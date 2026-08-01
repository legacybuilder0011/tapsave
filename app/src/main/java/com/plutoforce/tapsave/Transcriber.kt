package com.plutoforce.tapsave

import org.json.JSONObject
import java.io.DataOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Speech-to-text for videos that carry no caption track.
 *
 * Two routes, cheapest first. Normally the phone resolves the media link and the
 * server fetches those bytes itself. But TikTok and Instagram hand out CDN links
 * tied to the address that asked for them, so a link resolved on the phone is
 * often refused for the server — in that case the phone downloads the file and
 * uploads it instead. Recognition stays server-side either way, which keeps the
 * API key off the phone.
 */
object Transcriber {

    /** Recognised words. Throws with the server's own explanation on failure. */
    fun transcribe(
        context: android.content.Context,
        backendBase: String,
        mediaUrl: String,
        pageUrl: String,
        mediaHeaders: Map<String, String>
    ): String {
        val base = backendBase.trim().trimEnd('/')
        require(base.isNotEmpty()) { "No server address set" }

        // 1. Let the server fetch it — no upload, no mobile data.
        val direct = runCatching { viaServerFetch(base, mediaUrl, pageUrl) }
        direct.getOrNull()?.let { return it }

        // 2. The CDN wouldn't serve the server; send the bytes ourselves.
        return runCatching { viaUpload(context, base, mediaUrl, mediaHeaders) }
            .getOrElse { uploadError ->
                // Report whichever failure explains the most.
                val first = direct.exceptionOrNull()?.message
                throw IllegalStateException(uploadError.message ?: first ?: "Transcription failed")
            }
    }

    private fun viaServerFetch(base: String, mediaUrl: String, pageUrl: String): String {
        val endpoint = base + "/transcribe?media=" +
            URLEncoder.encode(mediaUrl, "UTF-8") +
            "&referer=" + URLEncoder.encode(pageUrl, "UTF-8")

        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            // Recognition takes a while, and a sleeping free host wakes slowly.
            readTimeout = 300_000
        }
        return readTranscript(connection)
    }

    /** Streams the media through this phone and up to the server as multipart. */
    private fun viaUpload(
        context: android.content.Context,
        base: String,
        mediaUrl: String,
        mediaHeaders: Map<String, String>
    ): String {
        // Strip the audio here first: uploading the whole video is what made
        // this slow, and the sound is all the speech model needs.
        val audio = AudioExtractor.extract(context.cacheDir, mediaUrl, mediaHeaders)
        if (audio != null) {
            try {
                return uploadFile(base, audio.inputStream(), "clip.m4a", alreadyAudio = true)
            } finally {
                audio.delete()
            }
        }

        val source = (URL(mediaUrl).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 20_000
            readTimeout = 120_000
            instanceFollowRedirects = true
            mediaHeaders.forEach { (key, value) -> runCatching { setRequestProperty(key, value) } }
        }
        val code = source.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            source.disconnect()
            throw IllegalStateException("Couldn't fetch the video to transcribe")
        }

        try {
            return uploadFile(base, source.inputStream, "clip.mp4", alreadyAudio = false)
        } finally {
            source.disconnect()
        }
    }

    /** Sends one file to the server as multipart/form-data. */
    private fun uploadFile(
        base: String,
        stream: java.io.InputStream,
        filename: String,
        alreadyAudio: Boolean
    ): String {
        val boundary = "----TapSave" + System.currentTimeMillis()
        val endpoint = "$base/transcribe_upload" + if (alreadyAudio) "?already_audio=1" else ""
        val upload = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 20_000
            readTimeout = 300_000
            setRequestProperty("Content-Type", "multipart/form-data; boundary=$boundary")
            setChunkedStreamingMode(256 * 1024)
        }

        DataOutputStream(upload.outputStream).use { out ->
            out.writeBytes("--$boundary\r\n")
            out.writeBytes(
                "Content-Disposition: form-data; name=\"file\"; filename=\"$filename\"\r\n"
            )
            out.writeBytes("Content-Type: application/octet-stream\r\n\r\n")
            stream.use { input ->
                val buffer = ByteArray(256 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    out.write(buffer, 0, read)
                }
            }
            out.writeBytes("\r\n--$boundary--\r\n")
        }
        return readTranscript(upload)
    }

    private fun readTranscript(connection: HttpURLConnection): String {
        try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                val raw = connection.errorStream?.bufferedReader()?.use { it.readText() }.orEmpty()
                val detail = runCatching { JSONObject(raw).optString("detail") }.getOrNull()
                throw IllegalStateException(
                    detail?.takeIf { it.isNotBlank() }
                        ?: "Server error ${connection.responseCode}"
                )
            }
            val body = connection.inputStream.bufferedReader().use { it.readText() }
            val text = JSONObject(body).optString("text")
            if (text.isBlank()) throw IllegalStateException("The transcript came back empty")
            return text
        } finally {
            connection.disconnect()
        }
    }
}
