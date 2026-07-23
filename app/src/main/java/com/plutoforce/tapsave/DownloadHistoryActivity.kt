package com.plutoforce.tapsave

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast

/** Lists past downloads with Open, Share and Delete. */
class DownloadHistoryActivity : Activity() {

    private lateinit var listView: ListView
    private lateinit var emptyText: TextView
    private lateinit var adapter: DownloadAdapter
    private var items: List<DownloadStore.Item> = emptyList()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_download_history)

        listView = findViewById(R.id.downloadList)
        emptyText = findViewById(R.id.emptyText)
        adapter = DownloadAdapter()
        listView.adapter = adapter

        findViewById<Button>(R.id.clearAllButton).setOnClickListener {
            if (items.isEmpty()) return@setOnClickListener
            AlertDialog.Builder(this)
                .setTitle(getString(R.string.clear_all))
                .setMessage("Clear the download list? (Your saved files are not deleted.)")
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    DownloadStore.clearAll(this)
                    refresh()
                }
                .show()
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun refresh() {
        items = DownloadStore.all(this)
        adapter.notifyDataSetChanged()
        emptyText.visibility = if (items.isEmpty()) View.VISIBLE else View.GONE
    }

    private fun mimeFor(item: DownloadStore.Item) = if (item.audio) "audio/*" else "video/*"

    private fun open(item: DownloadStore.Item) {
        try {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.parse(item.uri), mimeFor(item))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(intent)
        } catch (e: Exception) {
            toast("Couldn't open (file may have been deleted)")
        }
    }

    private fun share(item: DownloadStore.Item) {
        try {
            val intent = Intent(Intent.ACTION_SEND).apply {
                type = mimeFor(item)
                putExtra(Intent.EXTRA_STREAM, Uri.parse(item.uri))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(intent, "Share"))
        } catch (e: Exception) {
            toast("Couldn't share (file may have been deleted)")
        }
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private inner class DownloadAdapter : BaseAdapter() {
        override fun getCount(): Int = items.size
        override fun getItem(position: Int): Any = items[position]
        override fun getItemId(position: Int): Long = items[position].id

        override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
            val view = convertView
                ?: LayoutInflater.from(this@DownloadHistoryActivity)
                    .inflate(R.layout.item_download, parent, false)
            val item = items[position]
            val label = (if (item.audio) "🎵 " else "🎬 ") + item.name
            view.findViewById<TextView>(R.id.itemName).text = label
            view.findViewById<Button>(R.id.itemOpen).setOnClickListener { open(item) }
            view.findViewById<Button>(R.id.itemShare).setOnClickListener { share(item) }
            view.findViewById<Button>(R.id.itemDelete).setOnClickListener {
                DownloadStore.delete(this@DownloadHistoryActivity, item.id)
                refresh()
            }
            return view
        }
    }
}
