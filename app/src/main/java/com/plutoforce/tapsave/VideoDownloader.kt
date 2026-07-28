package com.plutoforce.tapsave

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Fetches a video (or audio) and saves it into the phone's gallery
 * ("Movies/TapSave") or music library ("Music/TapSave").
 *
 * Fast path: ask the backend to *resolve* the link, then pull the bytes straight
 * from the platform's CDN. That skips relaying the whole file through a small
 * free server, which is what made downloads slow. If the link can't be served
 * directly (needs merging, re-encoding, or mp3), we fall back to the backend
 * streaming it to us.
 */
object VideoDownloader {

    data class Result(
        val ok: Boolean,
        val message: String,
        val uri: String? = null,
        val name: String? = null,
        val audio: Boolean = false
    )

    private data class Resolved(val url: String, val headers: Map<String, String>)

    /**
     * Pokes the backend so it's already awake when a download starts. Free hosts
     * sleep when idle and can take a while to boot on the first request.
     */
    fun warmUp(backendBase: String) {
        val base = backendBase.trim().trimEnd('/')
        if (base.isEmpty()) return
        Thread {
            runCatching {
                val connection = (URL("$base/health").openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 10_000
                    readTimeout = 60_000
                }
                connection.responseCode
                connection.disconnect()
            }
        }.start()
    }

    fun download(
        context: Context,
        backendBase: String,
        videoUrl: String,
        audioOnly: Boolean,
        quality: String,
        onProgress: (Int) -> Unit
    ): Result {
        val base = backendBase.trim().trimEnd('/')
        if (base.isEmpty()) {
            return Result(false, "Set the server address in TapSave first")
        }

        // Try the direct-from-CDN path first; it's much faster.
        if (!audioOnly) {
            val resolved = runCatching { resolve(base, videoUrl, quality) }.getOrNull()
            if (resolved != null) {
                val direct = runCatching {
                    saveFrom(context, openDirect(resolved), audioOnly = false, onProgress = onProgress)
                }.getOrNull()
                if (direct != null && direct.ok) return direct
                // Direct attempt failed (expired link, odd headers) — fall through.
            }
        }

        return runCatching {
            saveFrom(
                context,
                openBackend(base, videoUrl, audioOnly, quality),
                audioOnly,
                onProgress
            )
        }.getOrElse { e -> Result(false, e.message ?: "Download failed") }
    }

    // --- Connections -------------------------------------------------------

    /** Asks the backend for a direct CDN link. Returns null if there isn't one. */
    private fun resolve(base: String, videoUrl: String, quality: String): Resolved? {
        val endpoint = "$base/resolve?url=" + URLEncoder.encode(videoUrl, "UTF-8") +
            "&quality=" + quality
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 120_000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                // Surface the backend's explanation rather than a silent fallback.
                throw IllegalStateException(readError(connection))
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val json = JSONObject(body)
            if (!json.optBoolean("direct", false)) return null
            val url = json.optString("url").orEmpty()
            if (url.isBlank()) return null

            val headers = HashMap<String, String>()
            json.optJSONObject("headers")?.let { obj ->
                obj.keys().forEach { key -> headers[key] = obj.optString(key) }
            }
            return Resolved(url, headers)
        } finally {
            connection.disconnect()
        }
    }

    private fun openDirect(resolved: Resolved): HttpURLConnection {
        val connection = (URL(resolved.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 120_000
            instanceFollowRedirects = true
        }
        resolved.headers.forEach { (key, value) ->
            runCatching { connection.setRequestProperty(key, value) }
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            connection.disconnect()
            throw IllegalStateException("CDN refused the download")
        }
        return connection
    }

    private fun openBackend(
        base: String,
        videoUrl: String,
        audioOnly: Boolean,
        quality: String
    ): HttpURLConnection {
        val endpoint = buildString {
            append(base)
            append("/download?url=")
            append(URLEncoder.encode(videoUrl, "UTF-8"))
            append("&quality=")
            append(quality)
            if (audioOnly) append("&audio=1")
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 30_000
            readTimeout = 300_000
            instanceFollowRedirects = true
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val message = readError(connection)
            connection.disconnect()
            throw IllegalStateException(message)
        }
        return connection
    }

    /** Pulls the human-readable sentence out of the backend's error body. */
    private fun readError(connection: HttpURLConnection): String {
        val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
        val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
        return when {
            !detail.isNullOrBlank() -> detail
            raw.isNotBlank() -> raw.take(140)
            else -> "The server couldn't download that video"
        }
    }

    // --- Saving ------------------------------------------------------------

    private fun saveFrom(
        context: Context,
        connection: HttpURLConnection,
        audioOnly: Boolean,
        onProgress: (Int) -> Unit
    ): Result {
        try {
            val total = connection.contentLengthLong
            val name = "TapSave_${System.currentTimeMillis()}" + if (audioOnly) ".mp3" else ".mp4"
            val resolver = context.contentResolver

            val collection = if (audioOnly) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relativePath = if (audioOnly) {
                Environment.DIRECTORY_MUSIC + "/TapSave"
            } else {
                Environment.DIRECTORY_MOVIES + "/TapSave"
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, if (audioOnly) "audio/mpeg" else "video/mp4")
                put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }

            val uri: Uri = resolver.insert(collection, values)
                ?: return Result(false, "Could not create the file")

            var wrote = 0L
            try {
                resolver.openOutputStream(uri).use { output ->
                    if (output == null) return Result(false, "Could not open the file for writing")
                    connection.inputStream.use { input ->
                        val buffer = ByteArray(128 * 1024)
                        var lastPct = -1
                        while (true) {
                            val n = input.read(buffer)
                            if (n < 0) break
                            output.write(buffer, 0, n)
                            wrote += n
                            if (total > 0) {
                                val pct = ((wrote * 100) / total).toInt()
                                if (pct != lastPct) {
                                    lastPct = pct
                                    onProgress(pct)
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                runCatching { resolver.delete(uri, null, null) }
                throw e
            }

            if (wrote <= 0L) {
                runCatching { resolver.delete(uri, null, null) }
                return Result(false, "The download came back empty")
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            val where = if (audioOnly) "Music/TapSave" else "Gallery (Movies/TapSave)"
            return Result(true, "Saved to $where", uri.toString(), name, audioOnly)
        } finally {
            connection.disconnect()
        }
    }
}
