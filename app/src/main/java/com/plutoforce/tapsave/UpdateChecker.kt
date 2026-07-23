package com.plutoforce.tapsave

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub for a newer TapSave build and installs it over the top.
 * The release publishes a small version.json next to the APK; we compare its
 * versionCode with the installed one. Networking to GitHub only.
 */
object UpdateChecker {

    private const val VERSION_URL =
        "https://github.com/legacybuilder0011/tapsave/releases/download/latest/version.json"

    data class Info(val versionCode: Int, val versionName: String, val apkUrl: String)

    fun currentVersionCode(context: Context): Int {
        return try {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                info.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION") info.versionCode
            }
        } catch (e: PackageManager.NameNotFoundException) {
            0
        }
    }

    fun fetchLatest(): Info? {
        return try {
            val conn = (URL(VERSION_URL).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 15_000
                instanceFollowRedirects = true
            }
            conn.use {
                if (it.responseCode != HttpURLConnection.HTTP_OK) return null
                val body = it.inputStream.bufferedReader().use { r -> r.readText() }
                val json = JSONObject(body)
                Info(
                    versionCode = json.getInt("versionCode"),
                    versionName = json.optString("versionName", ""),
                    apkUrl = json.getString("apk")
                )
            }
        } catch (e: Exception) {
            null
        }
    }

    fun downloadApk(context: Context, apkUrl: String): File? {
        return try {
            val dir = File(context.getExternalFilesDir(null), "updates").apply { mkdirs() }
            val file = File(dir, "TapSave-update.apk")
            val conn = (URL(apkUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 30_000
                readTimeout = 120_000
                instanceFollowRedirects = true
            }
            conn.use {
                if (it.responseCode != HttpURLConnection.HTTP_OK) return null
                it.inputStream.use { input ->
                    file.outputStream().use { output -> input.copyTo(output, 64 * 1024) }
                }
            }
            file
        } catch (e: Exception) {
            null
        }
    }

    fun installApk(context: Context, file: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }

    private inline fun <T> HttpURLConnection.use(block: (HttpURLConnection) -> T): T {
        try {
            return block(this)
        } finally {
            disconnect()
        }
    }
}
