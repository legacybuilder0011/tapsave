package com.plutoforce.tapsave

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Calls the TapSave backend to fetch a video (or audio), reports download
 * progress, and saves the file into the phone's gallery ("Movies/TapSave") or
 * music library ("Music/TapSave") so it shows up in the Gallery / Music apps.
 */
object VideoDownloader {

    data class Result(
        val ok: Boolean,
        val message: String,
        val uri: String? = null,
        val name: String? = null,
        val audio: Boolean = false
    )

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

        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val error = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                return Result(false, "Server error $code ${error.take(160)}".trim())
            }

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

            val uri = resolver.insert(collection, values)
                ?: return Result(false, "Could not create the file")

            resolver.openOutputStream(uri).use { output ->
                if (output == null) return Result(false, "Could not open the file for writing")
                connection.inputStream.use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var readSoFar = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        readSoFar += n
                        if (total > 0) {
                            val pct = ((readSoFar * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }

            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            val where = if (audioOnly) "Music/TapSave" else "Gallery (Movies/TapSave)"
            return Result(true, "Saved to $where", uri.toString(), name, audioOnly)
        } catch (e: Exception) {
            return Result(false, e.message ?: "Download failed")
        } finally {
            connection.disconnect()
        }
    }
}
