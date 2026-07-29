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
 * The clipboard can also come back empty for a moment right after another app
 * writes to it (tapping "Copy link" and the button in quick succession), so we
 * retry briefly instead of immediately deciding there is no link. Getting that
 * wrong sent people to the WhatsApp status picker when they meant to download.
 *
 * A copied video link means "download this". Anything else means "save a
 * WhatsApp status".
 */
class ClipboardTapActivity : Activity() {

    private companion object {
        const val RETRY_DELAY_MS = 120L
        const val MAX_ATTEMPTS = 10      // ~1.2s of retrying
        const val FALLBACK_MS = 1800L
    }

    private val handler = Handler(Looper.getMainLooper())
    private var handled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Safety net: if focus somehow never arrives, decide anyway.
        handler.postDelayed({ decide(clipboardText()) }, FALLBACK_MS)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) attemptRead(0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    /** Polls the clipboard until it has something usable, or we run out of tries. */
    private fun attemptRead(attempt: Int) {
        if (handled) return
        val text = clipboardText()
        if (Prefs.firstUrl(text) != null || attempt >= MAX_ATTEMPTS) {
            decide(text)
        } else {
            handler.postDelayed({ attemptRead(attempt + 1) }, RETRY_DELAY_MS)
        }
    }

    private fun decide(clipboard: String?) {
        if (handled) return
        handled = true

        val url = Prefs.firstUrl(clipboard)
        // A video link the user hasn't downloaded yet always means "download
        // this" — no matter how long ago it was copied. Only when there's no
        // such link do we fall through to saving a WhatsApp status.
        val isNewLink = url != null &&
            Prefs.isSupportedVideoLink(url) &&
            url != Prefs.lastDownloadedUrl(this)

        val intent = if (isNewLink) {
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_DOWNLOAD
                putExtra(OverlayService.EXTRA_URL, url)
            }
        } else {
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SAVE_STATUS
                // Lets the service explain itself properly when a link was
                // copied but isn't one we can download.
                putExtra(OverlayService.EXTRA_HAD_LINK, url != null)
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
