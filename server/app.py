"""TapSave download backend.

A tiny HTTP service that fetches a public video with yt-dlp and streams the
resulting file back to the phone. It exists because TikTok / Instagram /
Pinterest expose no public download API, and reliable extraction is not
practical to do on-device.

/resolve is the fast path: it hands the phone a direct CDN link so the bytes
never pass through this server. /download is the fallback for anything that
needs merging, transcoding or mp3 extraction.

Intended for personal use with content you own or have permission to download.
Downloading other people's videos or removing watermarks may violate a
platform's Terms of Service and the creator's copyright.
"""

import glob
import json
import urllib.request
import os
import shutil
import subprocess
import tempfile
import uuid
from urllib.parse import urlparse

from fastapi import FastAPI, File, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse, HTMLResponse, PlainTextResponse

# A bundled ffmpeg so yt-dlp can always merge video+audio and make mp3s, even if
# the host has no system ffmpeg. Without merging, videos come out silent.
try:
    import imageio_ffmpeg
    FFMPEG_LOCATION = imageio_ffmpeg.get_ffmpeg_exe()
except Exception:
    FFMPEG_LOCATION = None

app = FastAPI(title="TapSave backend")

# Simple web downloader so TapSave works from a PC (or any browser) with no app:
# open this server's URL, paste a link, click Download.
INDEX_HTML = """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>TapSave</title>
<style>
  :root { color-scheme: light dark; }
  * { box-sizing: border-box; }
  body { margin: 0; min-height: 100vh; display: flex; align-items: center;
         justify-content: center; font-family: system-ui, sans-serif;
         background: #0f0f14; color: #f4f4f6; padding: 24px; }
  .card { width: 100%; max-width: 520px; background: #1a1a22; border-radius: 16px;
          padding: 28px; box-shadow: 0 12px 40px rgba(0,0,0,.4); }
  h1 { margin: 0 0 6px; font-size: 26px; }
  p.sub { margin: 0 0 20px; color: #a7a7b4; font-size: 14px; }
  input, select { width: 100%; padding: 14px; border-radius: 10px; border: 1px solid #35354a;
          background: #12121a; color: #fff; font-size: 15px; }
  .row { display: flex; gap: 10px; margin-top: 12px; align-items: center; }
  .row label { font-size: 14px; color: #c9c9d6; white-space: nowrap; }
  button { width: 100%; margin-top: 12px; padding: 14px; border: 0; border-radius: 10px;
           background: #6c4dff; color: #fff; font-size: 16px; font-weight: 600;
           cursor: pointer; }
  button.secondary { background: #2a2a38; }
  button:disabled { opacity: .6; cursor: default; }
  #bar { height: 8px; background: #2a2a38; border-radius: 6px; margin-top: 16px; overflow: hidden; display: none; }
  #barFill { height: 100%; width: 0%; background: #6c4dff; transition: width .2s; }
  #status { margin-top: 12px; font-size: 14px; min-height: 20px; color: #c9c9d6; }
  .note { margin-top: 18px; font-size: 12px; color: #7d7d8c; line-height: 1.5; }
</style>
</head>
<body>
  <div class="card">
    <h1>TapSave</h1>
    <p class="sub">Paste a video link (TikTok, Instagram, Pinterest) and download it.</p>
    <button id="paste">📋 Paste link &amp; download</button>
    <input id="url" type="url" placeholder="or paste the link here…" autocomplete="off">
    <div class="row">
      <label for="quality">Quality</label>
      <select id="quality">
        <option value="high">High</option>
        <option value="medium">Medium (720p)</option>
        <option value="low">Data saver (480p)</option>
      </select>
    </div>
    <div class="row">
      <input type="checkbox" id="audio" style="width:auto">
      <label for="audio">Audio only (MP3)</label>
    </div>
    <button id="go" class="secondary">Download</button>
    <div id="bar"><div id="barFill"></div></div>
    <div id="status"></div>
    <p class="note">For content you own or have permission to download. First download
      after a while can take ~1 minute while the server wakes up.</p>
  </div>
<script>
  const urlInput = document.getElementById('url');
  const goBtn = document.getElementById('go');
  const pasteBtn = document.getElementById('paste');
  const statusEl = document.getElementById('status');
  const qualitySel = document.getElementById('quality');
  const audioChk = document.getElementById('audio');
  const bar = document.getElementById('bar');
  const barFill = document.getElementById('barFill');

  function setProgress(pct) {
    if (pct == null) { bar.style.display = 'none'; return; }
    bar.style.display = 'block';
    barFill.style.width = pct + '%';
  }

  async function pasteAndDownload() {
    try {
      const text = await navigator.clipboard.readText();
      const match = text && text.match(/https?:\\/\\/[^\\s"'<>]+/i);
      if (!match) { statusEl.textContent = 'No link found in your clipboard — copy a video link first.'; return; }
      urlInput.value = match[0];
      download();
    } catch (e) {
      statusEl.textContent = 'Your browser blocked clipboard access — paste the link in the box below instead.';
    }
  }

  async function download() {
    const url = urlInput.value.trim();
    if (!url) { statusEl.textContent = 'Paste a link first.'; return; }
    goBtn.disabled = true;
    setProgress(null);
    statusEl.textContent = 'Working… preparing the download (this can take a bit).';
    const audio = audioChk.checked;
    const q = 'download?url=' + encodeURIComponent(url) + '&quality=' + qualitySel.value + (audio ? '&audio=1' : '');
    try {
      const resp = await fetch('/' + q);
      if (!resp.ok) {
        const text = await resp.text();
        statusEl.textContent = 'Error: ' + text.slice(0, 200);
        return;
      }
      const total = parseInt(resp.headers.get('Content-Length') || '0', 10);
      const reader = resp.body.getReader();
      const chunks = [];
      let received = 0;
      statusEl.textContent = 'Downloading…';
      while (true) {
        const { done, value } = await reader.read();
        if (done) break;
        chunks.push(value);
        received += value.length;
        if (total) setProgress(Math.round((received / total) * 100));
      }
      const blob = new Blob(chunks);
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = (audio ? 'audio_' : 'video_') + Date.now() + (audio ? '.mp3' : '.mp4');
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(link.href);
      setProgress(100);
      statusEl.textContent = 'Done! Check your downloads folder.';
    } catch (e) {
      statusEl.textContent = 'Error: ' + e;
    } finally {
      goBtn.disabled = false;
    }
  }

  goBtn.addEventListener('click', download);
  pasteBtn.addEventListener('click', pasteAndDownload);
  urlInput.addEventListener('keydown', (e) => { if (e.key === 'Enter') download(); });
</script>
</body>
</html>"""


