package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The pure de-duplication rules for the push prepare (spool) window: one prepare
 * per peer, an identical selection ignored, a differing one refused.
 */
class PushPrepareGateTest {
    private val peerA = "aa".repeat(32)
    private val peerB = "bb".repeat(32)
    private val files1 = listOf("content://x/1", "content://x/2")
    private val files2 = listOf("content://x/3")

    @Test
    fun identicalSelectionWhilePreparingIsDuplicate() {
        val gate = PushPrepareGate()
        val first = gate.begin(peerA, files1)
        assertTrue(first is PrepareStart.Started, "the first selection opens the window")

        val second = gate.begin(peerA, files1)
        assertTrue(second is PrepareStart.Duplicate, "the same peer + URI set must be ignored, not queued twice: $second")
        assertEquals(1, gate.activeCount(), "a duplicate must not open a second window")
    }

    @Test
    fun theSameSelectionInAnyOrderIsDuplicate() {
        val gate = PushPrepareGate()
        gate.begin(peerA, files1)
        val reversed = gate.begin(peerA, files1.reversed())
        assertTrue(reversed is PrepareStart.Duplicate, "URI order must not change identity: $reversed")
    }

    @Test
    fun aDifferingSelectionWhilePreparingIsBusy() {
        val gate = PushPrepareGate()
        gate.begin(peerA, files1)

        val other = gate.begin(peerA, files2)
        assertTrue(other is PrepareStart.Busy, "a different send to the same peer must be refused, not queued: $other")
        assertEquals(PushPrepareGate.BUSY_REASON, (other as PrepareStart.Busy).reason)
        assertEquals(1, gate.activeCount(), "the refused send must not open a window")
    }

    @Test
    fun aSecondPeerMayPrepareConcurrently() {
        val gate = PushPrepareGate()
        val a = gate.begin(peerA, files1)
        val b = gate.begin(peerB, files2)
        assertTrue(a is PrepareStart.Started)
        assertTrue(b is PrepareStart.Started, "a different device is not blocked by another device's prepare")
        assertEquals(2, gate.activeCount())
        assertTrue(gate.isPreparing(peerA))
        assertTrue(gate.isPreparing(peerB))
    }

    @Test
    fun endReleasesTheWindowSoThePeerMayTryAgain() {
        val gate = PushPrepareGate()
        val started = gate.begin(peerA, files1) as PrepareStart.Started
        assertTrue(gate.isPreparing(peerA))

        gate.end(started.key)

        assertFalse(gate.isPreparing(peerA))
        assertEquals(0, gate.activeCount())
        assertTrue(gate.begin(peerA, files2) is PrepareStart.Started, "after the window closes a new selection is allowed")
    }

    @Test
    fun endIsIdempotent() {
        val gate = PushPrepareGate()
        val started = gate.begin(peerA, files1) as PrepareStart.Started
        gate.end(started.key)
        gate.end(started.key)
        assertEquals(0, gate.activeCount())
    }

    @Test
    fun theKeyIsStableAndPeerSpecific() {
        val gate = PushPrepareGate()
        val key = gate.key(peerA, files1)
        assertEquals(key, gate.key(peerA, files1.reversed()))
        assertNotEquals(key, gate.key(peerB, files1), "different peers must not share a key")
        assertNotEquals(key, gate.key(peerA, files2), "different URI sets must not share a key")
    }
}
