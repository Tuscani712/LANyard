package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Collections

/**
 * The receive path must be able to say where a file really landed, so the
 * Transfers row and the diagnostics log cannot silently disagree with the
 * folder the person chose.
 */
class InboxDestinationReportTest {

    @Test
    fun thePlacedFolderIsReportedAndLogged() {
        val spool = Files.createTempDirectory("lanyard-spool").toFile()
        val lines = Collections.synchronizedList(ArrayList<String>())
        val destination = object : PushDestination {
            override fun place(relPath: String, spool: File, size: Long): String =
                relPath.substringAfterLast('/')
            override fun folder(): String = InboxPaths.DEFAULT_LABEL
        }
        val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = destination,
            freeBytes = { 1L shl 40 },
            diag = { lines.add(it) },
        )
        val bytes = byteArrayOf(1, 2, 3, 4)
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        val offer = receiver.offer("p", "P", listOf(PushFileRequest("photo.jpg", bytes.size.toLong(), 0)), 0, 0)
        receiver.receiveWhole(offer.pushId, "p", "photo.jpg", sha, bytes.inputStream())

        assertEquals(InboxPaths.DEFAULT_LABEL, receiver.destinationFolder())
        assertTrue(
            lines.any { it.contains("dest=${InboxPaths.DEFAULT_LABEL}") },
            "the placement log must name the real folder, got: $lines",
        )
    }
}
