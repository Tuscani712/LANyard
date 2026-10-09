package io.github.tuscani712.lanyard

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.annotation.RequiresApi
import io.github.tuscani712.lanyard.core.FolderNames
import io.github.tuscani712.lanyard.core.InboxPaths
import io.github.tuscani712.lanyard.core.PushDestination
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

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
const val INBOX_FOLDER_NAME = InboxPaths.FOLDER_NAME

/** The DocumentsContract authority for the device's primary shared storage. */
private const val EXTERNAL_STORAGE_DOCUMENTS = "com.android.externalstorage.documents"

/**
 * A `content://` tree URI for a path relative to primary shared storage, so the
 * folder is openable on every supported API without exposing a `file://` URI.
 * Falls back to null when the document id cannot be encoded.
 */
internal fun primaryTreeUri(relativePath: String): String? = runCatching {
    DocumentsContract.buildTreeDocumentUri(EXTERNAL_STORAGE_DOCUMENTS, "primary:$relativePath").toString()
}.getOrNull()

/** MediaStore Downloads/LANyard on Android 10+. */
@RequiresApi(Build.VERSION_CODES.Q)
private class MediaStoreInboxDestination(private val context: Context) : PushDestination {
    // Names already present per relative folder, seeded once from MediaStore and
    // reserved in-memory afterwards, so a growing folder is not re-queried per
    // file and is not O(N²).
    private val folderNames = ConcurrentHashMap<String, FolderNames>()

    override fun folder(): String = InboxPaths.DEFAULT_LABEL

    override fun folderLocation(): String = primaryTreeUri(InboxPaths.defaultRelative()).orEmpty()

    override fun place(relPath: String, spool: File, size: Long): String {
        val sub = relPath.substringBeforeLast('/', "")
        val name = relPath.substringAfterLast('/').ifEmpty { "received-file" }
        val relative = InboxPaths.defaultRelative(sub)
        // Allocate a name that is unique among the names we know are present, so
        // MediaStore does not have to rename it and we can return the name we
        // asked for instead of querying the inserted URI afterwards.
        val allocated = namesFor(relative).allocate(name)

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, allocated)
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
        return allocated
    }

    /**
     * The known names in [relative], queried once from MediaStore and then
     * cached. The folder may be stored with or without a trailing slash, so both
     * forms are matched.
     */
    private fun namesFor(relative: String): FolderNames = folderNames.getOrPut(relative) {
        val names = HashSet<String>()
        val collection = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val selection = "${MediaStore.MediaColumns.RELATIVE_PATH} IN (?, ?)"
        runCatching {
            context.contentResolver.query(
                collection,
                arrayOf(MediaStore.MediaColumns.DISPLAY_NAME),
                selection,
                arrayOf(relative, "$relative/"),
                null,
            )?.use { c ->
                while (c.moveToNext()) c.getString(0)?.let { names.add(it) }
            }
        }
        FolderNames(names)
    }
}

/** App-specific external files dir on Android 9 and below (no permission). */
private class AppExternalInboxDestination(private val context: Context) : PushDestination {
    // One listing per directory, cached, so placing N files is not N folder scans.
    private val folderNames = ConcurrentHashMap<String, FolderNames>()

    override fun folder(): String = InboxPaths.DEFAULT_LABEL

    override fun folderLocation(): String {
        val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), INBOX_FOLDER_NAME)
        val relative = dir.absolutePath.substringAfter("/storage/emulated/0/", dir.absolutePath)
        return primaryTreeUri(relative) ?: dir.absolutePath
    }

    override fun place(relPath: String, spool: File, size: Long): String {
        val root = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), INBOX_FOLDER_NAME)
        val dir = relPath.substringBeforeLast('/', "").let { if (it.isEmpty()) root else File(root, it) }
        if (!dir.mkdirs() && !dir.isDirectory) throw IOException("Could not create the destination folder.")
        val name = relPath.substringAfterLast('/').ifEmpty { "received-file" }
        val target = File(dir, namesFor(dir).allocate(name))
        spool.inputStream().use { ins -> target.outputStream().use { ins.copyTo(it, 256 * 1024) } }
        return target.name
    }

    /** The names already in [dir], listed once and cached. */
    private fun namesFor(dir: File): FolderNames = folderNames.getOrPut(dir.absolutePath) {
        FolderNames(dir.list()?.toList() ?: emptyList())
    }
}
