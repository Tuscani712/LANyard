package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The pending-retry / reachable-signal pattern for interrupted transfers,
 * mirroring `PendingUnpairRetry`: a reachable signal resumes immediately, and a
 * timer is the safety net. The store is exercised on disk so a descriptor
 * survives a process death.
 */
class InterruptedTransferRetryTest {

    private class MemoryStore : InterruptedTransferStore {
        val map = LinkedHashMap<String, InterruptedTransfer>()
        override fun list() = map.values.toList()
        override fun upsert(entry: InterruptedTransfer) { map[entry.id] = entry }
        override fun remove(id: String) = map.remove(id) != null
        override fun clear() = map.clear()
    }

    private fun entry(id: String, fp: String, done: Long = 0, direction: String = "receive") =
        InterruptedTransfer(
            id = id,
            direction = direction,
            peerFingerprint = fp,
            peerName = "Desk",
            label = "file.bin",
            total = 100,
            done = done,
            queuedAt = 1_000,
        )

    @Test
    fun hasPendingTracksTheStore() {
        val store = MemoryStore()
        val retry = InterruptedTransferRetry(store)
        assertFalse(retry.hasPending())
        store.upsert(entry("a", "aa".repeat(32)))
        assertTrue(retry.hasPending())
    }

    @Test
    fun aReachablePeerResumesOnlyItsOwnTransfers() {
        val store = MemoryStore()
        val mine = entry("mine", "abcd".repeat(16))
        val other = entry("other", "ffff".repeat(16))
        store.upsert(mine)
        store.upsert(other)
        val retry = InterruptedTransferRetry(store)

        val resumed = retry.onPeerReachable(SelfFilter.ownShortId(mine.peerFingerprint))

        assertEquals(listOf("mine"), resumed.map { it.id })
        assertTrue(store.list().any { it.id == other.id }, "an unrelated peer is left pending")
    }

    @Test
    fun aForegroundSignalOffersEveryPendingTransfer() {
        val store = MemoryStore()
        store.upsert(entry("a", "aa".repeat(32)))
        store.upsert(entry("b", "bb".repeat(32)))
        val retry = InterruptedTransferRetry(store)

        assertEquals(setOf("a", "b"), retry.onPeerReachable(null).map { it.id }.toSet())
    }

    @Test
    fun selectionDoesNotDropTheRecord() {
        val store = MemoryStore()
        store.upsert(entry("a", "aa".repeat(32)))
        val retry = InterruptedTransferRetry(store)

        retry.onPeerReachable(null)

        assertTrue(store.list().any { it.id == "a" }, "the caller clears the record, not the selector")
    }

    @Test
    fun theTimerNudgesButNotMoreOftenThanTheInterval() {
        var now = 10_000L
        val store = MemoryStore()
        store.upsert(entry("a", "aa".repeat(32)))
        val retry = InterruptedTransferRetry(store, clock = { now })

        assertEquals(listOf("a"), retry.tick().map { it.id }, "the first tick fires")
        now += InterruptedTransferRetry.RETRY_INTERVAL_MS - 1
        assertTrue(retry.tick().isEmpty(), "too soon: no hammering")
        now += 1
        assertEquals(listOf("a"), retry.tick().map { it.id }, "after the interval it retries")
    }

    @Test
    fun theTimerDoesNothingWhenNothingIsPending() {
        val retry = InterruptedTransferRetry(MemoryStore())
        assertTrue(retry.tick(now = 999L).isEmpty())
    }

    @Test
    fun hasPartialReflectsBytesOnDisk() {
        assertFalse(entry("a", "aa".repeat(32), done = 0).hasPartial)
        assertTrue(entry("b", "bb".repeat(32), done = 40).hasPartial)
    }

    @Test
    fun theJsonStoreSurvivesAReopen(@TempDir dir: File) {
        val file = File(dir, "transfers-interrupted.json")
        JsonFileInterruptedTransferStore(file).apply {
            upsert(entry("a", "aa".repeat(32), done = 40, direction = "receive").copy(payload = "download\nshare\n/rel\ncontent://tree"))
        }

        val reopened = JsonFileInterruptedTransferStore(file)
        val restored = reopened.list().single()
        assertEquals("a", restored.id)
        assertEquals(40L, restored.done)
        assertEquals("download\nshare\n/rel\ncontent://tree", restored.payload)

        assertTrue(reopened.remove("a"))
        assertFalse(reopened.remove("a"))
        assertTrue(JsonFileInterruptedTransferStore(file).list().isEmpty())
    }
}
