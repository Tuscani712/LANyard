package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The default receive folder is one constant, not a literal repeated per file. */
class InboxPathsTest {

    @Test
    fun theDefaultFolderIsDownloadSlashLanyard() {
        assertEquals("Download/LANyard", InboxPaths.defaultRelative())
        assertEquals(InboxPaths.DEFAULT_LABEL, InboxPaths.defaultRelative(""))
    }

    @Test
    fun aSendersSubPathIsAppendedUnderTheFolder() {
        assertEquals("Download/LANyard/album", InboxPaths.defaultRelative("album"))
        assertEquals("Download/LANyard/album/sub", InboxPaths.defaultRelative("album/sub"))
        assertEquals("Download/LANyard/album", InboxPaths.defaultRelative("/album/"))
    }
}
