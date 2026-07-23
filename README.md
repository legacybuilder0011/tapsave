# TapSave

A standalone Android video downloader. Copy a video link (TikTok, Instagram,
YouTube, Pinterest, …), tap the floating button, and the video is saved to your
phone's `Downloads/TapSave` folder. Links can also be sent in via the Share
sheet.

TapSave has **no accessibility service** on purpose — Google restricts
accessibility-service apps to accessibility use, so a downloader must be
separate.

> For content you own or have permission to download. Downloading other people's
> videos or stripping watermarks may violate a platform's Terms of Service and
> the creator's copyright.

## How it works

Extraction is not practical to run on a phone, so TapSave calls a small
[yt-dlp](https://github.com/yt-dlp/yt-dlp) backend (in [`server/`](server/)) that
fetches the video and streams the file back. You run that backend somewhere the
phone can reach and paste its address into the app.

1. Start the backend — see [`server/README.md`](server/README.md).
2. Install the app (build it, or grab `TapSave.apk` from the Releases page).
3. In the app: set the **Server address**, grant **display over other apps**,
   and **Start floating button**.
4. Copy a video link (or Share → TapSave), tap the floating button → it
   downloads and saves to `Downloads/TapSave`.

## Build

Push to `main` and GitHub Actions builds `TapSave.apk` and publishes it to a
rolling `latest` Release. Or locally: `./gradlew assembleDebug` →
`app/build/outputs/apk/debug/app-debug.apk`.

- Android 10+ (API 29). Android Gradle Plugin 8.11, Gradle 8.13, JDK 17.
