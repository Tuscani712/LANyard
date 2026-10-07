package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.InputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap

/** The phone-side receive path: validation, spooling, verification, placement. */
class InboxReceiverTest {
    private val spool = Files.createTempDirectory("lanyard-spool").toFile()
    private val placed = ConcurrentHashMap<String, Long>()

    private fun receiver(free: Long = 1L shl 40) = InboxReceiver(
        spoolRoot = spool,
        destination = PushDestination { rel, f, size ->
            placed[rel] = f.length()
            rel.substringAfterLast('/')
        },
        freeBytes = { free },
    )

    private fun req(rel: String, size: Long) = PushFileRequest(rel, size, 0)

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    fun acceptsAndReportsOffsets() {
        val r = receiver()
        val o = r.offer("peer", "Peer", listOf(req("a.bin", 3), req("dir/b.bin", 5)), 0, 0)
        assertTrue(o.accepted)
        assertEquals(mapOf("a.bin" to 0L, "dir/b.bin" to 0L), o.offsets)
    }

    @Test
    fun rejectsBadNamesAndDuplicates() {
        val r = receiver()
        assertThrows(PeerHttpException::class.java) { r.offer("p", "P", listOf(req("../x", 1)), 0, 0) }
        assertThrows(PeerHttpException::class.java) { r.offer("p", "P", listOf(req("a", 1), req("a", 1)), 0, 0) }
    }

    @Test
    fun rejectsOverSizeAndLowSpace() {
        val r = receiver()
        val e1 = assertThrows(PeerHttpException::class.java) { r.offer("p", "P", listOf(req("a", 100)), 0, 50) }
        assertEquals(413, e1.code)
        val need = PushProtocol.requiredFreeSpace(100, 100)
        val e2 = assertThrows(PeerHttpException::class.java) { receiver(need - 1).offer("p", "P", listOf(req("a", 100)), 0, 0) }
        assertEquals(507, e2.code)
    }

    @Test
    fun writeChunkThenCompleteVerifiesAndPlaces() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("a.bin", 4)), 0, 0)
        val data = byteArrayOf(1, 2, 3, 4)
        r.writeChunk(o.pushId, "p", "a.bin", 0, data.inputStream())
        r.complete(o.pushId, "p", "a.bin", sha(data))
        assertEquals(4L, placed["a.bin"])
        assertFalse(spool.walkTopDown().any { it.isFile && it.name.endsWith(".lanpart") }, "spool part should be gone")
    }

    @Test
    fun wrongChecksumIsRefused() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("a.bin", 4)), 0, 0)
        r.writeChunk(o.pushId, "p", "a.bin", 0, byteArrayOf(1, 2, 3, 4).inputStream())
        assertThrows(PeerHttpException::class.java) { r.complete(o.pushId, "p", "a.bin", "00".repeat(32)) }
    }

    @Test
    fun wholeFileFastPath() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("a.bin", 3)), 0, 0)
        val data = byteArrayOf(9, 8, 7)
        r.receiveWhole(o.pushId, "p", "a.bin", sha(data), data.inputStream())
        assertEquals(3L, placed["a.bin"])
    }

    @Test
    fun cancelTurnsFurtherWritesInto410() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("a.bin", 10)), 0, 0)
        assertTrue(r.cancel(o.pushId, "p"))
        assertTrue(r.wasCancelled(o.pushId))
        val e = assertThrows(PeerHttpException::class.java) {
            r.writeChunk(o.pushId, "p", "a.bin", 0, byteArrayOf(1).inputStream())
        }
        assertEquals(410, e.code)
    }

    @Test
    fun resumeReportsTheBytesAlreadyOnDisk() {
        val r = receiver()
        val first = r.offer("p", "P", listOf(req("a.bin", 10)), 0, 0)
        r.writeChunk(first.pushId, "p", "a.bin", 0, byteArrayOf(1, 2, 3).inputStream())
        // A fresh receiver (as after an app restart) sees the peer-keyed part and
        // offers it back as resume offset.
        val second = receiver().offer("p", "P", listOf(req("a.bin", 10)), 0, 0)
        assertEquals(3L, second.offsets["a.bin"])
    }

    @Test
    fun oversizedStreamIsStoppedAtTheCap() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("a.bin", 4)), 0, 0)
        val ex = assertThrows(PeerHttpException::class.java) {
            // 100 bytes offered as 4: must be caught while reading.
            r.writeChunk(o.pushId, "p", "a.bin", 0, ByteArray(100).inputStream())
        }
        assertEquals(400, ex.code)
        assertFalse(spool.walkTopDown().any { it.isFile && it.name.endsWith(".lanpart") && it.length() > 4 })
    }

    @Test
    fun offsetPastTheSizeIsRefused() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("a.bin", 4)), 0, 0)
        val ex = assertThrows(PeerHttpException::class.java) {
            r.writeChunk(o.pushId, "p", "a.bin", 5, byteArrayOf(1).inputStream())
        }
        assertEquals(400, ex.code)
    }

    @Test
    fun sameNameFromDifferentPeersUsesSeparateSpool() {
        val r = receiver()
        val a = r.offer("peerA", "A", listOf(req("photo.jpg", 3)), 0, 0)
        val b = r.offer("peerB", "B", listOf(req("photo.jpg", 3)), 0, 0)
        r.writeChunk(a.pushId, "peerA", "photo.jpg", 0, byteArrayOf(1, 1, 1).inputStream())
        r.writeChunk(b.pushId, "peerB", "photo.jpg", 0, byteArrayOf(2, 2, 2).inputStream())
        r.complete(a.pushId, "peerA", "photo.jpg", sha(byteArrayOf(1, 1, 1)))
        r.complete(b.pushId, "peerB", "photo.jpg", sha(byteArrayOf(2, 2, 2)))
        assertEquals(3L, placed["photo.jpg"])
    }

    @Test
    fun secondInFlightSamePeerAndNameIsRefused() {
        val r = receiver()
        r.offer("peerA", "A", listOf(req("photo.jpg", 3)), 0, 0)
        val ex = assertThrows(PeerHttpException::class.java) {
            r.offer("peerA", "A", listOf(req("photo.jpg", 3)), 0, 0)
        }
        assertEquals(409, ex.code)
    }

    @Test
    @Timeout(120)
    fun streamsALargeFileWithoutBufferingIt() {
        val r = receiver()
        val total = 256L * 1024 * 1024 // 256 MB, far larger than any test buffer
        val o = r.offer("p", "P", listOf(req("big.bin", total)), 0, 0)
        // A generator stream: the test never materializes the bytes.
        val written = r.writeChunk(o.pushId, "p", "big.bin", 0, GenInput(total))
        assertEquals(total, written)
        val part = spool.walkTopDown().first { it.isFile && it.name.endsWith(".lanpart") }
        assertEquals(total, part.length())
        r.complete(o.pushId, "p", "big.bin", genSha(total))
        assertEquals(total, placed["big.bin"])
    }

    /** Deterministic byte source with no backing storage. */
    private class GenInput(private var left: Long) : InputStream() {
        override fun read(): Int {
            if (left <= 0) return -1
            left--
            return (left % 251).toInt()
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) return -1
            val n = minOf(len.toLong(), left).toInt()
            for (i in 0 until n) b[off + i] = ((left - i) % 251).toByte()
            left -= n
            return n
        }
    }

    private fun genSha(total: Long): String {
        val md = MessageDigest.getInstance("SHA-256")
        GenInput(total).use { ins ->
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = ins.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }
}
