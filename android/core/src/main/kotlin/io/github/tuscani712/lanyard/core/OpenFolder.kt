package io.github.tuscani712.lanyard.core

/**
 * A folder resolved to something the OS can open: the URI string to view and
 * the MIME type to view it as. The Android `Intent` itself is built in the app
 * layer, so this mapping stays pure and unit-testable.
 */
data class FolderTarget(val uri: String, val mimeType: String)

/**
 * Maps a stored destination folder to the `(uri, mimeType)` an "Open folder"
 * action should use. The stored value is whatever a [PushDestination] reported
 * as its location: a SAF `content://` tree URI, an absolute filesystem path, or
 * a `file://` URI.
 *
 * A `content://` location is a SAF/MediaStore tree, so it is viewed with the
 * document-directory MIME; anything filesystem-backed becomes a `file://` URI
 * viewed with the generic folder MIME. A blank value or a bare human label (for
 * example "Downloads") has no location to open, so it maps to null and the UI
 * leaves the action off rather than offering a dead button.
 */
object OpenFolder {
    /** The MIME type for a SAF/MediaStore directory tree. */
    const val MIME_TREE = "vnd.android.document/directory"

    /** The MIME type for a filesystem folder. */
    const val MIME_FOLDER = "resource/folder"

    fun targetFor(folder: String?): FolderTarget? {
        val stored = folder?.trim().orEmpty()
        if (stored.isEmpty()) return null
        return when {
            stored.startsWith("content://", ignoreCase = true) -> FolderTarget(stored, MIME_TREE)
            stored.startsWith("file://", ignoreCase = true) -> FolderTarget(stored, MIME_FOLDER)
            stored.startsWith("/") -> FolderTarget("file://$stored", MIME_FOLDER)
            else -> null
        }
    }
}
