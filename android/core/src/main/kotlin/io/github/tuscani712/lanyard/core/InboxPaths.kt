package io.github.tuscani712.lanyard.core

/**
 * The one place the default receive folder is named, shared by the Android
 * destination implementations, the Settings label and the tests.
 *
 * Received files land in `Download/LANyard` (a subfolder of the public
 * Downloads folder) unless the person chose a folder in Settings. Keeping the
 * name and the full relative path here means the row, the log and the Settings
 * screen can all show the *real* destination instead of repeating a literal.
 */
object InboxPaths {
    /** The folder LANyard creates inside Downloads. */
    const val FOLDER_NAME = "LANyard"

    /** The MediaStore/Settings label for the default folder. */
    const val DEFAULT_LABEL = "Download/$FOLDER_NAME"

    /**
     * The public relative path a received file is placed at, given its
     * folder-relative [subPath] from the sender. An empty sub-path is the
     * folder itself.
     */
    fun defaultRelative(subPath: String = ""): String {
        val sub = subPath.trim().trim('/')
        return if (sub.isEmpty()) DEFAULT_LABEL else "$DEFAULT_LABEL/$sub"
    }
}
