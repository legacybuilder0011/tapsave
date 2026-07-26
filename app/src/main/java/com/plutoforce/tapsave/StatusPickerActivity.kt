package com.plutoforce.tapsave

import android.app.Activity
import android.content.Intent
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.GridView
import android.widget.ImageView
import android.widget.Toast
import java.util.concurrent.Executors

/**
 * Shown when the floating button is tapped but we can't tell which status is on
 * screen (WhatsApp pre-downloads some statuses, so the newest file isn't always
 * the one being viewed). Displays the newest few as thumbnails over WhatsApp so
 * the user taps the one they just watched — then it saves instantly.
 */
class StatusPickerActivity : Activity() {

    private val handler = Handler(Looper.getMainLooper())
    private val executor = Executors.newFixedThreadPool(3)
    private var statuses: List<StatusSaver.Status> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status_picker)

        statuses = StatusSaver.list()
        if (statuses.isEmpty()) {
            Toast.makeText(this, "No status found", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        val grid = findViewById<GridView>(R.id.pickerGrid)
        grid.adapter = PickerAdapter()
        grid.setOnItemClickListener { _, _, position, _ -> choose(statuses[position]) }

        // Tapping the dimmed area outside the sheet dismisses.
        findViewById<View>(R.id.dimArea).setOnClickListener { dismiss() }
        findViewById<View>(R.id.pickerClose).setOnClickListener { dismiss() }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun choose(status: StatusSaver.Status) {
        startForegroundService(
            Intent(this, OverlayService::class.java).apply {
                action = OverlayService.ACTION_SAVE_STATUS_FILE
                putExtra(OverlayService.EXTRA_PATH, status.file.absolutePath)
            }
        )
        dismiss()
    }

    private fun dismiss() {
        finish()
        overridePendingTransition(0, 0)
    }

    private inner class PickerAdapter : BaseAdapter() {
        override fun getCount(): Int = statuses.size
        override fun getItem(position: Int): Any = statuses[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@StatusPickerActivity)
                    .inflate(R.layout.item_status_picker, parent, false)
            val status = statuses[position]
            val thumb = view.findViewById<ImageView>(R.id.statusThumb)
            view.findViewById<View>(R.id.playBadge).visibility =
                if (status.isVideo) View.VISIBLE else View.GONE
            view.findViewById<View>(R.id.savedBadge).visibility =
                if (StatusSaver.isAlreadySaved(this@StatusPickerActivity, status)) {
                    View.VISIBLE
                } else {
                    View.GONE
                }

            thumb.setImageDrawable(null)
            val token = status.file.absolutePath
            thumb.tag = token
            executor.execute {
                val bmp = runCatching {
                    if (status.isVideo) {
                        ThumbnailUtils.createVideoThumbnail(status.file, Size(240, 240), null)
                    } else {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(status.file.absolutePath, opts)
                    }
                }.getOrNull()
                if (bmp != null) handler.post {
                    if (thumb.tag == token) thumb.setImageBitmap(bmp)
                }
            }
            return view
        }
    }
}
