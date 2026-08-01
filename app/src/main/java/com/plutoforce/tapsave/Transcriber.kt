package com.plutoforce.tapsave

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Speech-to-text for videos that carry no caption track.
 *
 * The phone resolves the media link (TikTok and Instagram refuse the server's
 * datacenter IP), then hands that CDN link to the backend, which fetches the
 * bytes, strips the audio and runs it through a speech model. Doing the
 * recognition server-side keeps the API key off the phone.
 */
object Transcriber {

    /** Returns the recognised words, or throws with the server's explanation. */
    fun fromMedia(backendBase: String, mediaUrl: String, pageUrl: String): String? {
        val base = backendBase.trim().trimEnd('/')
        if (base.isEmpty()) return null

        val endpoint = base + "/transcribe?media=" +
            URLEncoder.encode(mediaUrl, "UTF-8") +
            "&referer=" + URLEncoder.encode(pageUrl, "UTF-8")

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            // Recognition takes a while on a long video, and the free host may
            // be waking up as well.
            readTimeout = 300_000
        }
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                throw IllegalStateException(
                    detail?.takeIf { it.isNotBlank() } ?: "Transcription failed"
                )
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            return JSONObject(body).optString("text").takeIf { it.isNotBlank() }
        } finally {
            connection.disconnect()
        }
    }
}
