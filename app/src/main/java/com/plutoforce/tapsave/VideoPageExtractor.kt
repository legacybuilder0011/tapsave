package com.plutoforce.tapsave

import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a video's direct file URL on the phone itself, by loading the post's own
 * page and reading the media link out of it.
 *
 * Social platforms block datacenter IPs — a cloud server asking TikTok for a
 * public video gets "Video not available". The phone has an ordinary mobile/home
 * IP that these sites serve normally, so doing it here both fixes the failures
 * and makes downloads fast, because nothing is relayed through a server.
 *
 * Per platform:
 *  - TikTok    "playAddr" (what the app itself plays — no watermark)
 *  - Instagram the post's /embed/ page, which is readable without logging in
 *  - Pinterest the direct v*.pinimg.com mp4
 *  - anything else, the standard og:video tag
 */
object VideoPageExtractor {

    data class Resolved(val url: String, val headers: Map<String, String>)

    private const val UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    private const val DESKTOP_UA =
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"

    /** Don't start another attempt with less time than this left. */
    private const val MIN_ATTEMPT_MS = 3_000

    private fun isInstagram(url: String) =
        url.contains("instagram.com", ignoreCase = true) ||
            url.contains("instagr.am", ignoreCase = true)

    /**
     * Platforms that refuse cloud/datacenter IPs. For these the server can't
     * help at all, so a failed on-device attempt should give up quickly rather
     * than spending the rest of the budget on a guaranteed failure.
     */
    fun isBlockedForServers(url: String): Boolean =
        url.contains("tiktok.com", ignoreCase = true) || isInstagram(url)

    /** Worth attempting on-device for anything except YouTube. */
    fun canHandle(url: String): Boolean =
        !url.contains("youtube.com", ignoreCase = true) &&
            !url.contains("youtu.be", ignoreCase = true)

    /**
     * Returns a directly downloadable link, or null if nothing worked.
     *
     * Every attempt shares one budget. Without that, Instagram's several routes
     * each waited out their own timeout in turn and the button sat there
     * counting for the better part of a minute before giving up.
     */
    fun resolve(pageUrl: String, budgetMs: Int = 20_000): Resolved? {
        val deadline = System.currentTimeMillis() + budgetMs
        fun left() = (deadline - System.currentTimeMillis()).toInt()

        // Instagram answers logged-out requests from a phone IP through its own
        // endpoints — far more reliable than scraping a page behind a login wall.
        if (isInstagram(pageUrl)) {
            if (left() > MIN_ATTEMPT_MS) instagramViaApi(pageUrl, left())?.let { return it }
            if (left() > MIN_ATTEMPT_MS) instagramViaGraphql(pageUrl, left())?.let { return it }
        }
        for (candidate in candidatePages(pageUrl)) {
            if (left() < MIN_ATTEMPT_MS) break
            val page = fetch(candidate, left()) ?: continue
            val media = findMediaUrl(page.html) ?: continue

            val headers = HashMap<String, String>()
            headers["User-Agent"] = UA
            headers["Referer"] = page.url
            headers["Accept"] = "*/*"
            headers["Accept-Language"] = "en-US,en;q=0.9"
            headers["Range"] = "bytes=0-"
            if (page.cookies.isNotBlank()) headers["Cookie"] = page.cookies
            return Resolved(media, headers)
        }
        return null
    }

    /**
     * Instagram won't show a post's media to a logged-out reader on the normal
     * page, but its embed page will — so try that first and keep the original as
     * a fallback.
     */
    private fun candidatePages(pageUrl: String): List<String> {
        if (!isInstagram(pageUrl)) return listOf(pageUrl)
        val match = Regex("/(reels?|p|tv)/([A-Za-z0-9_-]+)").find(pageUrl)
            ?: return listOf(pageUrl)
        val kind = if (match.groupValues[1].startsWith("reel")) "reel" else match.groupValues[1]
        val code = match.groupValues[2]
        val other = if (kind == "reel") "p" else "reel"
        return listOf(
            "https://www.instagram.com/$kind/$code/embed/captioned/",
            "https://www.instagram.com/$other/$code/embed/captioned/",
            "https://www.instagram.com/$kind/$code/embed/",
            pageUrl
        )
    }

    // --- Instagram media API ----------------------------------------------

    // Instagram's public web app id. Sending it makes the media endpoint answer
    // logged-out requests for public posts.
    private const val IG_APP_ID = "936619743392459"
    // Instagram's public post query, used when the media endpoint declines.
    private val IG_DOC_IDS = listOf(
        "8845758582119845", "9510064595728286", "10015901848480474"
    )

