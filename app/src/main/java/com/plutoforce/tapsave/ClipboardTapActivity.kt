package com.plutoforce.tapsave

import android.app.Activity
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.widget.Toast

/**
 * Invisible helper opened when the floating button is tapped. Because it briefly
 * holds window focus it can read the clipboard (a background service cannot on
 * Android 10+), pull out the copied link, hand it to [OverlayService], and close.
 */
class ClipboardTapActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip
        val copied = if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).coerceToText(this)?.toString()
        } else {
            null
        }

        val url = Prefs.firstUrl(copied)
        if (url == null) {
            Toast.makeText(this, "Copy a video link first, then tap the button", Toast.LENGTH_SHORT)
                .show()
        } else {
            val intent = Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_DOWNLOAD
                putExtra(OverlayService.EXTRA_URL, url)
            }
            startForegroundService(intent)
        }
        finish()
    }
}
