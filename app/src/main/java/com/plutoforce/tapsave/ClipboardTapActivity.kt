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
 * because the window has not gained focus yet. We then pull out the copied
 * link, hand it to [OverlayService], and close.
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

        val copied = runCatching {
            val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            val clip = clipboard.primaryClip
            if (clip != null && clip.itemCount > 0) {
                clip.getItemAt(0).coerceToText(this)?.toString()
            } else {
                null
            }
        }.getOrNull()

        val url = Prefs.firstUrl(copied)
        val isFreshLink = url != null && url != Prefs.lastDownloadedUrl(this)

        val intent = if (isFreshLink) {
            // A newly copied link → download it (TikTok/Instagram/etc.).
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_DOWNLOAD
                putExtra(OverlayService.EXTRA_URL, url)
            }
        } else {
            // No new link copied → save the WhatsApp status being viewed.
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SAVE_STATUS
            }
        }
        startForegroundService(intent)
        finish()
        overridePendingTransition(0, 0)
    }
}
