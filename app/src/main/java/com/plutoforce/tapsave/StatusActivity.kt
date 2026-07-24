package com.plutoforce.tapsave

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Size
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.util.concurrent.Executors

/**
 * Browses the WhatsApp statuses currently on the phone as a grid of thumbnails.
 * Tap a status to save it to the gallery, long-press to open it, or use
 * "Save all" to keep every one at once.
 */
class StatusActivity : Activity() {

    private lateinit var grid: GridView
    private lateinit var emptyText: TextView
    private lateinit var permissionPanel: View
    private lateinit var grantButton: Button
    private lateinit var saveAllButton: Button

    private val mainHandler = Handler(Looper.getMainLooper())
    private val thumbExecutor = Executors.newFixedThreadPool(3)
    private var statuses: List<StatusSaver.Status> = emptyList()
    private lateinit var adapter: StatusAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_status)

        grid = findViewById(R.id.statusGrid)
        emptyText = findViewById(R.id.statusEmpty)
        permissionPanel = findViewById(R.id.permissionPanel)
        grantButton = findViewById(R.id.grantAccessButton)
        saveAllButton = findViewById(R.id.saveAllButton)

        adapter = StatusAdapter()
        grid.adapter = adapter

        grid.setOnItemClickListener { _, _, position, _ -> saveOne(statuses[position]) }
        grid.setOnItemLongClickListener { _, _, position, _ ->
            openExternally(statuses[position]); true
        }

        grantButton.setOnClickListener { requestAccess() }
        saveAllButton.setOnClickListener { saveAll() }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    override fun onDestroy() {
        thumbExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        refresh()
    }

    private fun refresh() {
        if (!StatusSaver.hasAccess(this)) {
            permissionPanel.visibility = View.VISIBLE
            grid.visibility = View.GONE
            emptyText.visibility = View.GONE
            saveAllButton.visibility = View.GONE
            return
        }
        permissionPanel.visibility = View.GONE
        statuses = StatusSaver.list()
        adapter.notifyDataSetChanged()
        val empty = statuses.isEmpty()
        grid.visibility = if (empty) View.GONE else View.VISIBLE
        saveAllButton.visibility = if (empty) View.GONE else View.VISIBLE
        emptyText.visibility = if (empty) View.VISIBLE else View.GONE
        emptyText.text = if (StatusSaver.statusFolderExists()) {
            getString(R.string.status_none)
        } else {
            getString(R.string.status_no_folder)
        }
    }

    private fun requestAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.status_access_title))
                .setMessage(getString(R.string.status_access_message))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(getString(R.string.status_open_settings)) { _, _ ->
                    val intent = Intent(
                        Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.parse("package:$packageName")
                    )
                    runCatching { startActivity(intent) }.onFailure {
                        startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    }
                }
                .show()
        } else {
            requestPermissions(arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE), 7)
        }
    }

    private fun saveOne(status: StatusSaver.Status) {
        thumbExecutor.execute {
            val ok = StatusSaver.save(this, status)
            mainHandler.post {
                toast(if (ok) getString(R.string.status_saved_one) else getString(R.string.status_save_failed))
            }
        }
    }

    private fun saveAll() {
        val toSave = statuses
        if (toSave.isEmpty()) return
        toast(getString(R.string.status_saving_all, toSave.size))
        thumbExecutor.execute {
            var saved = 0
            for (s in toSave) if (StatusSaver.save(this, s)) saved++
            mainHandler.post { toast(getString(R.string.status_saved_all, saved)) }
        }
    }

    private fun openExternally(status: StatusSaver.Status) {
        try {
            val uri = FileProvider.getUriForFile(
                this, "$packageName.fileprovider", status.file
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, if (status.isVideo) "video/*" else "image/*")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast(getString(R.string.status_open_failed))
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class StatusAdapter : BaseAdapter() {
        override fun getCount(): Int = statuses.size
        override fun getItem(position: Int): Any = statuses[position]
        override fun getItemId(position: Int): Long = position.toLong()

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@StatusActivity)
                    .inflate(R.layout.item_status, parent, false)
            val status = statuses[position]
            val thumb = view.findViewById<ImageView>(R.id.statusThumb)
            val playBadge = view.findViewById<View>(R.id.playBadge)

            playBadge.visibility = if (status.isVideo) View.VISIBLE else View.GONE
            thumb.setImageDrawable(null)
            // Tag the view with the file it should show, so a recycled row that
            // finished loading an old thumbnail doesn't overwrite the new one.
            val token = status.file.absolutePath
            thumb.tag = token
            thumbExecutor.execute {
                val bmp = runCatching {
                    if (status.isVideo) {
                        ThumbnailUtils.createVideoThumbnail(status.file, Size(240, 240), null)
                    } else {
                        val opts = BitmapFactory.Options().apply { inSampleSize = 4 }
                        BitmapFactory.decodeFile(status.file.absolutePath, opts)
                    }
                }.getOrNull()
                if (bmp != null) mainHandler.post {
                    if (thumb.tag == token) thumb.setImageBitmap(bmp)
                }
            }
            return view
        }
    }
}
