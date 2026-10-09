package io.github.tuscani712.lanyard.core

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

class SwitchingDestinationTest {
    private class Fake(val label: String, val location: String) : PushDestination {
        override fun place(relPath: String, spool: File, size: Long) = "$label/$relPath"
        override fun folder() = label
        override fun folderLocation() = location
    }

    @Test
    fun forwardsFolderAndLocationOfTheChosenDestination() {
        var useSaf = false
        val saf = Fake("Chosen", "content://tree/saf")
        val default = Fake("Downloads/LANyard", "content://tree/default")
        val d = SwitchingDestination { if (useSaf) saf else default }
        assertEquals("Downloads/LANyard", d.folder())
        assertEquals("content://tree/default", d.folderLocation())
        useSaf = true
        assertEquals("Chosen", d.folder())
        assertEquals("content://tree/saf", d.folderLocation())
        assertEquals("Chosen/a.txt", d.place("a.txt", File("x"), 1))
    }
}
