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

    /**
     * All current statuses, newest first, with duplicates removed. WhatsApp and
     * WhatsApp Business can hold the same status, and the same photo often lands
     * twice, so we key on name and on size+timestamp.
     */
    fun list(): List<Status> {
        val out = ArrayList<Status>()
        val seenNames = HashSet<String>()
        val seenContent = HashSet<String>()
        for (dir in candidateDirs()) {
            val files = dir.listFiles() ?: continue
            for (f in files) {
                if (!f.isFile) continue
                val name = f.name.lowercase()
                val isVideo = name.endsWith(".mp4")
                val isImage = name.endsWith(".jpg") || name.endsWith(".jpeg") ||
                    name.endsWith(".png") || name.endsWith(".webp")
                if (!isVideo && !isImage) continue
                val contentKey = "${f.length()}_${f.lastModified()}"
                if (!seenNames.add(name) || !seenContent.add(contentKey)) continue
                out.add(Status(f, isVideo))
            }
        }
        return out.sortedByDescending { it.file.lastModified() }
    }

    /** The most recently written status. */
    fun newest(): Status? = list().firstOrNull()

    /**
     * The status being viewed, if we can genuinely tell.
     *
     * WhatsApp gives apps no way to ask what's on screen, and it pre-downloads
     * statuses in batches before you open them — so file timestamps do NOT
     * identify the one you're looking at. We only skip the picker in the one
     * unambiguous case: a single status exists that was written moments ago.
     * Any other time the caller must ask the user.
     */
    fun confidentlyViewed(withinMs: Long = 8_000L): Status? {
        val now = System.currentTimeMillis()
        val all = list()
        val recent = all.filter { now - it.file.lastModified() <= withinMs }
        return if (recent.size == 1 && all.size == 1) recent.first() else null
    }

    // --- Remembering what's already been saved ----------------------------

    private const val SAVED_PREFS = "tapsave_saved_statuses"
    private const val SAVED_KEY = "keys"

    private fun key(status: Status) = "${status.file.name}_${status.file.length()}"

    fun isAlreadySaved(context: Context, status: Status): Boolean =
        context.getSharedPreferences(SAVED_PREFS, Context.MODE_PRIVATE)
            .getStringSet(SAVED_KEY, emptySet())
            .orEmpty()
            .contains(key(status))

    private fun markSaved(context: Context, status: Status) {
        val prefs = context.getSharedPreferences(SAVED_PREFS, Context.MODE_PRIVATE)
        val current = prefs.getStringSet(SAVED_KEY, emptySet()).orEmpty().toMutableSet()
        current.add(key(status))
        // Statuses expire in a day, so this set never needs to grow forever.
        while (current.size > 400) current.remove(current.first())
        prefs.edit().putStringSet(SAVED_KEY, current).apply()
    }

    /** Copies one status into the gallery (Pictures/TapSave or Movies/TapSave). */
    fun save(context: Context, status: Status, onProgress: (Int) -> Unit = {}): Boolean {
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
            val total = status.file.length()
            resolver.openOutputStream(uri).use { output ->
                if (output == null) return false
                status.file.inputStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPct = -1
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        copied += n
                        if (total > 0) {
                            val pct = ((copied * 100) / total).toInt()
                            if (pct != lastPct) {
                                lastPct = pct
                                onProgress(pct)
                            }
                        }
                    }
                }
            }
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            onProgress(100)
            markSaved(context, status)
            true
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            false
        }
    }
}