@app.get("/", response_class=HTMLResponse)
def index():
    return INDEX_HTML

# Video format per requested quality. Prefer mp4 and merge so the device gets
# one ready-to-play file; fall back to any best stream.
# Prefer H.264 (fast, plays with audio everywhere, no re-encoding). Only fall
# back to other codecs (e.g. H.265) when no H.264 stream exists — those rare
# cases get transcoded afterwards (see maybe_transcode).
_AVC = "vcodec~='^(avc|h264)'"
QUALITY_FORMATS = {
    "high": f"b[{_AVC}]/bv*[{_AVC}]+ba/bv*+ba/b",
    "medium": f"b[{_AVC}][height<=720]/b[height<=720]/bv*[height<=720]+ba/b",
    "low": f"b[{_AVC}][height<=480]/b[height<=480]/bv*[height<=480]+ba/b",
}


def _video_is_hevc(path: str) -> bool:
    if not FFMPEG_LOCATION:
        return False
    try:
        info = subprocess.run(
            [FFMPEG_LOCATION, "-i", path], capture_output=True, timeout=60
        ).stderr.decode(errors="ignore").lower()
    except Exception:
        return False
    return "video:" in info and ("hevc" in info or "h265" in info)


def maybe_transcode(path: str, workdir: str) -> str:
    """H.265 videos play silently in most players; re-encode them to H.264+AAC."""
    if not _video_is_hevc(path):
        return path
    out = os.path.join(workdir, "h264.mp4")
    try:
        subprocess.run(
            [
                FFMPEG_LOCATION, "-y", "-i", path,
                "-c:v", "libx264", "-preset", "ultrafast", "-crf", "26",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out,
            ],
            check=True, capture_output=True, timeout=280,
        )
        return out
    except Exception:
        return path

