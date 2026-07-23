# TapSave backend

A tiny [yt-dlp](https://github.com/yt-dlp/yt-dlp) service the TapSave Android app
calls to fetch a video and stream it back to the phone.

Why a server at all? TikTok, Instagram, YouTube and Pinterest expose no public
"download this video" API, and reliable extraction is not practical to run on a
phone. yt-dlp does the extraction; the app just downloads the file it returns.

> Personal use only. Download content you own or have permission for.
> Downloading other people's videos or stripping watermarks can violate a
> platform's Terms of Service and the creator's copyright.

## Run locally

```bash
cd server
pip install -r requirements.txt
uvicorn app:app --host 0.0.0.0 --port 8080
```

Test it:

```bash
curl "http://localhost:8080/health"
curl -L "http://localhost:8080/download?url=<VIDEO_URL>" -o out.mp4
```

## Run with Docker

```bash
cd server
docker build -t tapsave-backend .
docker run -p 8080:8080 tapsave-backend
```

## Let your phone reach it

The phone needs a URL it can open over the internet:

- **Same Wi‑Fi:** use your computer's LAN address, e.g. `http://192.168.1.20:8080`.
- **Anywhere:** expose it with a tunnel such as `ngrok http 8080`, or deploy the
  Docker image to a small host (Fly.io, Render, a VPS, etc.).

Put that base URL (no trailing `/download`) into the TapSave app's **Server
address** field.

## Endpoints

- `GET /health` → `{"ok": true}`
- `GET /download?url=<video url>` → streams an `mp4` back with a
  `Content-Disposition` filename.
