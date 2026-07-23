package com.plutoforce.tapsave

import android.content.Context
import java.util.regex.Pattern

/** Small shared-preferences wrapper plus a URL helper. */
object Prefs {

    private const val NAME = "tapsave"
    private const val KEY_BACKEND = "backend"

    private val URL_PATTERN: Pattern =
        Pattern.compile("https?://[^\\s\"'<>]+", Pattern.CASE_INSENSITIVE)

    fun backend(context: Context): String =
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE).getString(KEY_BACKEND, "").orEmpty()

    fun setBackend(context: Context, value: String) {
        context.getSharedPreferences(NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_BACKEND, value.trim())
            .apply()
    }

    /** Returns the first http(s) URL found in [text], or null. */
    fun firstUrl(text: String?): String? {
        if (text.isNullOrBlank()) return null
        val matcher = URL_PATTERN.matcher(text)
        return if (matcher.find()) matcher.group() else null
    }
}
