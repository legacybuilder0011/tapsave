package com.plutoforce.tapsave

import java.net.HttpURLConnection
import java.net.URL

/**
 * Finds a TikTok video's direct file URL on the phone itself.
 *
 * TikTok blocks datacenter IPs, so asking a cloud server to fetch the video gets
 * "Video not available" even for perfectly public posts. The phone has an
 * ordinary mobile/home IP that TikTok serves normally, so we load the page here
 * and read the direct link out of the JSON TikTok embeds in it. This also makes
 * downloads fast, because nothing is relayed through a server.
 *
 * We use "playAddr" (the stream the app itself plays), which has no watermark —
 * "downloadAddr" is the watermarked one.
 */
object TikTokExtractor {

    data class Resolved(val url: String, val headers: Map<String, String>)

    private const val UA =
        "Mozilla/5.0 (iPhone; CPU iPhone OS 17_5 like Mac OS X) AppleWebKit/605.1.15 " +
            "(KHTML, like Gecko) Version/17.5 Mobile/15E148 Safari/604.1"

    fun isTikTok(url: String): Boolean = url.contains("tiktok.com", ignoreCase = true)

    /** Returns a directly downloadable link, or null if the page didn't yield one. */
    fun resolve(pageUrl: String): Resolved? {
        val (finalUrl, html, cookies) = fetch(pageUrl) ?: return null
        val playAddr = findPlayAddr(html) ?: return null

        val headers = HashMap<String, String>()
        headers["User-Agent"] = UA
        headers["Referer"] = finalUrl
        headers["Accept"] = "*/*"
        headers["Accept-Language"] = "en-US,en;q=0.9"
        headers["Range"] = "bytes=0-"
        if (cookies.isNotBlank()) headers["Cookie"] = cookies
        return Resolved(playAddr, headers)
    }

    private data class Page(val url: String, val html: String, val cookies: String)

    private fun fetch(pageUrl: String): Page? {
        var current = pageUrl
        var cookies = ""
        // Short links (vm.tiktok.com) bounce a few times before the real page.
        repeat(5) {
            val connection = (URL(current).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 20_000
                readTimeout = 30_000
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

    /** Pulls the first playAddr out of the JSON TikTok embeds in the page. */
    private fun findPlayAddr(html: String): String? {
        val match = Regex("\"playAddr\"\\s*:\\s*\"([^\"]{20,})\"").find(html)
            ?: Regex("\"downloadAddr\"\\s*:\\s*\"([^\"]{20,})\"").find(html)
            ?: return null
        val raw = unescape(match.groupValues[1])
        return if (raw.startsWith("http")) raw else null
    }

    private fun unescape(value: String): String = value
        .replace("\\u002F", "/", ignoreCase = true)
        .replace("\\u0026", "&", ignoreCase = true)
        .replace("\\/", "/")
        .replace("\\\\", "\\")
}
