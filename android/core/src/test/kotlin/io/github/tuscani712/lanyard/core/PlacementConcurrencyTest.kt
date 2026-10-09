package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.nio.file.Files
import java.security.MessageDigest
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * De-serialized placement (Item D1): placing files into the destination used to
 * be guarded by a single global lock, so every verified file waited behind the
 * one being copied. Placement is now guarded only by the file's own lock, so
 * many tiny files can be finalized at the same time.
 *
 * Benchmark-style but CI-friendly: it never asserts a wall-clock speed, only
 * that more than one placement is ever inside the destination at once.
 */
class PlacementConcurrencyTest {

    private fun sha(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @Test
    @Timeout(60)
    fun tinyFilePlacementsOverlapInsteadOfSerializing() {
        val fileCount = 8
        val spool = Files.createTempDirectory("lanyard-spool-parallel").toFile()
        val active = AtomicInteger()
        val maxActive = AtomicInteger()
        val allInside = CountDownLatch(fileCount)
        val release = CountDownLatch(1)
        val destination = PushDestination { rel, _, _ ->
            val now = active.incrementAndGet()
            maxActive.updateAndGet { maxOf(it, now) }
            allInside.countDown()
            // Hold every placement open until all of them have entered, so a
            // global lock would block all but one before this point.
            release.await(5, TimeUnit.SECONDS)
            active.decrementAndGet()
            rel.substringAfterLast('/')
        }
        val receiver = InboxReceiver(
            spoolRoot = spool,
            destination = destination,
            freeBytes = { 1L shl 40 },
        )
        receiver.use { r ->
            val reqs = (0 until fileCount).map { PushFileRequest("f$it.bin", 4, 0) }
            val offer = r.offer("peer", "Peer", reqs, 0, 0)
            val pool = Executors.newFixedThreadPool(fileCount)
            try {
                val futures = (0 until fileCount).map { i ->
                    pool.submit {
                        val data = byteArrayOf(1, 2, 3, 4)
                        r.receiveWhole(offer.pushId, "peer", "f$i.bin", sha(data), data.inputStream())
                    }
                }
                assertTrue(
                    allInside.await(5, TimeUnit.SECONDS),
                    "all $fileCount placements should run at once; with a global lock only one can",
                )
                release.countDown()
                futures.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally {
                release.countDown()
                pool.shutdownNow()
            }
            assertTrue(
                maxActive.get() >= 2,
                "placements must overlap, saw max concurrent placements = ${maxActive.get()}",
            )
        }
    }

    /** A tiny local helper so the test's InboxReceiver is closed on every path. */
    private fun <T> InboxReceiver.use(block: (InboxReceiver) -> T): T =
        try {
            block(this)
        } finally {
            close()
        }

    @Test
    @Timeout(60)
    fun aSlowDestinationDoesNotSerialiseManyTinyFiles() {
        // Same idea, timed: each placement sleeps 100ms. Serialized that is
        // ~800ms for eight files; overlapped it is far less. The bound is loose
        // so a loaded CI machine still passes, while a global lock cannot.
        val fileCount = 8
        val spool = Files.createTempDirectory("lanyard-spool-timed").toFile()
        val destination = PushDestination { rel, _, _ ->
            Thread.sleep(100)
            rel.substringAfterLast('/')
        }
        val receiver = InboxReceiver(spool, destination, { 1L shl 40 })
        receiver.use { r ->
            val reqs = (0 until fileCount).map { PushFileRequest("f$it.bin", 4, 0) }
            val offer = r.offer("peer", "Peer", reqs, 0, 0)
            val pool = Executors.newFixedThreadPool(fileCount)
            val start = System.currentTimeMillis()
            try {
                val futures = (0 until fileCount).map { i ->
                    pool.submit {
                        val data = byteArrayOf(1, 2, 3, 4)
                        r.receiveWhole(offer.pushId, "peer", "f$i.bin", sha(data), data.inputStream())
                    }
                }
                futures.forEach { it.get(15, TimeUnit.SECONDS) }
            } finally {
                pool.shutdownNow()
            }
            val elapsed = System.currentTimeMillis() - start
            assertTrue(elapsed < (fileCount * 100) * 0.8, "placements look serialized: ${elapsed}ms for $fileCount files")
        }
    }
}
