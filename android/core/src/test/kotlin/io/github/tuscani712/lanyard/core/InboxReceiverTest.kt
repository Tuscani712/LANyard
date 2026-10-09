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
    fun completeReportsFinishingBeforeThePushIsDone() {
        val ownSpool = Files.createTempDirectory("lanyard-spool-finishing").toFile()
        val events = mutableListOf<String>()
        val r = InboxReceiver(
            spoolRoot = ownSpool,
            destination = PushDestination { rel, f, _ -> placed[rel] = f.length(); rel.substringAfterLast('/') },
            freeBytes = { 1L shl 40 },
            onFinishing = { _, _, _, bytes -> events.add("finishing:$bytes") },
            onDone = { _, _, files, _ -> events.add("done:$files") },
        )
        val o = r.offer("p", "P", listOf(req("big.bin", 4)), 0, 0)
        val data = byteArrayOf(1, 2, 3, 4)
        r.writeChunk(o.pushId, "p", "big.bin", 0, data.inputStream())

        r.complete(o.pushId, "p", "big.bin", sha(data))
        // The finishing window opens as the spool is hashed and copied; only the
        // job-level finish closes it as Done.
        assertEquals(listOf("finishing:4"), events, "complete must report Finishing before any Done")
        r.finish(o.pushId, "p")
        assertEquals(listOf("finishing:4", "done:1"), events)
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
    fun duplicateWholeFileIsSafe() {
        val ownSpool = Files.createTempDirectory("lanyard-spool-idem").toFile()
        var places = 0
        val r = InboxReceiver(
            spoolRoot = ownSpool,
            destination = PushDestination { rel, f, _ -> places++; placed[rel] = f.length(); rel },
            freeBytes = { 1L shl 40 },
        )
        val o = r.offer("p", "P", listOf(req("a.bin", 3)), 0, 0)
        val data = byteArrayOf(9, 8, 7)
        r.receiveWhole(o.pushId, "p", "a.bin", sha(data), data.inputStream())
        // A retried whole-file request is an idempotent success.
        r.receiveWhole(o.pushId, "p", "a.bin", sha(data), data.inputStream())
        assertEquals(1, places, "a duplicate whole-file send must not place the file twice")
    }

    @Test
    fun completeIsIdempotent() {
        val ownSpool = Files.createTempDirectory("lanyard-spool-idem").toFile()
        var places = 0
        val r = InboxReceiver(
            spoolRoot = ownSpool,
            destination = PushDestination { rel, f, _ -> places++; placed[rel] = f.length(); rel },
            freeBytes = { 1L shl 40 },
        )
        val o = r.offer("p", "P", listOf(req("c.bin", 4)), 0, 0)
        val data = byteArrayOf(1, 2, 3, 4)
        r.writeChunk(o.pushId, "p", "c.bin", 0, data.inputStream())
        val first = r.complete(o.pushId, "p", "c.bin", sha(data))
        val second = r.complete(o.pushId, "p", "c.bin", sha(data))
        assertEquals(first.placedName, second.placedName)
        assertEquals(4L, second.done)
        assertEquals(1, places, "a duplicate complete must not place the file twice")
        assertFalse(
            ownSpool.walkTopDown().any { it.isFile && it.name.endsWith(".lanpart") },
            "the spool part should be gone after the first complete",
        )
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
    fun cancelLocalFreesTheSpoolAndTheStaleSessionGuard() {
        val failures = mutableListOf<Pair<String, String>>()
        val cancels = mutableListOf<Pair<String, String>>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onFailed = { id, reason -> failures.add(id to reason) },
            onCancelled = { id, reason -> cancels.add(id to reason) },
        )
        val o = r.offer("peerA", "A", listOf(req("photo.jpg", 100)), 0, 0)
        val release = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            runCatching { r.writeChunk(o.pushId, "peerA", "photo.jpg", 0, BlockingInput(3, release)) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(
                waitFor(2_000) { spool.walkTopDown().any { it.isFile && it.name == "photo.jpg.lanpart" && it.length() >= 3 } },
                "test setup: a body should be in flight",
            )
            // A live body blocks a fresh offer (the stale-session guard).
            assertEquals(
                409,
                assertThrows(PeerHttpException::class.java) {
                    r.offer("peerA", "A", listOf(req("photo.jpg", 100)), 0, 0)
                }.code,
            )
            // Cancel from this phone's UI frees it.
            assertEquals("peerA", r.cancelLocal(o.pushId))
        } finally {
            release.countDown()
            writer.join(2_000)
        }
        assertEquals(0, r.count(), "a locally cancelled receive must leave the incoming list")
        assertFalse(
            spool.walkTopDown().any { it.isFile && it.name == "photo.jpg.lanpart" },
            "cancel must delete the .lanpart spool",
        )
        // The stale-session guard is gone: the same peer may offer again.
        val again = r.offer("peerA", "A", listOf(req("photo.jpg", 100)), 0, 0)
        assertTrue(again.pushId != o.pushId, "a re-offer after cancel must get a fresh session")
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
    fun secondOfferSamePeerAndNameWhileBodyInFlightIsRefused() {
        // A body genuinely in flight is a legitimate concurrent push: the new
        // offer must not clobber the shared spool, so it is still refused.
        val r = receiver()
        val first = r.offer("peerA", "A", listOf(req("photo.jpg", 100)), 0, 0)
        val release = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            runCatching {
                r.writeChunk(first.pushId, "peerA", "photo.jpg", 0, BlockingInput(3, release))
            }
        }.apply { isDaemon = true; start() }
        assertTrue(
            waitFor(2_000) { spool.walkTopDown().any { it.isFile && it.name == "photo.jpg.lanpart" && it.length() >= 3 } },
            "test setup: a body should be in flight",
        )
        try {
            val ex = assertThrows(PeerHttpException::class.java) {
                r.offer("peerA", "A", listOf(req("photo.jpg", 100)), 0, 0)
            }
            assertEquals(409, ex.code)
        } finally {
            release.countDown()
            writer.join(2_000)
        }
    }

    @Test
    fun staleOfferSamePeerAndNameIsReplacedNotRefused() {
        val failures = mutableListOf<Pair<String, String>>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, f, _ -> placed[rel] = f.length(); rel },
            freeBytes = { 1L shl 40 },
            onFailed = { id, reason -> failures.add(id to reason) },
        )
        val first = r.offer("peerA", "A", listOf(req("photo.jpg", 10)), 0, 0)
        // No body was ever started, so the first session is stale (idle). The
        // re-offer must replace it rather than return 409.
        val second = r.offer("peerA", "A", listOf(req("photo.jpg", 10)), 0, 0)
        assertTrue(second.pushId != first.pushId, "a re-offer must create a fresh session")
        assertEquals(1, r.count(), "the stale session must be gone")
        assertEquals(listOf(first.pushId to "replaced by a new offer from the same device"), failures)

        // The replacement is a normal session: a fresh write/complete works.
        val data = byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)
        r.writeChunk(second.pushId, "peerA", "photo.jpg", 0, data.inputStream())
        r.complete(second.pushId, "peerA", "photo.jpg", sha(data))
        assertEquals(10L, placed["photo.jpg"])
    }

    @Test
    fun aSupersededSessionCanNoLongerWriteTheSharedPart() {
        // Two overlapping offers name the same file. The first is idle, so the
        // second supersedes it (resume, not 409). The superseded session must be
        // dead: it can never open a second writer on the shared `.lanpart`.
        val r = receiver()
        val first = r.offer("peerA", "A", listOf(req("photo.jpg", 10)), 0, 0)
        val second = r.offer("peerA", "A", listOf(req("photo.jpg", 10)), 0, 0)
        assertTrue(second.pushId != first.pushId, "the idle first offer must be replaced")
        assertEquals(1, r.count(), "only one live session may own the spool")

        val refusedWrite = assertThrows(PeerHttpException::class.java) {
            r.writeChunk(first.pushId, "peerA", "photo.jpg", 0, byteArrayOf(1).inputStream())
        }
        assertEquals(404, refusedWrite.code, "a superseded session must not write the shared part")
        assertThrows(PeerHttpException::class.java) {
            r.complete(first.pushId, "peerA", "photo.jpg", "00".repeat(32))
        }
    }

    @Test
    @Timeout(60)
    fun aBodyStartingUnderAConcurrentOfferNeverSharesTheSpool() {
        // Two overlapping offers name the same file. The first has a body whose
        // start must be atomic with the offer decision: once the body is claimed,
        // a concurrent offer is refused (409) rather than superseding it. Without
        // the atomic claim, the second offer would remove the first while its
        // writer is still starting and two live pushes would share one `.lanpart`.
        //
        // The receiver's per-file diagnostic is emitted only *after* the claim,
        // so waiting for it proves the first body is claimed. A small body then
        // blocks on its input, keeping the claim alive while the offer races it.
        val ownSpool = Files.createTempDirectory("lanyard-spool-writers").toFile()
        val claimed = java.util.concurrent.CountDownLatch(1)
        val r = InboxReceiver(
            spoolRoot = ownSpool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            diag = { line -> if (line.contains(" file id=")) claimed.countDown() },
        )
        // A large resume prefix makes the pre-claim window wide: without the
        // atomic claim the body is not marked in flight until after hashing it.
        val resumeBytes = 256L * 1024 * 1024
        val peerDir = java.io.File(ownSpool, "peerA".take(16).lowercase()).apply { mkdirs() }
        java.io.RandomAccessFile(java.io.File(peerDir, "photo.jpg.lanpart"), "rw").use { it.setLength(resumeBytes) }
        val first = r.offer("peerA", "A", listOf(req("photo.jpg", resumeBytes + 10)), 0, 0)

        val release = java.util.concurrent.CountDownLatch(1)
        val writer = Thread {
            runCatching { r.writeChunk(first.pushId, "peerA", "photo.jpg", resumeBytes, BlockingInput(3, release)) }
        }.apply { isDaemon = true; start() }

        try {
            assertTrue(claimed.await(5, java.util.concurrent.TimeUnit.SECONDS), "test setup: the body must claim the part")
            var superseded = false
            val deadline = System.currentTimeMillis() + 3_000
            while (System.currentTimeMillis() < deadline) {
                try {
                    val again = r.offer("peerA", "A", listOf(req("photo.jpg", resumeBytes + 10)), 0, 0)
                    if (again.pushId != first.pushId) { superseded = true; break }
                } catch (e: PeerHttpException) {
                    assertEquals(409, e.code, "a claimed body must refuse a concurrent offer, not supersede it")
                }
            }
            assertFalse(
                superseded,
                "a body being started must block a supersede: two live pushes must never share one .lanpart",
            )
            assertEquals(1, r.count(), "two live pushes must never share one .lanpart")
        } finally {
            release.countDown()
            writer.join(5_000)
        }
    }

    @Test
    fun staleInFlightReceiveIsReapedAfterTheStallTimeoutAndKeepsItsSpool() {
        val failures = mutableListOf<Pair<String, String>>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onFailed = { id, reason -> failures.add(id to reason) },
            stallTimeoutMillis = 80,
            idleTimeoutMillis = 3_000,
        )
        val o = r.offer("p", "P", listOf(req("a.bin", 1L shl 20)), 0, 0)
        val release = java.util.concurrent.CountDownLatch(1)
        // A body that delivers a few bytes and then makes no further progress.
        val writer = Thread {
            runCatching { r.writeChunk(o.pushId, "p", "a.bin", 0, BlockingInput(8, release)) }
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(waitFor(3_000) { failures.isNotEmpty() }, "a stalled body must be failed by the reaper")
            assertEquals(o.pushId, failures.single().first)
            assertEquals("the connection stalled", failures.single().second)
            assertEquals(0, r.count(), "a stalled session must leave the incoming list")
        } finally {
            release.countDown()
            writer.join(2_000)
            r.close()
        }
        assertTrue(
            spool.walkTopDown().any { it.isFile && it.name == "a.bin.lanpart" },
            "a stalled receive must keep its .lanpart so a re-offer can resume",
        )
    }

    @Test
    fun idleReceiveIsNotReapedBeforeTheIdleTimeout() {
        val failures = mutableListOf<Pair<String, String>>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onFailed = { id, reason -> failures.add(id to reason) },
            stallTimeoutMillis = 60,
            idleTimeoutMillis = 700,
        )
        try {
            r.offer("p", "P", listOf(req("a.bin", 10)), 0, 0)
            // Past the short stall timeout but before the idle timeout: an idle
            // push (no body in flight) must still be alive.
            Thread.sleep(250)
            assertEquals(1, r.count(), "an idle push must not be reaped by the stall timeout")
            assertTrue(failures.isEmpty(), "an idle push must not be failed before its idle timeout")
            // After the idle timeout it is reaped like the desktop.
            assertTrue(waitFor(2_000) { failures.isNotEmpty() }, "an idle push must be reaped after the idle timeout")
            assertEquals(0, r.count())
        } finally {
            r.close()
        }
    }

    /** A byte source that yields [prefix] bytes then blocks until [release]. */
    private class BlockingInput(private var prefix: Int, private val release: java.util.concurrent.CountDownLatch) : InputStream() {
        override fun read(): Int {
            if (prefix > 0) { prefix--; return 7 }
            release.await()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (prefix > 0) {
                val n = minOf(len, prefix)
                for (i in 0 until n) b[off + i] = 7
                prefix -= n
                return n
            }
            release.await()
            return -1
        }
    }

    private fun waitFor(timeoutMs: Long, cond: () -> Boolean): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (cond()) return true
            Thread.sleep(15)
        }
        return cond()
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

    @Test
    fun receiveCallbacksCarryThePushIdAndProgress() {
        val offers = mutableListOf<String>()
        val progress = mutableListOf<Long>()
        val dones = mutableListOf<String>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onOffer = { pushId, _, _, _ -> offers.add(pushId) },
            onProgress = { _, done, _, _, _ -> progress.add(done) },
            onDone = { pushId, _, _, _ -> dones.add(pushId) },
        )
        val o = r.offer("p", "P", listOf(req("a.bin", 4)), 0, 0)
        r.writeChunk(o.pushId, "p", "a.bin", 0, byteArrayOf(1, 2, 3, 4).inputStream())
        r.finish(o.pushId, "p")
        assertEquals(listOf(o.pushId), offers)
        assertEquals(listOf(o.pushId), dones)
        assertTrue(progress.isNotEmpty() && progress.last() == 4L, "progress should report the bytes written: $progress")
    }

    @Test
    fun progressIsWholeTransferCumulativeAcrossFilesAndNeverGoesBackwards() {
        val progress = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val totals = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val filesDone = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onProgress = { _, done, total, fd, _ -> progress.add(done); totals.add(total); filesDone.add(fd) },
        )
        val o = r.offer("p", "P", listOf(req("a.bin", 4), req("b.bin", 6)), 0, 0)
        r.writeChunk(o.pushId, "p", "a.bin", 0, byteArrayOf(1, 2, 3, 4).inputStream())
        val afterA = progress.last()
        assertEquals(4L, afterA, "after the first file the whole-transfer count is that file's bytes")
        r.writeChunk(o.pushId, "p", "b.bin", 0, byteArrayOf(5, 6, 7, 8, 9, 10).inputStream())

        // The counter is cumulative over the whole push and never decreases at
        // the file boundary (which is what made the meter think it had reset).
        assertEquals(progress.sorted(), progress.toList(), "whole-transfer progress must be monotonic: $progress")
        assertEquals(10L, progress.last(), "the last progress is the push total: $progress")
        assertTrue(totals.all { it == 10L }, "total is the whole push, not the current file: $totals")
    }

    @Test
    fun clearAbandonedSpoolKeepsLivePartsAndDropsLeftovers() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("live.bin", 10)), 0, 0)
        r.writeChunk(o.pushId, "p", "live.bin", 0, byteArrayOf(1, 2, 3).inputStream())
        // A leftover from a previous run (no in-memory session points at it).
        val stray = java.io.File(spool, "old-peer/dead.bin.lanpart").apply { parentFile?.mkdirs(); writeText("junk") }

        val removed = r.clearAbandonedSpool()

        assertEquals(1, removed)
        assertFalse(stray.exists(), "an abandoned spool part must be cleared")
        assertTrue(
            spool.walkTopDown().any { it.isFile && it.name == "live.bin.lanpart" },
            "a part belonging to a live push must be kept",
        )
    }

    @Test
    fun cancelNotifiesTheCallerWithThePushId() {
        val cancelled = mutableListOf<String>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onCancelled = { pushId, _ -> cancelled.add(pushId) },
        )
        val o = r.offer("p", "P", listOf(req("a.bin", 4)), 0, 0)
        assertTrue(r.cancel(o.pushId, "p"))
        assertEquals(listOf(o.pushId), cancelled, "a cancelled push must be reported so its row does not stay Running")
    }

    @Test
    fun cancelDeletesTheSpoolUnlikeFailure() {
        val r = receiver()
        val o = r.offer("p", "P", listOf(req("a.bin", 10)), 0, 0)
        r.writeChunk(o.pushId, "p", "a.bin", 0, byteArrayOf(1, 2, 3).inputStream())
        assertTrue(
            spool.walkTopDown().any { it.isFile && it.name == "a.bin.lanpart" },
            "test setup: a partial spool should exist before cancel",
        )
        assertTrue(r.cancel(o.pushId, "p"))
        assertFalse(
            spool.walkTopDown().any { it.isFile && it.name == "a.bin.lanpart" },
            "an explicit cancel must delete the spool",
        )
    }

    @Test
    fun midBodyFailureFiresFailedExactlyOnceAndNeverDone() {
        val failures = mutableListOf<Pair<String, String>>()
        val dones = mutableListOf<String>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onDone = { pushId, _, _, _ -> dones.add(pushId) },
            onFailed = { pushId, reason -> failures.add(pushId to reason) },
        )
        val o = r.offer("p", "P", listOf(req("a.bin", 100)), 0, 0)
        assertThrows(java.io.IOException::class.java) {
            // 10 bytes, then the source throws: the body dies mid-file.
            r.writeChunk(o.pushId, "p", "a.bin", 0, ThrowingInput(10))
        }
        // A second terminal report (e.g. PeerServer also noticing the drop) must
        // not double-fire: the session is already gone.
        assertFalse(r.fail(o.pushId, "second"), "fail must be once-only per session")
        assertEquals(listOf(o.pushId), failures.map { it.first }, "exactly one onFailed for the session")
        assertEquals("Connection lost", failures.single().second)
        assertTrue(dones.isEmpty(), "a failed receive must never report done")
        // A drop is not a decline: the partial spool must survive so a re-offer
        // can resume from it. Only an explicit cancel deletes the spool.
        assertTrue(
            spool.walkTopDown().any { it.isFile && it.name == "a.bin.lanpart" },
            "a failed receive must keep the .lanpart spool for resume",
        )
    }

    @Test
    fun droppedMidBodyKeepsTheSpoolAndReofferResumesFromThePartialSize() {
        val r1 = receiver()
        val first = r1.offer("p", "P", listOf(req("a.bin", 10)), 0, 0)
        assertThrows(java.io.IOException::class.java) {
            // 3 bytes, then the source throws: the body dies mid-file.
            r1.writeChunk(first.pushId, "p", "a.bin", 0, ThrowingInput(3))
        }
        val part = spool.walkTopDown().first { it.isFile && it.name == "a.bin.lanpart" }
        assertEquals(3L, part.length(), "a dropped receive must keep the partial .lanpart spool")

        // The peer re-offers the same file; a fresh receiver (as after a reconnect)
        // must report the partial as the resume offset.
        val r2 = receiver()
        val second = r2.offer("p", "P", listOf(req("a.bin", 10)), 0, 0)
        assertEquals(3L, second.offsets["a.bin"], "a re-offer must resume from the partial size")

        // Finish from that offset and verify the placed file is complete/correct.
        r2.writeChunk(second.pushId, "p", "a.bin", 3, byteArrayOf(4, 5, 6, 7, 8, 9, 10).inputStream())
        r2.complete(second.pushId, "p", "a.bin", sha(byteArrayOf(7, 7, 7, 4, 5, 6, 7, 8, 9, 10)))
        assertEquals(10L, placed["a.bin"], "the resumed file must be placed whole")
    }

    @Test
    fun happyPathFiresDoneAndNeverFailed() {
        val failures = mutableListOf<String>()
        val dones = mutableListOf<String>()
        val r = InboxReceiver(
            spoolRoot = spool,
            destination = PushDestination { rel, _, _ -> rel },
            freeBytes = { 1L shl 40 },
            onDone = { pushId, _, _, _ -> dones.add(pushId) },
            onFailed = { pushId, _ -> failures.add(pushId) },
        )
        val o = r.offer("p", "P", listOf(req("a.bin", 4)), 0, 0)
        r.writeChunk(o.pushId, "p", "a.bin", 0, byteArrayOf(1, 2, 3, 4).inputStream())
        r.finish(o.pushId, "p")
        assertEquals(listOf(o.pushId), dones)
        assertTrue(failures.isEmpty(), "the happy path must never fire onFailed")
    }

    /** A byte source that yields [prefix] bytes and then throws. */
    private class ThrowingInput(private var left: Int) : InputStream() {
        override fun read(): Int {
            if (left <= 0) throw java.io.IOException("connection lost")
            left--
            return 7
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (left <= 0) throw java.io.IOException("connection lost")
            val n = minOf(len, left)
            for (i in 0 until n) b[off + i] = 7
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
