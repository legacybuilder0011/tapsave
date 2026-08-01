package com.plutoforce.tapsave

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.ContentValues
import android.content.Intent
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.view.View
import android.widget.TextView
import android.widget.Toast

/**
 * Shows the spoken transcript of a copied video link.
 *
 * TikTok publishes auto-captions alongside the video, so this costs nothing and
 * takes a second or two. Instagram publishes no equivalent, so we say so plainly
 * rather than pretending to try.
 */
class TranscriptActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var body: TextView
    private lateinit var status: TextView
    private var transcript = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_transcript)

        body = findViewById(R.id.transcriptText)
        status = findViewById(R.id.transcriptStatus)

        findViewById<View>(R.id.backButton).setOnClickListener { finish() }
        findViewById<View>(R.id.copyButton).setOnClickListener { copyToClipboard() }
        findViewById<View>(R.id.shareButton).setOnClickListener { share() }
        findViewById<View>(R.id.saveButton).setOnClickListener { saveAsText() }
        findViewById<View>(R.id.fetchButton).setOnClickListener { fetchFromClipboard() }

        setActionsEnabled(false)
        fetchFromClipboard()
    }

    private fun fetchFromClipboard() {
        val url = Prefs.firstUrl(clipboardText())
        if (url == null) {
            status.text = getString(R.string.transcript_no_link)
            return
        }
        if (!url.contains("tiktok.com", ignoreCase = true)) {
            status.text = getString(R.string.transcript_unsupported)
            body.text = ""
            setActionsEnabled(false)
            return
        }

        status.text = getString(R.string.transcript_working)
        body.text = ""
        setActionsEnabled(false)

        Thread {
            val text = runCatching { VideoPageExtractor.transcript(url) }.getOrNull()
            handler.post {
                if (text.isNullOrBlank()) {
                    status.text = getString(R.string.transcript_none)
                    setActionsEnabled(false)
                } else {
                    transcript = text
                    body.text = text
                    status.text = getString(
                        R.string.transcript_ready,
                        text.split(Regex("\\s+")).count { it.isNotBlank() }
                    )
                    setActionsEnabled(true)
                }
            }
        }.start()
    }

    private fun setActionsEnabled(enabled: Boolean) {
        listOf(R.id.copyButton, R.id.shareButton, R.id.saveButton).forEach { id ->
            findViewById<View>(id).apply {
                isEnabled = enabled
                alpha = if (enabled) 1f else 0.4f
            }
        }
    }

    private fun copyToClipboard() {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Transcript", transcript))
        toast(getString(R.string.transcript_copied))
    }

    private fun share() {
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, transcript)
        }
        startActivity(Intent.createChooser(intent, getString(R.string.transcript_share)))
    }

    /** Writes the transcript next to the videos, as Documents/TapSave/*.txt. */
    private fun saveAsText() {
        val name = "TapSave_transcript_${System.currentTimeMillis()}.txt"
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
            put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS + "/TapSave")
        }
        val uri = contentResolver.insert(MediaStore.Files.getContentUri("external"), values)
        if (uri == null) {
            toast(getString(R.string.transcript_save_failed))
            return
        }
        val ok = runCatching {
            contentResolver.openOutputStream(uri)?.use { it.write(transcript.toByteArray()) }
        }.isSuccess
        toast(getString(if (ok) R.string.transcript_saved else R.string.transcript_save_failed))
    }

    private fun clipboardText(): String? = runCatching {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = clipboard.primaryClip ?: return@runCatching null
        if (clip.itemCount <= 0) null else clip.getItemAt(0).coerceToText(this)?.toString()
    }.getOrNull()

    private fun toast(message: String) = Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
}
