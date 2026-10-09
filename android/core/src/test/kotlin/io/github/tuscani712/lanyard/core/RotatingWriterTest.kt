package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Path

class RotatingWriterTest {
    private fun lines(n: Int) = (0 until n).map { "line-%02d".format(it) }

    @Test
    fun rotatesIntoBackupsOnceTheFileWouldExceedTheCap(@TempDir dir: Path) {
        val file = dir.resolve("lanyard.log").toFile()
        val writer = RotatingWriter(file, maxBytes = 32, maxRotations = 3)

        lines(13).forEach { writer.append(it) }

        assertTrue(File(file.path + ".1").exists())
        assertTrue(File(file.path + ".2").exists())
        assertTrue(File(file.path + ".3").exists())
        // Never more than maxRotations backups.
        assertFalse(File(file.path + ".4").exists())
        // The live file is a fresh, small segment, not the whole history.
        assertTrue(file.length() <= 32)
    }

    @Test
    fun dropsTheOldestRotationOnceTheCapIsReached(@TempDir dir: Path) {
        val file = dir.resolve("lanyard.log").toFile()
        val writer = RotatingWriter(file, maxBytes = 32, maxRotations = 3)

        lines(17).forEach { writer.append(it) }

        val history = writer.readAll()
        // line-00/01/02/03 were in the segment dropped by the fourth rotation.
        assertFalse(history.contains("line-00"))
        assertFalse(history.contains("line-03"))
        assertTrue(history.contains("line-04"))
        assertTrue(history.contains("line-16"))
    }

    @Test
    fun readAllReturnsTheHistoryOldestFirst(@TempDir dir: Path) {
        val file = dir.resolve("lanyard.log").toFile()
        val writer = RotatingWriter(file, maxBytes = 32, maxRotations = 3)

        lines(17).forEach { writer.append(it) }

        val history = writer.readAll()
        assertTrue(history.indexOf("line-04") < history.indexOf("line-16"))
        assertTrue(history.indexOf("line-04") < history.indexOf("line-08"))
    }

    @Test
    fun readAllIsEmptyBeforeAnythingIsWritten(@TempDir dir: Path) {
        val writer = RotatingWriter(dir.resolve("missing.log").toFile())
        assertEquals("", writer.readAll())
    }

    @Test
    fun createsParentDirectoriesOnFirstWrite(@TempDir dir: Path) {
        val file = File(dir.toFile(), "logs/nested/lanyard.log")
        val writer = RotatingWriter(file)

        writer.append("hello")

        assertTrue(file.exists())
    }

    @Test
    fun defaultCapIsOneMebibyteAndThreeRotations() {
        assertEquals(1L shl 20, RotatingWriter.DEFAULT_MAX_BYTES)
        assertEquals(3, RotatingWriter.DEFAULT_MAX_ROTATIONS)
    }
}
