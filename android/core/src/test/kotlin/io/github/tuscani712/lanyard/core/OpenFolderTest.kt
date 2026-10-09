package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The stored-folder to (uri, mime) mapping behind "Open folder". The Android
 * Intent is built elsewhere; this only has to pick the right URI form and MIME
 * for a SAF tree vs a filesystem path, and refuse to invent one for a label.
 */
class OpenFolderTest {

    @Test
    fun aSafTreeUsesItsOwnUriAndTheDocumentDirectoryMime() {
        val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload%2FLANyard"
        assertEquals(
            FolderTarget(tree, OpenFolder.MIME_TREE),
            OpenFolder.targetFor(tree),
        )
    }

    @Test
    fun anAbsolutePathBecomesAFileUriWithTheFolderMime() {
        assertEquals(
            FolderTarget("file:///storage/emulated/0/Download/LANyard", OpenFolder.MIME_FOLDER),
            OpenFolder.targetFor("/storage/emulated/0/Download/LANyard"),
        )
    }

    @Test
    fun anExistingFileUriIsKeptWithTheFolderMime() {
        assertEquals(
            FolderTarget("file:///sdcard/Download/LANyard", OpenFolder.MIME_FOLDER),
            OpenFolder.targetFor("file:///sdcard/Download/LANyard"),
        )
    }

    @Test
    fun aBareLabelOrBlankHasNothingToOpen() {
        assertNull(OpenFolder.targetFor("Download/LANyard"), "a relative label is not openable")
        assertNull(OpenFolder.targetFor("MyFolder (chosen folder)"))
        assertNull(OpenFolder.targetFor(""))
        assertNull(OpenFolder.targetFor("   "))
        assertNull(OpenFolder.targetFor(null))
    }
}
