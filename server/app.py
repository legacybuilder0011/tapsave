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
from fastapi.responses import FileResponse

app = FastAPI(title="TapSave backend")

# Prefer the best video+audio and merge to mp4 so the phone gets one
# ready-to-play file. For TikTok, yt-dlp already returns the no-watermark
# rendition. Falls back to any best single stream if a merge isn't possible.
YTDLP_FORMAT = "bv*[ext=mp4]+ba[ext=m4a]/bv*+ba/b[ext=mp4]/b"

# YouTube blocks the default web client from server IPs ("Sign in to confirm
# you're not a bot"). The android/ios players usually work without cookies.
YOUTUBE_EXTRACTOR_ARGS = "youtube:player_client=android,ios,web"


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

    try:
        subprocess.run(cmd, check=True, capture_output=True, timeout=300)
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Download timed out")
    except subprocess.CalledProcessError as exc:
        detail = exc.stderr.decode(errors="ignore")[-500:] if exc.stderr else "unknown error"
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
