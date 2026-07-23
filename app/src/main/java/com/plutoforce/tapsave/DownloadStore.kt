package com.plutoforce.tapsave

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Persists a history of downloads (newest first) in SharedPreferences. */
object DownloadStore {

    private const val PREFS = "tapsave_history"
    private const val KEY = "items"
    private const val MAX = 200

    data class Item(
        val id: Long,
        val name: String,
        val url: String,
        val uri: String,
        val audio: Boolean
    )

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun all(context: Context): List<Item> {
        val out = ArrayList<Item>()
        runCatching {
            val array = JSONArray(prefs(context).getString(KEY, "[]").orEmpty())
            for (i in 0 until array.length()) {
                val o = array.getJSONObject(i)
                out.add(
                    Item(
                        o.getLong("id"),
                        o.optString("name"),
                        o.optString("url"),
                        o.optString("uri"),
                        o.optBoolean("audio", false)
                    )
                )
            }
        }
        return out
    }

    fun add(context: Context, name: String, url: String, uri: String, audio: Boolean) {
        val items = all(context).toMutableList()
        items.add(0, Item(System.currentTimeMillis(), name, url, uri, audio))
        save(context, items.take(MAX))
    }

    fun delete(context: Context, id: Long) {
        save(context, all(context).filterNot { it.id == id })
    }

    fun clearAll(context: Context) = save(context, emptyList())

    private fun save(context: Context, items: List<Item>) {
        val array = JSONArray()
        items.forEach { item ->
            array.put(
                JSONObject()
                    .put("id", item.id)
                    .put("name", item.name)
                    .put("url", item.url)
                    .put("uri", item.uri)
                    .put("audio", item.audio)
            )
        }
        prefs(context).edit().putString(KEY, array.toString()).apply()
    }
}
