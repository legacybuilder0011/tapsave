package com.plutoforce.tapsave

import android.content.ContentValues
import android.content.Context
import android.os.Environment
import android.provider.MediaStore
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Calls the TapSave backend to fetch a video and saves the returned file into
 * the phone's shared Downloads/TapSave folder via MediaStore.
 */
object VideoDownloader {

    data class Result(val ok: Boolean, val message: String)

    fun download(context: Context, backendBase: String, videoUrl: String): Result {
        val base = backendBase.trim().trimEnd('/')
        if (base.isEmpty()) {
            return Result(false, "Set the server address in TapSave first")
        }

        val endpoint = "$base/download?url=" + URLEncoder.encode(videoUrl, "UTF-8")
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
                return Result(false, "Server error $code ${error.take(140)}".trim())
            }

            val name = "video_${System.currentTimeMillis()}.mp4"
            val resolver = context.contentResolver
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, name)
                put(MediaStore.Downloads.MIME_TYPE, "video/mp4")
                put(
                    MediaStore.Downloads.RELATIVE_PATH,
                    Environment.DIRECTORY_DOWNLOADS + "/TapSave"
                )
                put(MediaStore.Downloads.IS_PENDING, 1)
            }

            val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values)
                ?: return Result(false, "Could not create the file")

            resolver.openOutputStream(uri).use { output ->
                if (output == null) return Result(false, "Could not open the file for writing")
                connection.inputStream.use { input -> input.copyTo(output, 64 * 1024) }
            }

            values.clear()
            values.put(MediaStore.Downloads.IS_PENDING, 0)
            resolver.update(uri, values, null, null)

            return Result(true, "Saved to Downloads/TapSave")
        } catch (e: Exception) {
            return Result(false, e.message ?: "Download failed")
        } finally {
            connection.disconnect()
        }
    }
}
