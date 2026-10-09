package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The per-peer rate limit behind the pending-unpair retry: a burst of "peer
 * reachable" signals must be one attempt per peer per interval, with a bounded
 * exponential backoff after repeated failures. This is what turns five to six
 * attempts (and log lines) a second into one.
 */
class UnpairRetryGateTest {
    private val peer = "aa".repeat(32)

    @Test
    fun aSecondAttemptWithinTheIntervalIsSuppressed() {
        val gate = UnpairRetryGate()
        assertTrue(gate.allow(peer, 1_000), "the first attempt is always allowed")
        gate.recordAttempt(peer, 1_000)

        assertFalse(gate.allow(peer, 1_000 + UnpairRetryGate.BASE_INTERVAL_MS - 1), "inside the interval")
        assertTrue(gate.allow(peer, 1_000 + UnpairRetryGate.BASE_INTERVAL_MS), "at the interval")
    }

    @Test
    fun aBurstOfSignalsIsOneAttempt() {
        val gate = UnpairRetryGate()
        var attempts = 0
        var now = 0L
        // 100 reachable signals within a couple of seconds: only one attempt.
        repeat(100) {
            now += 20
            if (gate.allow(peer, now)) {
                attempts++
                gate.recordAttempt(peer, now)
                gate.recordResult(peer, delivered = false)
            }
        }
        assertEquals(1, attempts, "a burst of signals must collapse to one attempt")
    }

    @Test
    fun theBackoffDoublesAfterFailuresAndIsBounded() {
        val gate = UnpairRetryGate(baseIntervalMs = 1_000, maxIntervalMs = 8_000)
        assertEquals(1_000, gate.intervalFor(peer))
        repeat(1) { gate.recordResult(peer, delivered = false) }
        assertEquals(2_000, gate.intervalFor(peer))
        repeat(1) { gate.recordResult(peer, delivered = false) }
        assertEquals(4_000, gate.intervalFor(peer))
        repeat(4) { gate.recordResult(peer, delivered = false) }
        assertEquals(8_000, gate.intervalFor(peer), "the backoff must be bounded by the maximum")
    }

    @Test
    fun aDeliveryResetsTheBackoff() {
        val gate = UnpairRetryGate()
        repeat(3) { gate.recordResult(peer, delivered = false) }
        assertTrue(gate.intervalFor(peer) > UnpairRetryGate.BASE_INTERVAL_MS)

        gate.recordResult(peer, delivered = true)
        assertEquals(UnpairRetryGate.BASE_INTERVAL_MS, gate.intervalFor(peer), "success resets the backoff")
    }

    @Test
    fun peersAreLimitedIndependently() {
        val other = "bb".repeat(32)
        val gate = UnpairRetryGate()
        gate.recordAttempt(peer, 0)
        assertFalse(gate.allow(peer, 1), "the attempted peer is inside its interval")
        assertTrue(gate.allow(other, 1), "a different peer is unaffected")
    }
}