# Optional Netscape cookies.txt. On Render, add it as a Secret File named
# cookies.txt (mounted at /etc/secrets/cookies.txt). Mainly useful for
# private/age-gated Instagram posts.
COOKIES_FILE = os.environ.get("YTDLP_COOKIES", "/etc/secrets/cookies.txt")

_YOUTUBE_HOSTS = ("youtube.com", "youtu.be", "youtube-nocookie.com")

YOUTUBE_MESSAGE = "YouTube isn't supported. TikTok, Instagram and Pinterest work."

MOBILE_UA = (
    "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 "
    "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"
)


def is_youtube(url: str) -> bool:
    host = urlparse(url).netloc.lower()
    return any(h in host for h in _YOUTUBE_HOSTS)


def is_tiktok(url: str) -> bool:
    return "tiktok" in urlparse(url).netloc.lower()


def attempt_variants(url: str):
    """
    Extra yt-dlp arguments to try in order.

    TikTok often answers "Video not available" to datacenter IPs on its default
    API host, so we retry through the alternate regional hosts and finally with a
    phone user-agent. Each attempt is cheap because it fails fast.
    """
    if is_tiktok(url):
        return [
            [],
            ["--extractor-args", "tiktok:api_hostname=api22-normal-c-useast2a.tiktokv.com"],
            ["--extractor-args", "tiktok:api_hostname=api16-normal-c-useast1a.tiktokv.com"],
            ["--user-agent", MOBILE_UA],
        ]
    return [[], ["--user-agent", MOBILE_UA]]


def friendly_error(detail: str, url: str) -> str:
    """Turns a wall of yt-dlp output into one sentence a person can act on."""
    low = detail.lower()
    if "video not available" in low or "content isn't available" in low:
        if is_tiktok(url):
            # TikTok routinely refuses datacenter IPs even for public videos, so
            # don't blame the link — the app retries on the phone itself.
            return (
                "TikTok blocked the server for this video. The app will try "
                "again from your phone — if that also fails, try another link."
            )
        return "That video isn't available to download (private, deleted or region-locked)."
    if "login required" in low or "log in" in low or "rate-limit" in low:
        return "That post needs a login, so it can't be downloaded."
    if "unsupported url" in low:
        return "That link isn't a video TapSave can download."
    if "unable to download webpage" in low or "timed out" in low:
        return "Couldn't reach that site just now. Try again in a moment."
    return "Couldn't download that video. Try another link."


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/diag")
def diag():
    """Quick check: yt-dlp version and whether the cookies file is mounted."""
    try:
        version = subprocess.run(
            ["yt-dlp", "--version"], capture_output=True, timeout=30
        ).stdout.decode(errors="ignore").strip()
    except Exception as e:  # noqa: BLE001
        version = f"error: {e}"
    present = os.path.exists(COOKIES_FILE)
    ffmpeg_ok = False
    if FFMPEG_LOCATION:
        try:
            ffmpeg_ok = subprocess.run(
                [FFMPEG_LOCATION, "-version"], capture_output=True, timeout=30
            ).returncode == 0
        except Exception:
            ffmpeg_ok = False
    return {
        "yt_dlp_version": version,
        "ffmpeg_location": FFMPEG_LOCATION,
        "ffmpeg_ok": ffmpeg_ok,
        "cookies_present": present,
        "cookies_path": COOKIES_FILE,
        "cookies_bytes": os.path.getsize(COOKIES_FILE) if present else 0,
        # So the app (and a browser) can confirm transcription is switched on
        # without exposing the key itself.
        "asr_configured": bool(ASR_API_KEY),
        "asr_model": ASR_MODEL if ASR_API_KEY else None,
        "asr_endpoint": ASR_BASE_URL if ASR_API_KEY else None,
    }