    // Instagram's own Android app user-agent: the media endpoint answers this
    // where it refuses a browser.
    private const val IG_APP_UA =
        "Instagram 269.0.0.18.75 Android (26/8.0.0; 480dpi; 1080x1920; OnePlus; " +
            "ONEPLUS A3010; OnePlus3T; qcom; en_US; 314665256)"
    private const val IG_ALPHABET =
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"

    /** A post's shortcode is its numeric id written in base64. */
    private fun shortcodeToMediaId(shortcode: String): String? {
        var id = java.math.BigInteger.ZERO
        val base = java.math.BigInteger.valueOf(64L)
        for (c in shortcode) {
            val index = IG_ALPHABET.indexOf(c)
            if (index < 0) return null
            id = id.multiply(base).add(java.math.BigInteger.valueOf(index.toLong()))
        }
        return if (id.signum() > 0) id.toString() else null
    }

    private fun shortcodeOf(pageUrl: String): String? =
        Regex("/(?:reels?|p|tv)/([A-Za-z0-9_-]+)").find(pageUrl)?.groupValues?.get(1)

    /**
     * Instagram's media endpoint, tried the way its own app asks.
     *
     * The browser host answers logged-out callers only sometimes; i.instagram.com
     * with an Instagram app user-agent is what actually serves public posts, and
     * is how working downloaders reach it. Both are tried, cheapest first.
     */
    private fun instagramViaApi(pageUrl: String, budgetMs: Int): Resolved? {
        val mediaId = shortcodeOf(pageUrl)?.let { shortcodeToMediaId(it) } ?: return null
        val attempts = listOf(
            "https://i.instagram.com/api/v1/media/$mediaId/info/" to IG_APP_UA,
            "https://www.instagram.com/api/v1/media/$mediaId/info/" to DESKTOP_UA
        )
        for ((endpoint, agent) in attempts) {
            val json = httpGet(
                endpoint,
                budgetMs,
                mapOf(
                    "User-Agent" to agent,
                    "x-ig-app-id" to IG_APP_ID,
                    "x-ig-www-claim" to "0",
                    "Accept" to "*/*",
                    "Referer" to "https://www.instagram.com/"
                )
            ) ?: continue
            videoFromApiJson(json)?.let { return instagramResolved(it) }
        }
        return null
    }

    private fun videoFromApiJson(json: String): String? = runCatching {
        val items = org.json.JSONObject(json).optJSONArray("items") ?: return@runCatching null
        val item = items.optJSONObject(0) ?: return@runCatching null
        // A carousel keeps its videos one level down.
        val holder = item.optJSONArray("carousel_media")?.optJSONObject(0) ?: item
        holder.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
    }.getOrNull()?.takeIf { it.isNotBlank() }

