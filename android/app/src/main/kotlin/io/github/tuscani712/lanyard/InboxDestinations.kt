package io.github.tuscani712.lanyard

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.github.tuscani712.lanyard.core.PushDestination
import java.io.File
import java.io.IOException

/**
 * A push destination used when the person has not chosen a folder: a LANyard
 * folder next to Downloads, created on first use. On Android 10+ this goes
 * through MediaStore (no permission, the same folder the Files app shows); on
 * Android 9 and below it falls back to the app's own external files directory,
 * which also needs no permission. The system folder picker in Settings is an
 * override that, when set, is used instead.
 */
fun defaultInboxDestination(context: Context): PushDestination =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        MediaStoreInboxDestination(context.applicationContext)
    } else {
        AppExternalInboxDestination(context.applicationContext)
    }

/** The folder name shown next to Downloads. */
const val INBOX_FOLDER_NAME = "LANyard"

/** MediaStore Downloads/LANyard on Android 10+. */
@RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreInboxDestination(private val context: Context) : PushDestination {
    override fun place(relPath: String, spool: File, size: Long): String {
        val sub = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/').ifEmpty { "received-file" }
        val relative = "Download/$INBOX_FOLDER_NAME" + if (sub.isEmpty()) "" else "/$sub"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/octet-stream")
            put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: throw IOException("Could not create the file in Downloads/$INBOX_FOLDER_NAME.")
        try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { out ->
                spool.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Could not write the file in Downloads/$INBOX_FOLDER_NAME.")
            values.clear()
            values.put(MediaStore.MediaColumns.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
        } catch (e: Exception) {
            runCatching { context.contentResolver.delete(uri, null, null) }
            throw e
        }
        return displayName(uri) ?: name
    }

    /** MediaStore may rename on a collision; report what it actually saved. */
    private fun displayName(uri: android.net.Uri): String? =
        runCatching {
            context.contentResolver.query(uri, arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}

/** App-specific external files dir on Android 9 and below (no permission). */
private class AppExternalInboxDestination(private val context: Context) : PushDestination {
    override fun place(relPath: String, spool: File, size: Long): String {
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), INBOX_FOLDER_NAME)
        val dir = relPath.substringBeforeLast('/', "").let { if (it.isEmpty()) root else File(root, it) }
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create the destination folder.")
        val name = relPath.substringAfterLast('/').ifEmpty { "received-file" }
        val target = uniqueFile(dir, name)
        spool.inputStream().use { ins -> target.outputStream().use { ins.copyTo(it, 256 * 1024) } }
        return target.name
    }

    private fun uniqueFile(dir: File, name: String): File {
        var candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (candidate.exists()) {
            candidate = File(dir, "$base ($n)$ext")
            n++
        }
        return candidate
    }
}
