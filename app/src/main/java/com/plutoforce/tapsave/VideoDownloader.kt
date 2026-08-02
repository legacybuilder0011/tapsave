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
        val audio: Boolean = false,
        /** Every file saved, when a post held more than one. */
        val parts: List<Part> = emptyList()
    )

    data class Part(val name: String, val uri: String, val audio: Boolean)

    private enum class Kind { VIDEO, AUDIO, IMAGE }

    private data class Resolved(val url: String, val headers: Map<String, String>)

    /** Whole-attempt budget, so a failing download never spins for minutes. */
    private const val TOTAL_BUDGET_MS = 55_000L
    private const val MIN_STEP_MS = 6_000

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

        // One budget for the whole attempt. Without this the fallbacks stack up
        // — page fetch, then /resolve, then /download — and a failing download
        // could sit there for well over two minutes before admitting defeat.
        val deadline = System.currentTimeMillis() + TOTAL_BUDGET_MS

        if (!audioOnly) {
            // Platforms refuse datacenter IPs, so resolve on the phone (which has
            // an ordinary mobile IP) and pull straight from the platform's CDN.
            // This is both the reliable path and by far the fastest.
            if (VideoPageExtractor.canHandle(videoUrl)) {
                val pieces = runCatching {
                    // Bounded so a platform that won't answer can't eat the budget.
                    VideoPageExtractor.resolveAll(
                        videoUrl,
                        remaining(deadline).coerceAtMost(26_000),
                        quality
                    )
                }.getOrNull().orEmpty()

                // A carousel or photo post: save every slide, not just the first.
                if (pieces.size > 1) {
                    val album = runCatching { saveAlbum(context, pieces, onProgress) }.getOrNull()
                    if (album != null && album.ok) return album
                }
                val onDevice = pieces.firstOrNull()
                if (onDevice != null) {
                    val saved = runCatching {
                        saveFrom(
                            context,
                            openDirect(Resolved(onDevice.url, onDevice.headers), remaining(deadline)),
                            if (onDevice.isVideo) Kind.VIDEO else Kind.IMAGE,
                            onProgress = onProgress
                        )
                    }.getOrNull()
                    if (saved != null && saved.ok) return saved
                }
            }

            if (remaining(deadline) < MIN_STEP_MS) return timedOut(videoUrl)

            // Asking the server to resolve a TikTok is a guaranteed dead end —
            // it's the datacenter IP that TikTok refuses. Don't burn the budget.
            if (!VideoPageExtractor.isBlockedForServers(videoUrl)) {
                val resolved = runCatching {
                    resolve(base, videoUrl, quality, remaining(deadline))
                }.getOrNull()
                if (resolved != null) {
                    val direct = runCatching {
                        saveFrom(
                            context,
                            openDirect(resolved, remaining(deadline)),
                            Kind.VIDEO,
                            onProgress = onProgress
                        )
                    }.getOrNull()
                    if (direct != null && direct.ok) return direct
                }
            }
        }

        if (remaining(deadline) < MIN_STEP_MS) return timedOut(videoUrl)

        // For platforms that refuse datacenter IPs the server is a long shot, so
        // give it a short slice rather than the whole remaining budget — that
        // was the difference between failing at 58s and failing at ~35s.
        val serverBudget = if (!audioOnly && VideoPageExtractor.isBlockedForServers(videoUrl)) {
            remaining(deadline).coerceAtMost(12_000)
        } else {
            remaining(deadline)
        }

        return runCatching {
            saveFrom(
                context,
                openBackend(base, videoUrl, audioOnly, quality, serverBudget),
                if (audioOnly) Kind.AUDIO else Kind.VIDEO,
                onProgress
            )
        }.getOrElse { e ->
            val message = e.message
            if (message.isNullOrBlank() || e is java.net.SocketTimeoutException) {
                timedOut(videoUrl)
            } else {
                Result(false, message)
            }
        }
    }

    private fun remaining(deadline: Long): Int =
        (deadline - System.currentTimeMillis()).coerceAtLeast(0L).toInt()

    private fun timedOut(videoUrl: String): Result {
        val where = if (VideoPageExtractor.isBlockedForServers(videoUrl)) {
            "Couldn't reach this video. Check your connection and try again."
        } else {
            "Took too long — try again, or check the link is public."
        }
        return Result(false, where)
    }

    // --- Connections -------------------------------------------------------

    /** Asks the backend for a direct CDN link. Returns null if there isn't one. */
    private fun resolve(base: String, videoUrl: String, quality: String, budgetMs: Int): Resolved? {
        val endpoint = "$base/resolve?url=" + URLEncoder.encode(videoUrl, "UTF-8") +
            "&quality=" + quality
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = budgetMs.coerceIn(MIN_STEP_MS, 25_000)
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

    private fun openDirect(resolved: Resolved, budgetMs: Int): HttpURLConnection {
        val connection = (URL(resolved.url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 10_000
            readTimeout = budgetMs.coerceIn(MIN_STEP_MS, 45_000)
            instanceFollowRedirects = true
        }
        resolved.headers.forEach { (key, value) ->
            runCatching { connection.setRequestProperty(key, value) }
        }
        // A ranged request answers 206, not 200 — both are fine.
        val code = connection.responseCode
        if (code != HttpURLConnection.HTTP_OK && code != HttpURLConnection.HTTP_PARTIAL) {
            connection.disconnect()
            throw IllegalStateException("CDN refused the download")
        }
        return connection
    }

    private fun openBackend(
        base: String,
        videoUrl: String,
        audioOnly: Boolean,
        quality: String,
        budgetMs: Int
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
            connectTimeout = 10_000
            // Bounded by whatever is left of the overall budget.
            readTimeout = budgetMs.coerceIn(MIN_STEP_MS, 45_000)
            instanceFollowRedirects = true
        }
        if (connection.responseCode != HttpURLConnection.HTTP_OK) {
            val message = readError(connection)
            connection.disconnect()
            throw IllegalStateException(message)
        }
        return connection
    }

    /** Instagram serves jpg, TikTok often webp — keep whatever came back. */
    private fun imageExtension(connection: HttpURLConnection): String {
        val type = connection.contentType.orEmpty().substringAfter("image/", "").substringBefore(";").trim()
        return when (type.lowercase()) {
            "webp" -> ".webp"
            "png" -> ".png"
            "heic" -> ".heic"
            else -> ".jpg"
        }
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

    /**
     * Saves every slide of a carousel or photo post.
     *
     * Each slide gets its own slice of the progress bar, so ten pictures fill it
     * once rather than snapping back to zero ten times. One slide failing
     * doesn't lose the rest — the count says what actually landed.
     */
    private fun saveAlbum(
        context: Context,
        pieces: List<VideoPageExtractor.Media>,
        onProgress: (Int) -> Unit
    ): Result {
        val saved = ArrayList<Part>()
        pieces.forEachIndexed { index, piece ->
            val slice = 100 / pieces.size
            val done = index * slice
            val outcome = runCatching {
                saveFrom(
                    context,
                    // Per slide, so a long carousel isn't cut off by one budget.
                    openDirect(Resolved(piece.url, piece.headers), PER_ITEM_MS),
                    if (piece.isVideo) Kind.VIDEO else Kind.IMAGE
                ) { pct -> onProgress(done + pct * slice / 100) }
            }.getOrNull()
            if (outcome != null && outcome.ok && outcome.uri != null && outcome.name != null) {
                saved.add(Part(outcome.name, outcome.uri, outcome.audio))
            }
        }
        if (saved.isEmpty()) return Result(false, "None of the slides would download")
        onProgress(100)

        val message = if (saved.size == pieces.size) {
            "Saved all ${saved.size} to your gallery"
        } else {
            "Saved ${saved.size} of ${pieces.size} — the rest wouldn't download"
        }
        return Result(true, message, saved.first().uri, saved.first().name, false, saved)
    }

    /** A single slide's own timeout, separate from the whole-post budget. */
    private const val PER_ITEM_MS = 25_000

    private fun saveFrom(
        context: Context,
        connection: HttpURLConnection,
        kind: Kind,
        onProgress: (Int) -> Unit
    ): Result {
        try {
            val total = connection.contentLengthLong
            val audioOnly = kind == Kind.AUDIO
            val extension = when (kind) {
                Kind.AUDIO -> ".mp3"
                Kind.VIDEO -> ".mp4"
                Kind.IMAGE -> imageExtension(connection)
            }
            val name = "TapSave_${System.currentTimeMillis()}$extension"
            val resolver = context.contentResolver

            val collection = when (kind) {
                Kind.AUDIO -> MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Kind.VIDEO -> MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                Kind.IMAGE -> MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }
            val relativePath = when (kind) {
                Kind.AUDIO -> Environment.DIRECTORY_MUSIC + "/TapSave"
                Kind.VIDEO -> Environment.DIRECTORY_MOVIES + "/TapSave"
                Kind.IMAGE -> Environment.DIRECTORY_PICTURES + "/TapSave"
            }
            val mime = when (kind) {
                Kind.AUDIO -> "audio/mpeg"
                Kind.VIDEO -> "video/mp4"
                Kind.IMAGE -> "image/" + extension.removePrefix(".").replace("jpg", "jpeg")
            }

            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
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

            val where = when (kind) {
                Kind.AUDIO -> "Music/TapSave"
                Kind.VIDEO -> "Gallery (Movies/TapSave)"
                Kind.IMAGE -> "Gallery (Pictures/TapSave)"
            }
            return Result(true, "Saved to $where", uri.toString(), name, audioOnly)
        } finally {
            connection.disconnect()
        }
    }
}
