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

    /** Returns a directly downloadable link, or null if the page didn't yield one. */
    fun resolve(pageUrl: String): Resolved? {
        // Instagram's own media API answers logged-out requests from a phone IP,
        // and is far more reliable than scraping the page.
        if (isInstagram(pageUrl)) {
            instagramViaApi(pageUrl)?.let { return it }
        }
        for (candidate in candidatePages(pageUrl)) {
            val page = fetch(candidate) ?: continue
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
        return listOf(
            "https://www.instagram.com/$kind/$code/embed/captioned/",
            "https://www.instagram.com/$kind/$code/embed/",
            pageUrl
        )
    }

    // --- Instagram media API ----------------------------------------------

    // Instagram's public web app id. Sending it makes the media endpoint answer
    // logged-out requests for public posts.
    private const val IG_APP_ID = "936619743392459"
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

    private fun instagramViaApi(pageUrl: String): Resolved? {
        val shortcode = Regex("/(?:reels?|p|tv)/([A-Za-z0-9_-]+)").find(pageUrl)
            ?.groupValues?.get(1) ?: return null
        val mediaId = shortcodeToMediaId(shortcode) ?: return null

        val json = runCatching {
            val connection = (
                URL("https://i.instagram.com/api/v1/media/$mediaId/info/")
                    .openConnection() as HttpURLConnection
                ).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
                setRequestProperty("User-Agent", UA)
                setRequestProperty("x-ig-app-id", IG_APP_ID)
                setRequestProperty("Accept", "*/*")
            }
            try {
                if (connection.responseCode != HttpURLConnection.HTTP_OK) return@runCatching null
                connection.inputStream.bufferedReader().use { it.readText() }
            } finally {
                connection.disconnect()
            }
        }.getOrNull() ?: return null

        val media = runCatching {
            val items = org.json.JSONObject(json).optJSONArray("items") ?: return@runCatching null
            val item = items.optJSONObject(0) ?: return@runCatching null
            // A carousel keeps its videos one level down.
            val holder = item.optJSONArray("carousel_media")?.optJSONObject(0) ?: item
            holder.optJSONArray("video_versions")?.optJSONObject(0)?.optString("url")
        }.getOrNull()

        if (media.isNullOrBlank()) return null
        return Resolved(
            media,
            mapOf(
                "User-Agent" to UA,
                "Referer" to "https://www.instagram.com/",
                "Accept" to "*/*",
                "Range" to "bytes=0-"
            )
        )
    }

    private data class Page(val url: String, val html: String, val cookies: String)

    private fun fetch(pageUrl: String): Page? {
        var current = pageUrl
        var cookies = ""
        // Short links (vm.tiktok.com, pin.it) bounce before the real page.
        repeat(4) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 8_000
                readTimeout = 12_000
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
        Regex("\"video_versions\"[^\\[]*\\[\\s*\\{[^}]*?\"url\"\\s*:\\s*\"([^\"]{20,})\""),
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
