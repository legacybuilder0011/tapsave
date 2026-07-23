package com.plutoforce.tapsave

import android.content.Context
import java.util.regex.Pattern

/** Small shared-preferences wrapper plus a URL helper. */
object Prefs {

    private const val NAME = "tapsave"
    private const val KEY_BACKEND = "backend"
    private const val KEY_QUALITY = "quality"
    private const val KEY_AUDIO = "audio_only"

    private val URL_PATTERN: Pattern =
        Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE)

    private fun prefs(context: Context) =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)

    fun backend(context: Context): String =
        prefs(context).getString(KEY_BACKEND, "").orEmpty()

    fun setBackend(context: Context, value: String) {
        prefs(context).edit().putString(KEY_BACKEND, value.trim()).apply()
    }

    /** "high", "medium" or "low". */
    fun quality(context: Context): String =
        prefs(context).getString(KEY_QUALITY, "high").orEmpty().ifBlank { "high" }

    fun setQuality(context: Context, value: String) {
        prefs(context).edit().putString(KEY_QUALITY, value).apply()
    }

    fun audioOnly(context: Context): Boolean =
        prefs(context).getBoolean(KEY_AUDIO, false)

    fun setAudioOnly(context: Context, value: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUDIO, value).apply()
    }

    /** Returns the first http(s) URL found in [text], or null. */
    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val matcher = URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }
}
