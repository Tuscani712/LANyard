package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The trigger policy for pending-unpair delivery: every "peer reachable" signal
 * delivers immediately, and a timer retries at most once per interval while a
 * record remains. The clock and the delivery action are injected, so the policy
 * is tested without real time or a live peer.
 */
class PendingUnpairRetryTest {

    private val identity = Identity.generate("Phone")

    private class MemoryStore : PendingUnpairStore {
        private val map = LinkedHashMap<String, PendingUnpair>()
        private val delivered = HashMap<String, Long>()
        override fun list(): List<PendingUnpair> = map.values.toList()
        override fun find(fingerprint: String): PendingUnpair? = map[fingerprint.lowercase()]
        override fun upsert(entry: PendingUnpair) {
            map[entry.fingerprint.lowercase()] = entry.copy(fingerprint = entry.fingerprint.lowercase())
        }
        override fun upsertIfCurrent(entry: PendingUnpair, generation: Long): Boolean {
            if (entry.fingerprint.isBlank()) return false
            if (deliveryGeneration(entry.fingerprint) != generation) return false
            upsert(entry)
            return true
        }
        override fun remove(fingerprint: String): Boolean {
            val k = fingerprint.lowercase()
            val present = map.remove(k) != null
            delivered[k] = (delivered[k] ?: 0L) + 1
            return present
        }
        override fun clear() = map.clear()
        override fun snapshot(): List<PendingUnpairSnapshot> =
            map.values.map { PendingUnpairSnapshot(it, delivered[it.fingerprint.lowercase()] ?: 0L) }
        override fun deliveryGeneration(fingerprint: String): Long =
            delivered[fingerprint.lowercase()] ?: 0L
    }

    @Test
    fun timerRetriesAtTheIntervalWhileARecordIsPending() {
        val store = MemoryStore()
        store.upsert(PendingUnpair("aa".repeat(32), "Desk", "10.0.0.5", 47800, queuedAt = 0))
        var now = 1_000L
        var attempts = 0
        val retry = PendingUnpairRetry(
            store = store,
            identity = { identity },
            clock = { now },
            deliver = { _, _, _ -> attempts++; emptyList() },
        )

        retry.tick()
        assertEquals(1, attempts, "the first tick fires at once")

        now += PendingUnpairRetry.RETRY_INTERVAL_MS - 1
        retry.tick()
        assertEquals(1, attempts, "a tick inside the interval is skipped")

        now += 1
        retry.tick()
        assertEquals(2, attempts, "the next interval fires again")
    }

    @Test
    fun aBurstOfReachableSignalsForOnePeerIsOneAttempt() {
        // The same peer is announced over and over (mDNS re-announcements, probes,
        // handshakes). Unreachable, it must be retried once — not once per signal.
        val store = MemoryStore()
        val fp = "0123abcd" + "ff".repeat(28)
        store.upsert(PendingUnpair(fp, "Desk", "10.0.0.9", 5555, queuedAt = 1))
        var attempts = 0
        var now = 0L
        val retry = PendingUnpairRetry(
            store = store,
            identity = { identity },
            clock = { now },
            deliver = { _, _, _ -> attempts++; emptyList() },
        )
        repeat(50) {
            now += 20
            retry.onPeerReachable(SelfFilter.ownShortId(fp), "10.0.0.9", 5555)
        }
        assertEquals(1, attempts, "a burst of signals for one peer must collapse to one attempt")
    }

    @Test
    fun timerStaysQuietWithNothingPending() {
        val store = MemoryStore()
        var attempts = 0
        val retry = PendingUnpairRetry(
            store = store,
            identity = { identity },
            deliver = { _, _, _ -> attempts++; emptyList() },
        )
        retry.tick()
        assertEquals(0, attempts, "no pending record means no delivery attempt")
    }

    @Test
    fun reachableSignalAttachesTheAddressAndDeliversImmediately() {
        val store = MemoryStore()
        val fp = "0123abcd" + "ff".repeat(28)
        store.upsert(PendingUnpair(fp, "Desk", "", 0, queuedAt = 1))
        var seen: PendingUnpair? = null
        var now = 500L
        val retry = PendingUnpairRetry(
            store = store,
            identity = { identity },
            clock = { now },
            deliver = { s, _, _ -> seen = s.find(fp); s.remove(fp); listOf(fp) },
        )

        // A reachable signal delivers immediately, without waiting for the timer.
        retry.tick() // records an attempt time, but the peer is still pending here
        store.upsert(PendingUnpair(fp, "Desk", "", 0, queuedAt = 1))
        now += 5
        val delivered = retry.onPeerReachable(SelfFilter.ownShortId(fp), "10.0.0.9", 5555)

        assertEquals(listOf(fp), delivered)
        assertEquals("10.0.0.9", seen?.host, "the live address is attached before delivery")
        assertEquals(5555, seen?.port)
        assertTrue(store.list().isEmpty())
    }
}
