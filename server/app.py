"""TapSave download backend.

A tiny HTTP service that fetches a public video with yt-dlp and streams the
resulting file back to the phone. It exists because TikTok / Instagram /
YouTube / Pinterest expose no public download API, and reliable extraction is
not practical to do on-device.

Intended for personal use with content you own or have permission to download.
Downloading other people's videos or removing watermarks may violate a
platform's Terms of Service and the creator's copyright.
"""

import glob
import os
import shutil
import subprocess
import tempfile
import uuid
from urllib.parse import urlparse

from fastapi import FastAPI, HTTPException, Query
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
    <p class="sub">Paste a video link (TikTok, Instagram, YouTube, Pinterest) and download it.</p>
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
# Highest resolution per quality (any codec). H.265 results are transcoded to
# H.264 afterwards (see maybe_transcode) so they keep audio and play everywhere.
QUALITY_FORMATS = {
    "high": "bv*+ba/b",
    "medium": "bv*[height<=720]+ba/b[height<=720]/b",
    "low": "bv*[height<=480]+ba/b[height<=480]/b",
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
                "-c:v", "libx264", "-preset", "veryfast", "-crf", "23",
                "-c:a", "aac", "-b:a", "192k", "-movflags", "+faststart", out,
            ],
            check=True, capture_output=True, timeout=280,
        )
        return out
    except Exception:
        return path

# YouTube blocks the default web client from server IPs ("Sign in to confirm
# you're not a bot"). Which clients work depends on whether we have cookies:
# without cookies the mobile/tv clients dodge the check better; with cookies the
# default/mweb clients actually make use of them.
YT_ARGS_NO_COOKIES = "youtube:player_client=android,ios,tv"
YT_ARGS_WITH_COOKIES = "youtube:player_client=default,mweb"

# Optional Netscape cookies.txt. On Render, add it as a Secret File named
# cookies.txt (mounted at /etc/secrets/cookies.txt). If present it's passed to
# yt-dlp, which lets YouTube downloads through from the server IP.
COOKIES_FILE = os.environ.get("YTDLP_COOKIES", "/etc/secrets/cookies.txt")

_YOUTUBE_HOSTS = ("youtube.com", "youtu.be", "youtube-nocookie.com")


def is_youtube(url: str) -> bool:
    host = urlparse(url).netloc.lower()
    return any(h in host for h in _YOUTUBE_HOSTS)


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
    }


@app.get("/probe", response_class=PlainTextResponse)
def probe(url: str = Query(...)):
    """Diagnostic: show available formats and which one is selected + why."""
    if not url.startswith("http"):
        raise HTTPException(status_code=400, detail="bad url")
    has_cookies = os.path.exists(COOKIES_FILE)
    common = [
        "--no-warnings", "--force-ipv4",
        "--extractor-args", YT_ARGS_WITH_COOKIES if has_cookies else YT_ARGS_NO_COOKIES,
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
        raise HTTPException(
            status_code=400,
            detail="YouTube isn't supported here. TikTok, Instagram and Pinterest work.",
        )

    workdir = tempfile.mkdtemp(prefix="tapsave_")
    output_template = os.path.join(workdir, f"{uuid.uuid4().hex}.%(ext)s")
    has_cookies = os.path.exists(COOKIES_FILE)

    cmd = [
        "yt-dlp",
        "--no-playlist",
        "--no-warnings",
        "--force-ipv4",
        "--extractor-args",
        YT_ARGS_WITH_COOKIES if has_cookies else YT_ARGS_NO_COOKIES,
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

    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=300)
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Download timed out")
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode(errors="ignore") if exc.stderr else "unknown error"
        detail = detail[-1500:] if debug else detail[-400:]
        if "not a bot" in detail or "Sign in to confirm" in detail:
            raise HTTPException(
                status_code=502,
                detail=(
                    "YouTube can't be downloaded from this server — Google blocks "
                    "cloud hosts. TikTok, Instagram and Pinterest work fine."
                ),
            )
        raise HTTPException(status_code=502, detail=f"yt-dlp failed: {detail}")

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
