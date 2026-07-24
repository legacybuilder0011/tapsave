package com.plutoforce.tapsave

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File

/**
 * Finds WhatsApp "status" photos and videos on the phone (the 24-hour ones your
 * contacts post) and copies them into the gallery so they're kept before they
 * expire. There's no server or link involved — statuses live in a local folder
 * that WhatsApp writes as you view them.
 */
object StatusSaver {

    data class Status(val file: File, val isVideo: Boolean)

    /**
     * Every place WhatsApp / WhatsApp Business is known to keep statuses, newest
     * layout first. On Android 11+ WhatsApp moved them under Android/media; older
     * installs kept them at the storage root.
     */
    private fun candidateDirs(): List<File> {
        val root = Environment.getExternalStorageDirectory()
        return listOf(
            "Android/media/com.whatsapp/WhatsApp/Media/.Statuses",
            "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses",
            "WhatsApp/Media/.Statuses",
            "WhatsApp Business/Media/.Statuses"
        ).map { File(root, it) }
    }

    /** True if we're allowed to read the shared storage the statuses live in. */
    fun hasAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            context.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }

    /** True if WhatsApp appears installed but no statuses have been viewed yet. */
    fun statusFolderExists(): Boolean = candidateDirs().any { it.isDirectory }

    /** All current statuses, newest first. Empty if none or no access. */
    fun list(): List<Status> {
        val out = ArrayList<Status>()
        for (dir in candidateDirs()) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val name = f.name.lowercase()
                val isVideo = name.endsWith(".mp4")
                val isImage = name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".webp")
                if (isVideo || isImage) out.add(Status(f, isVideo))
            }
        }
        return out.sortedByDescending { it.file.lastModified() }
    }

    /** Copies one status into the gallery (Pictures/TapSave or Movies/TapSave). */
    fun save(context: Context, status: Status): Boolean {
        val resolver = context.contentResolver
        val stamp = System.currentTimeMillis()
        val ext = status.file.extension.ifBlank { if (status.isVideo) "mp4" else "jpg" }
        val name = "WA_Status_$stamp.$ext"

        val collection = if (status.isVideo) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        } else {
            MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        }
        val relativePath = if (status.isVideo) {
            Environment.DIRECTORY_MOVIES + "/TapSave"
        } else {
            Environment.DIRECTORY_PICTURES + "/TapSave"
        }
        val mime = when {
            status.isVideo -> "video/mp4"
            ext.equals("png", true) -> "image/png"
            ext.equals("webp", true) -> "image/webp"
            else -> "image/jpeg"
        }

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }

        val uri = resolver.insert(collection, values) ?: return false
        return try {
            resolver.openOutputStream(uri).use { output ->
                if (output == null) return false
                status.file.inputStream().use { input -> input.copyTo(output, 64 * 1024) }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
