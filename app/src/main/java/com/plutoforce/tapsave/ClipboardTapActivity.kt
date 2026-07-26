package com.plutoforce.tapsave

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper

/**
 * Invisible helper opened when the floating button is tapped. On Android 10+ an
 * app may only read the clipboard while it holds window focus, so we wait for
 * [onWindowFocusChanged] before reading — reading in onCreate returns null
 * because the window has not gained focus yet.
 *
 * A link only counts as a download request if it was copied in the last
 * [FRESH_WINDOW_MS]. Otherwise an old link left in the clipboard would hijack
 * the button while the user is browsing WhatsApp statuses, sending them to the
 * (slow, network) download path instead of the instant local status save.
 */
class ClipboardTapActivity : Activity() {

    companion object {
        private const val FRESH_WINDOW_MS = 60_000L
    }

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

        val fresh = freshlyCopiedText()
        val url = Prefs.firstUrl(fresh)
        val isNewLink = url != null && url != Prefs.lastDownloadedUrl(this)

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

    /** Clipboard text, but only if it was copied within [FRESH_WINDOW_MS]. */
    private fun freshlyCopiedText(): String? = runCatching {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return@runCatching null
        if (clip.itemCount <= 0) return@runCatching null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val copiedAt = clip.description?.timestamp ?: 0L
            // timestamp is 0 on some ROMs — treat unknown age as "not fresh" so
            // a stale link can never hijack a status save.
            if (copiedAt <= 0L) return@runCatching null
            if (System.currentTimeMillis() - copiedAt > FRESH_WINDOW_MS) return@runCatching null
        }
        clip.getItemAt(0).coerceToText(this)?.toString()
    }.getOrNull()
}