@app.get("/probe", response_class=PlainTextResponse)
def probe(url: str = Query(...)):
    """Diagnostic: show available formats and which one is selected + why."""
    if not url.startswith("http"):
        raise HTTPException(status_code=400, detail="bad url")
    has_cookies = os.path.exists(COOKIES_FILE)
    common = [
        "--no-warnings", "--force-ipv4",
    ]
    cookie_args = []
    if has_cookies:
        tmp = tempfile.mkdtemp()
        dst = os.path.join(tmp, "c.txt")
        try:
            shutil.copyfile(COOKIES_FILE, dst)
            cookie_args = ["--cookies", dst]
        except OSError:
            pass

    def run(extra):
        try:
            p = subprocess.run(
                ["yt-dlp"] + common + cookie_args + extra + [url],
                capture_output=True, timeout=120,
            )
            return (p.stdout.decode(errors="ignore") + "\n" + p.stderr.decode(errors="ignore"))
        except Exception as e:  # noqa: BLE001
            return f"error: {e}"

    formats = run(["-F"])
    chosen = run(["--simulate", "-f", QUALITY_FORMATS["high"], "-v"])
    # Keep the response readable.
    return (
        "=== AVAILABLE FORMATS ===\n" + formats[-4000:]
        + "\n\n=== SELECTION (bv*+ba/b) ===\n"
        + "\n".join(
            ln for ln in chosen.splitlines()
            if ("Downloading" in ln or "format" in ln.lower() or "Merg" in ln or "ERROR" in ln)
        )[-3000:]
    )


# --- Speech-to-text --------------------------------------------------------
# Set ASR_API_KEY in the host's environment to switch transcription on. Any
# OpenAI-compatible audio endpoint works; Groq's is the default because its
# free tier covers personal use and whisper-large-v3-turbo is quick.
ASR_API_KEY = os.environ.get("ASR_API_KEY", "")
ASR_BASE_URL = os.environ.get("ASR_BASE_URL", "https://api.groq.com/openai/v1")
ASR_MODEL = os.environ.get("ASR_MODEL", "whisper-large-v3-turbo")

ASR_NOT_CONFIGURED = (
    "Transcription isn't switched on. Add an ASR_API_KEY to the server to enable it."
)


def _transcribe_file(source_path: str, workdir: str) -> str:
    """Strips the audio and sends it to the speech model. Returns the words."""
    if not FFMPEG_LOCATION:
        raise HTTPException(status_code=503, detail="No ffmpeg available to extract audio")

    # Mono 16 kHz is what speech models want, and keeps the upload small.
    audio = os.path.join(workdir, "audio.mp3")
    try:
        subprocess.run(
            [
                FFMPEG_LOCATION, "-y", "-i", source_path, "-vn",
                "-ac", "1", "-ar", "16000", "-b:a", "64k", audio,
            ],
            check=True, capture_output=True, timeout=300,
        )
    except Exception:  # noqa: BLE001
        raise HTTPException(status_code=502, detail="Couldn't read the audio track")

    return _send_to_speech(audio)


def _send_to_speech(audio_path: str) -> str:
    """Hands an audio file to the speech model and returns the words."""
    try:
        import requests

        with open(audio_path, "rb") as handle:
            reply = requests.post(
                ASR_BASE_URL.rstrip("/") + "/audio/transcriptions",
                headers={"Authorization": f"Bearer {ASR_API_KEY}"},
                files={"file": (os.path.basename(audio_path), handle, "application/octet-stream")},
                data={"model": ASR_MODEL, "response_format": "json"},
                timeout=300,
            )
    except Exception as exc:  # noqa: BLE001
        raise HTTPException(status_code=502, detail=f"Speech service unreachable: {exc}")

    if reply.status_code != 200:
        raise HTTPException(
            status_code=502,
            detail=f"Speech service said {reply.status_code}: {reply.text[:200]}",
        )
    text = (reply.json() or {}).get("text", "").strip()
    if not text:
        raise HTTPException(status_code=502, detail="Nothing was recognised in that audio")
    return text


@app.post("/transcribe_upload")
async def transcribe_upload(
    file: UploadFile = File(...),
    already_audio: bool = Query(False, description="Skip ffmpeg; the upload is audio"),
):
    """
    Transcribe media the phone sends us directly.

    TikTok and Instagram hand out CDN links that are tied to the address that
    asked for them, so a link the phone resolved often refuses this server. When
    that happens the phone uploads the file instead and we work from the bytes.
    """
    if not ASR_API_KEY:
        raise HTTPException(status_code=503, detail=ASR_NOT_CONFIGURED)

    workdir = tempfile.mkdtemp(prefix="tapsave_up_")
    try:
        source = os.path.join(workdir, "upload")
        with open(source, "wb") as handle:
            while True:
                chunk = await file.read(1024 * 256)
                if not chunk:
                    break
                handle.write(chunk)
        if os.path.getsize(source) == 0:
            raise HTTPException(status_code=400, detail="Empty upload")
        # The phone can strip the audio itself, which saves it a large upload and
        # saves us a transcode.
        if already_audio:
            audio = os.path.join(workdir, "audio.m4a")
            os.rename(source, audio)
            return {"text": _send_to_speech(audio)}
        return {"text": _transcribe_file(source, workdir)}
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


