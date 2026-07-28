package com.plutoforce.tapsave

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible helper opened when the floating button is tapped. On Android 10+ an
 * app may only read the clipboard while it holds window focus, so we wait for
 * [onWindowFocusChanged] before reading — reading in onCreate returns null
 * because the window has not gained focus yet.
 *
 * A copied video link means "download this". Anything else (no link, or a link
 * already downloaded) means "save a WhatsApp status".
 */
class ClipboardTapActivity : Activity() {

    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Safety net: if focus somehow never arrives, still try shortly after.
        Handler(Looper.getMainLooper()).postDelayed({ readAndFinish() }, 500L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) readAndFinish()
    }

    private fun readAndFinish() {
        if (handled) return
        handled = true

        val url = Prefs.firstUrl(clipboardText())
        // A video link the user hasn't downloaded yet always means "download
        // this" — no matter how long ago it was copied. Only when there's no
        // such link do we fall through to saving a WhatsApp status.
        val isNewLink = url != null &&
            Prefs.isSupportedVideoLink(url) &&
            url != Prefs.lastDownloadedUrl(this)

        val intent = if (isNewLink) {
            // Just copied a link → download it (TikTok/Instagram/etc.).
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_DOWNLOAD
                putExtra(OverlayService.EXTRA_URL, url)
            }
        } else {
            // Not a fresh link → save the WhatsApp status being viewed (instant).
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SAVE_STATUS
            }
        }
        startForegroundService(intent)
        finish()
        overridePendingTransition(0, 0)
    }

    private fun clipboardText(): String? = runCatching {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return@runCatching null
        if (clip.itemCount <= 0) return@runCatching null
        clip.getItemAt(0).coerceToText(this)?.toString()
    }.getOrNull()
}
