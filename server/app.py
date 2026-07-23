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
import subprocess
import tempfile
import uuid

from fastapi import FastAPI, HTTPException, Query
from fastapi.responses import FileResponse, HTMLResponse

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
  input { width: 100%; padding: 14px; border-radius: 10px; border: 1px solid #35354a;
          background: #12121a; color: #fff; font-size: 15px; }
  button { width: 100%; margin-top: 12px; padding: 14px; border: 0; border-radius: 10px;
           background: #6c4dff; color: #fff; font-size: 16px; font-weight: 600;
           cursor: pointer; }
  button.secondary { background: #2a2a38; }
  button:disabled { opacity: .6; cursor: default; }
  #status { margin-top: 16px; font-size: 14px; min-height: 20px; color: #c9c9d6; }
  .note { margin-top: 18px; font-size: 12px; color: #7d7d8c; line-height: 1.5; }
</style>
</head>
<body>
  <div class="card">
    <h1>TapSave</h1>
    <p class="sub">Paste a video link (TikTok, Instagram, YouTube, Pinterest) and download it.</p>
    <button id="paste">📋 Paste link &amp; download</button>
    <input id="url" type="url" placeholder="or paste the link here…" autocomplete="off">
    <button id="go" class="secondary">Download</button>
    <div id="status"></div>
    <p class="note">For content you own or have permission to download. First download
      after a while can take ~1 minute while the server wakes up.</p>
  </div>
<script>
  const urlInput = document.getElementById('url');
  const goBtn = document.getElementById('go');
  const pasteBtn = document.getElementById('paste');
  const statusEl = document.getElementById('status');

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
    statusEl.textContent = 'Working… fetching the video (this can take a bit).';
    try {
      const resp = await fetch('/download?url=' + encodeURIComponent(url));
      if (!resp.ok) {
        const text = await resp.text();
        statusEl.textContent = 'Error: ' + text.slice(0, 200);
        return;
      }
      const blob = await resp.blob();
      const link = document.createElement('a');
      link.href = URL.createObjectURL(blob);
      link.download = 'video_' + Date.now() + '.mp4';
      document.body.appendChild(link);
      link.click();
      link.remove();
      URL.revokeObjectURL(link.href);
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

# Prefer the best video+audio and merge to mp4 so the phone gets one
# ready-to-play file. For TikTok, yt-dlp already returns the no-watermark
# rendition. Falls back to any best single stream if a merge isn't possible.
YTDLP_FORMAT = "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b[ext=mp4]/b"

# YouTube blocks the default web client from server IPs ("Sign in to confirm
# you're not a bot"). These clients help, but a cookies file is the reliable fix.
YOUTUBE_EXTRACTOR_ARGS = "youtube:player_client=default,android,ios,tv"

# Optional Netscape cookies.txt. On Render, add it as a Secret File named
# cookies.txt (mounted at /etc/secrets/cookies.txt). If present it's passed to
# yt-dlp, which lets YouTube downloads through from the server IP.
COOKIES_FILE = os.environ.get("YTDLP_COOKIES", "/etc/secrets/cookies.txt")


@app.get("/health")
def health():
    return {"ok": True}


@app.get("/download")
def download(url: str = Query(..., description="Public video URL to fetch")):
    if not (url.startswith("http://") or url.startswith("https://")):
        raise HTTPException(status_code=400, detail="URL must start with http(s)://")

    workdir = tempfile.mkdtemp(prefix="tapsave_")
    output_template = os.path.join(workdir, f"{uuid.uuid4().hex}.%(ext)s")

    cmd = [
        "yt-dlp",
        "--no-playlist",
        "--no-warnings",
        "--force-ipv4",
        "-f",
        YTDLP_FORMAT,
        "--merge-output-format",
        "mp4",
        "--extractor-args",
        YOUTUBE_EXTRACTOR_ARGS,
        "-o",
        output_template,
        url,
    ]

    # Use cookies when available (needed for YouTube from a datacenter IP).
    if os.path.exists(COOKIES_FILE):
        cmd += ["--cookies", COOKIES_FILE]

    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=300)
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Download timed out")
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode(errors="ignore")[-500:] if exc.stderr else "unknown error"
        if "confirm you" in detail or "not a bot" in detail or "Sign in" in detail:
            raise HTTPException(
                status_code=502,
                detail=(
                    "YouTube blocked the server (bot check). Add a cookies.txt "
                    "Secret File on the host to enable YouTube. Other sites still work."
                ),
            )
        raise HTTPException(status_code=502, detail=f"yt-dlp failed: {detail}")

    files = glob.glob(os.path.join(workdir, "*"))
    if not files:
        raise HTTPException(status_code=502, detail="No file was produced")

    path = max(files, key=os.path.getsize)
    return FileResponse(
        path,
        media_type="video/mp4",
        filename=os.path.basename(path),
    )