@app.get("/transcribe")
def transcribe(
    media: str = Query(..., description="Direct media URL (from /resolve or the phone)"),
    referer: str = Query("", description="Page the media came from, for the CDN"),
):
    """
    Speech-to-text for a video the caller has already resolved.

    The caller passes a direct CDN link rather than a page URL: TikTok and
    Instagram refuse this server's datacenter IP for their APIs, but their CDNs
    serve the file to anyone, so the phone resolves the link and we only fetch
    bytes. Audio is stripped out here and sent to the speech provider.
    """
    if not ASR_API_KEY:
        raise HTTPException(status_code=503, detail=ASR_NOT_CONFIGURED)
    if not media.startswith("http"):
        raise HTTPException(status_code=400, detail="media must be an http(s) URL")
    if not FFMPEG_LOCATION:
        raise HTTPException(status_code=503, detail="No ffmpeg available to extract audio")

    workdir = tempfile.mkdtemp(prefix="tapsave_asr_")
    try:
        source = os.path.join(workdir, "source")
        request = urllib.request.Request(
            media,
            headers={
                "User-Agent": MOBILE_UA,
                "Referer": referer or "https://www.tiktok.com/",
                "Accept": "*/*",
            },
        )
        try:
            with urllib.request.urlopen(request, timeout=120) as response, \
                    open(source, "wb") as handle:
                shutil.copyfileobj(response, handle, 1024 * 256)
        except Exception as exc:  # noqa: BLE001
            raise HTTPException(status_code=502, detail=f"Couldn't fetch the media: {exc}")

        return {"text": _transcribe_file(source, workdir)}
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


_HEIGHT_LIMIT = {"high": 10000, "medium": 720, "low": 480}


@app.get("/resolve")
def resolve(
    url: str = Query(..., description="Public video URL"),
    quality: str = Query("high", description="high | medium | low"),
):
    """
    Returns a direct CDN link for the video so the phone can download it itself.

    This is the fast path: the server only does the (quick) extraction, and the
    bytes travel straight from the platform's CDN to the phone instead of being
    relayed through this box. Only single-file streams that already contain both
    video and audio qualify; anything needing a merge falls back to /download.
    """
    if not (url.startswith("http://") or url.startswith("https://")):
        raise HTTPException(status_code=400, detail="URL must start with http(s)://")
    if is_youtube(url):
        raise HTTPException(status_code=400, detail=YOUTUBE_MESSAGE)

    base = ["yt-dlp", "-J", "--no-playlist", "--no-warnings", "--force-ipv4"]
    workdir = tempfile.mkdtemp(prefix="tapsave_res_")
    try:
        if os.path.exists(COOKIES_FILE):
            writable = os.path.join(workdir, "cookies.txt")
            try:
                shutil.copyfile(COOKIES_FILE, writable)
                base += ["--cookies", writable]
            except OSError:
                pass

        info = None
        last_error = "unknown error"
        for extra in attempt_variants(url):
            try:
                out = subprocess.run(
                    base + extra + [url], check=True, capture_output=True, timeout=90
                ).stdout
                info = json.loads(out.decode(errors="ignore"))
                break
            except subprocess.TimeoutExpired:
                last_error = "timed out"
            except subprocess.CalledProcessError as exc:
                last_error = exc.stderr.decode(errors="ignore") if exc.stderr else "failed"
            except (ValueError, json.JSONDecodeError):
                last_error = "could not read video info"

        if not info:
            raise HTTPException(status_code=502, detail=friendly_error(last_error, url))

        limit = _HEIGHT_LIMIT.get(quality, _HEIGHT_LIMIT["high"])
        best = None
        for fmt in info.get("formats") or []:
            # Needs its own audio and video, a usable URL, and a sane height.
            if not fmt.get("url"):
                continue
            if fmt.get("vcodec") in (None, "none") or fmt.get("acodec") in (None, "none"):
                continue
            if fmt.get("protocol") not in (None, "https", "http"):
                continue
            height = fmt.get("height") or 0
            if height and height > limit:
                continue
            codec = (fmt.get("vcodec") or "").lower()
            # H.264 first: TikTok's H.265 streams play silently on many phones.
            score = (
                1 if codec.startswith(("avc", "h264")) else 0,
                height,
                fmt.get("tbr") or 0,
            )
            if best is None or score > best[0]:
                best = (score, fmt)

        if not best:
            return {"direct": False}

        fmt = best[1]
        codec = (fmt.get("vcodec") or "").lower()
        if not codec.startswith(("avc", "h264")):
            # Would need re-encoding to keep audio working; let /download do it.
            return {"direct": False}

        return {
            "direct": True,
            "url": fmt["url"],
            "headers": fmt.get("http_headers") or {},
            "ext": fmt.get("ext") or "mp4",
        }
    finally:
        shutil.rmtree(workdir, ignore_errors=True)