    /**
     * Instagram's public post query. Meta rotates the query id, so a few known
     * ones are tried rather than betting everything on a single value.
     */
    private fun instagramViaGraphql(pageUrl: String, budgetMs: Int): Resolved? {
        val shortcode = shortcodeOf(pageUrl) ?: return null
        val deadline = System.currentTimeMillis() + budgetMs

        for (docId in IG_DOC_IDS) {
            if (System.currentTimeMillis() + MIN_ATTEMPT_MS > deadline) break
            val body = "variables=" +
                java.net.URLEncoder.encode("{\"shortcode\":\"$shortcode\"}", "UTF-8") +
                "&doc_id=" + docId

            val json = runCatching {
                val connection = (
                    URL("https://www.instagram.com/graphql/query")
                        .openConnection() as HttpURLConnection
                    ).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 6_000
                    readTimeout = 10_000
                    setRequestProperty("User-Agent", DESKTOP_UA)
                    setRequestProperty("x-ig-app-id", IG_APP_ID)
                    setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                    setRequestProperty("Accept", "*/*")
                }
                try {
                    connection.outputStream.use { it.write(body.toByteArray()) }
                    if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                    connection.inputStream.bufferedReader().use { it.readText() }
                } finally {
                    connection.disconnect()
                }
            }.getOrNull() ?: continue

            val media = runCatching {
                val node = org.json.JSONObject(json)
                    .optJSONObject("data")
                    ?.optJSONObject("xdt_shortcode_media") ?: return@runCatching null
                node.optString("video_url").takeIf { it.isNotBlank() }
                    ?: node.optJSONObject("edge_sidecar_to_children")
                        ?.optJSONArray("edges")?.optJSONObject(0)
                        ?.optJSONObject("node")?.optString("video_url")
            }.getOrNull()

            if (!media.isNullOrBlank()) return instagramResolved(media)
        }
        return null
    }

    private fun instagramResolved(url: String) = Resolved(
        url,
        mapOf(
            "User-Agent" to UA,
            "Referer" to "https://www.instagram.com/",
            "Accept" to "*/*",
            "Range" to "bytes=0-"
        )
    )

    /** Small GET helper that respects the remaining budget. */
    private fun httpGet(url: String, budgetMs: Int, headers: Map<String, String>): String? =
        runCatching {
            val connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = budgetMs.coerceIn(4_000, 12_000)
                headers.forEach { (key, value) -> setRequestProperty(key, value) }
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()

    private data class Page(val url: String, val html: String, val cookies: String)

    private fun fetch(pageUrl: String, budgetMs: Int): Page? {
        var current = pageUrl
        var cookies = ""
        // Short links (vm.tiktok.com, pin.it) bounce before the real page.
        repeat(4) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 6_000
                readTimeout = budgetMs.coerceIn(4_000, 10_000)
                instanceFollowRedirects = false
                setRequestProperty("User-Agent", UA)
                setRequestProperty("Accept", "text/html,application/xhtml+xml")
                setRequestProperty("Accept-Language", "en-US,en;q=0.9")
                if (cookies.isNotBlank()) setRequestProperty("Cookie", cookies)
            }
            try {
                val code = connection.responseCode
                cookies = mergeCookies(cookies, connection.headerFields["Set-Cookie"])

                if (code in 300..399) {
                    val location = connection.getHeaderField("Location") ?: return null
                    current = URL(URL(current), location).toString()
                    return@repeat
                }
                if (code != HttpURLConnection.HTTP_OK) return null
                val body = connection.inputStream.bufferedReader().use { it.readText() }
                return Page(current, body, cookies)
            } catch (e: Exception) {
                return null
            } finally {
                connection.disconnect()
            }
        }
        return null
    }

    private fun mergeCookies(existing: String, setCookie: List<String>?): String {
        if (setCookie.isNullOrEmpty()) return existing
        val jar = LinkedHashMap<String, String>()
        existing.split(";").forEach { part ->
            val pair = part.trim().split("=", limit = 2)
            if (pair.size == 2) jar[pair[0]] = pair[1]
        }
        setCookie.forEach { header ->
            val pair = header.substringBefore(";").trim().split("=", limit = 2)
            if (pair.size == 2 && pair[0].isNotBlank()) jar[pair[0]] = pair[1]
        }
        return jar.entries.joinToString("; ") { "${it.key}=${it.value}" }
    }

    // Ordered best-first: platform-specific keys, then the standard meta tags.
    private val PATTERNS = listOf(
        Regex("\"playAddr\"\\s*:\\s*\"([^\"]{20,})\""),
        Regex("\"video_url\"\\s*:\\s*\"([^\"]{20,})\""),
        Regex("\"video_versions\"\\s*:\\s*\\[.{0,200}?\"url\"\\s*:\\s*\"([^\"]{20,})\"", RegexOption.DOT_MATCHES_ALL),
        Regex(
            "<meta[^>]+property=[\"']og:video(?::secure_url|:url)?[\"'][^>]+content=[\"']([^\"']{20,})[\"']",
            RegexOption.IGNORE_CASE
        ),
        Regex(
            "<meta[^>]+content=[\"']([^\"']{20,})[\"'][^>]+property=[\"']og:video(?::secure_url|:url)?[\"']",
            RegexOption.IGNORE_CASE
        ),
        Regex("\"contentUrl\"\\s*:\\s*\"([^\"]{20,}\\.mp4[^\"]*)\""),
        Regex("<video[^>]+src=[\"']([^\"']{20,})[\"']", RegexOption.IGNORE_CASE),
        Regex("\"downloadAddr\"\\s*:\\s*\"([^\"]{20,})\""),
        // Last resort: a direct mp4 on a known media CDN (Pinterest, Instagram).
        Regex("https?://[^\"'\\s\\\\]*(?:pinimg|cdninstagram|fbcdn)[^\"'\\s\\\\]*\\.mp4[^\"'\\s\\\\]*")
    )

    private fun findMediaUrl(html: String): String? {
        // Instagram's embed page carries its JSON inside a string, so the source
        // literally reads \"video_url\":\"https:\/\/… — patterns looking for a
        // plain quote never match it. Try the page as-is, then unescaped.
        return matchIn(html) ?: matchIn(
            html.replace("\\\"", "\"").replace("\\\\/", "/").replace("\\/", "/")
        )
    }

    private fun matchIn(html: String): String? {
        for (pattern in PATTERNS) {
            val match = pattern.find(html) ?: continue
            // Most patterns capture a group; the bare-URL one matches the whole thing.
            val raw = match.groupValues.getOrNull(1)?.takeIf { it.isNotBlank() } ?: match.value
            val url = unescape(raw)
            if (url.startsWith("http")) return url
        }
        return null
    }

    private fun unescape(value: String): String = value
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\/", "/")
        .replace("&amp;", "&")
        .replace("\\\\", "\\")
}
