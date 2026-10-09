package io.github.tuscani712.lanyard

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.tuscani712.lanyard.core.PushDestination
import java.io.File
import java.io.IOException

/**
 * Places a received, SHA-verified file into the user's chosen download folder
 * (a SAF tree). Relative sub-folders in the push are recreated, and a name
 * collision becomes "name (1).ext". Throws with a readable message when the
 * folder is unset, revoked or unwritable.
 */
class SafInboxDestination(
    private val context: Context,
    private val treeUri: () -> Uri?,
) : PushDestination {
    /** The chosen folder's display name, for the Transfers row and the log. */
    override fun folder(): String =
        treeUri()?.let { runCatching { DocumentFile.fromTreeUri(context, it)?.name }.getOrNull() }
            ?.takeIf { it.isNotBlank() }
            ?.let { "$it (chosen folder)" }
            ?: "the chosen folder"

    /** The chosen folder's SAF tree URI, so the OS can open it (read permission held). */
    override fun folderLocation(): String = treeUri()?.toString().orEmpty()

    override fun place(relPath: String, spool: File, size: Long): String {
        val uri = treeUri() ?: throw IOException("Choose a download folder in Settings first.")
        val root = DocumentFile.fromTreeUri(context, uri)
            ?: throw IOException("The download folder is unavailable.")
        if (!root.canWrite()) throw IOException("The download folder is not writable.")
        val dir = ensureDirs(root, relPath.substringBeforeLast('/', ""))
            ?: throw IOException("Could not create the destination folder.")
        val name = relPath.substringAfterLast('/').ifEmpty { "received-file" }
        val target = if (dir.findFile(name) == null) {
            dir.createFile(MIME, name)
        } else {
            dir.createFile(MIME, uniqueName(dir, name))
        } ?: throw IOException("Could not create the file.")
        try {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                spool.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Could not write the file.")
        } catch (e: Exception) {
            // Never leave a partial file under its final, normal-looking name.
            runCatching { target.delete() }
            throw e
        }
        return target.name ?: name
    }

    private fun ensureDirs(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            current = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
            if (!current.isDirectory) return null
        }
        return current
    }

    private fun uniqueName(dir: DocumentFile, name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var n = 1
        while (true) {
            val candidate = "$base ($n)$ext"
            if (dir.findFile(candidate) == null) return candidate
            n++
        }
    }

    private companion object {
        const val MIME = "application/octet-stream"
    }
}