@app.get("/download")
def download(
    url: str = Query(..., description="Public video URL to fetch"),
    audio: bool = Query(False, description="Download audio only (mp3)"),
    quality: str = Query("high", description="high | medium | low"),
    debug: bool = Query(False, description="Return more of the yt-dlp error"),
):
    if not (url.startswith("http://") or url.startswith("https://")):
        raise HTTPException(status_code=400, detail="URL must start with http(s)://")

    # Reject YouTube immediately — it can't be downloaded from a cloud server and
    # attempting it can wedge the free instance for other downloads.
    if is_youtube(url):
        raise HTTPException(status_code=400, detail=YOUTUBE_MESSAGE)

    workdir = tempfile.mkdtemp(prefix="tapsave_")
    output_template = os.path.join(workdir, f"{uuid.uuid4().hex}.%(ext)s")
    has_cookies = os.path.exists(COOKIES_FILE)

    cmd = [
        "yt-dlp",
        "--no-playlist",
        "--no-warnings",
        "--force-ipv4",
        "-o",
        output_template,
    ]
    if FFMPEG_LOCATION:
        cmd += ["--ffmpeg-location", FFMPEG_LOCATION]
    if audio:
        # Extract audio to mp3.
        cmd += ["-f", "bestaudio/best", "-x", "--audio-format", "mp3"]
    else:
        cmd += [
            "-f",
            QUALITY_FORMATS.get(quality, QUALITY_FORMATS["high"]),
            "--merge-output-format",
            "mp4",
            # Re-encode audio to AAC while merging so audio always survives in the
            # mp4 container (Opus/WebM audio can't be copied into mp4 and was
            # being dropped on longer videos, leaving them silent).
            "--postprocessor-args",
            "Merger:-c:v copy -c:a aac -b:a 192k",
        ]
    cmd.append(url)

    # yt-dlp writes the cookie jar back to the --cookies path, but a Render
    # Secret File is read-only, so copy it into the writable work dir first.
    if has_cookies:
        writable_cookies = os.path.join(workdir, "cookies.txt")
        try:
            shutil.copyfile(COOKIES_FILE, writable_cookies)
            cmd += ["--cookies", writable_cookies]
        except OSError:
            pass

    last_error = "unknown error"
    ok = False
    for extra in attempt_variants(url):
        try:
            subprocess.run(cmd + extra, check=True, capture_output=True, timeout=300)
            ok = True
            break
        except subprocess.TimeoutExpired:
            raise HTTPException(status_code=504, detail="Download timed out")
        except subprocess.CalledProcessError as exc:
            last_error = exc.stderr.decode(errors="ignore") if exc.stderr else "unknown error"

    if not ok:
        detail = last_error[-1500:] if debug else friendly_error(last_error, url)
        raise HTTPException(status_code=502, detail=detail)

    files = glob.glob(os.path.join(workdir, "*"))
    if not files:
        raise HTTPException(status_code=502, detail="No file was produced")

    path = max(files, key=os.path.getsize)
    if not audio:
        path = maybe_transcode(path, workdir)
    return FileResponse(
        path,
        media_type="audio/mpeg" if audio else "video/mp4",
        filename=os.path.basename(path),
    )
