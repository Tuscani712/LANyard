package io.github.tuscani712.lanyard

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import io.github.tuscani712.lanyard.core.FolderNames
import io.github.tuscani712.lanyard.core.PushDestination
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Places a received, SHA-verified file into the user's chosen download folder
 * (a SAF tree). Relative sub-folders in the push are recreated, and a name
 * collision becomes "name (1).ext". Throws with a readable message when the
 * folder is unset, revoked or unwritable.
 *
 * The tree's directories are resolved once and cached (keyed by the parent's
 * URI plus the segment), and each directory's existing names are listed once and
 * cached in a [FolderNames], so placing N files into one growing folder costs one
 * listing rather than a `findFile`/`listFiles` scan per file (which was O(N²)).
 */
class SafInboxDestination(
    private val context: Context,
    private val treeUri: () -> Uri?,
) : PushDestination {
    private val dirCache = ConcurrentHashMap<String, DocumentFile>()
    private val namesCache = ConcurrentHashMap<String, FolderNames>()

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
        // Reserve a free name in-memory instead of a findFile scan per candidate.
        val allocated = namesFor(dir).allocate(name)
        val target = dir.createFile(MIME, allocated)
            ?: throw IOException("Could not create the file.")
        try {
            context.contentResolver.openOutputStream(target.uri, "wt")?.use { out ->
                spool.inputStream().use { it.copyTo(out, 256 * 1024) }
            } ?: throw IOException("Could not write the file.")
        } catch (e: Exception) {
            // Never leave a partial file under its final, normal-looking name.
            runCatching { target.delete() }
            throw e
        }
        return target.name ?: allocated
    }

    private fun ensureDirs(root: DocumentFile, path: String): DocumentFile? {
        var current = root
        for (segment in path.split('/')) {
            if (segment.isEmpty()) continue
            val key = current.uri.toString() + "/" + segment
            val cached = dirCache[key]
            if (cached != null) {
                current = cached
                continue
            }
            val next = current.findFile(segment) ?: current.createDirectory(segment) ?: return null
            if (!next.isDirectory) return null
            dirCache[key] = next
            current = next
        }
        return current
    }

    /** The names already in [dir], listed once and cached. */
    private fun namesFor(dir: DocumentFile): FolderNames = namesCache.getOrPut(dir.uri.toString()) {
        FolderNames(dir.listFiles().mapNotNull { it.name })
    }

    private companion object {
        const val MIME = "application/octet-stream"
    }
}
