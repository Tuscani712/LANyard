package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Batch 10: the pure policy behind "a transfer keeps going when backgrounded",
 * for sends, pulls (downloads) and receives alike.
 *
 *  - both locks are held for any active direction and dropped when the last one
 *    ends;
 *  - Back and a swipe-away never cancel, and a swipe-away keeps the service up
 *    while a transfer runs;
 *  - an interrupted row reads "Interrupted – will resume" and is resumed from
 *    its partial when the peer is reachable.
 */
class ForegroundTransferPolicyTest {
    private val policy = ForegroundTransferPolicy()

    private fun row(
        id: String,
        direction: String,
        state: TransferState = TransferState.Running,
        message: String? = null,
        done: Long = 0,
        total: Long = 100,
    ) = TransferRecord(
        id = id,
        direction = direction,
        peerName = "Desk",
        peerFingerprint = "ab".repeat(32),
        label = "file.bin",
        total = total,
        done = done,
        state = state,
        message = message,
        startedAt = 1_000,
    )

    @Test
    fun wakeAndWifiLocksAreHeldForASend() {
        val state = policy.decide(listOf(row("s", "send")))
        assertTrue(state.serviceRunning)
        assertEquals(setOf(TransferLock.WAKE, TransferLock.WIFI), state.locks)
        assertTrue(state.text.startsWith("Sending"), "a send says so: ${state.text}")
    }

    @Test
    fun wakeAndWifiLocksAreHeldForAPullDownload() {
        val state = policy.decide(listOf(row("d", "receive")))
        assertTrue(state.serviceRunning)
        assertEquals(setOf(TransferLock.WAKE, TransferLock.WIFI), state.locks)
        assertTrue(state.text.startsWith("Receiving"), "a pull is a receive: ${state.text}")
    }

    @Test
    fun wakeAndWifiLocksAreHeldForAnIncomingReceive() {
        val state = policy.decide(listOf(row("r", "receive")))
        assertTrue(state.serviceRunning)
        assertEquals(setOf(TransferLock.WAKE, TransferLock.WIFI), state.locks)
    }

    @Test
    fun locksAreHeldForAQueuedRowToo() {
        val state = policy.decide(listOf(row("q", "send", state = TransferState.Queued)))
        assertTrue(state.serviceRunning)
        assertEquals(setOf(TransferLock.WAKE, TransferLock.WIFI), state.locks)
    }

    @Test
    fun locksAreReleasedWhenTheLastTransferEnds() {
        // One active row: both locks held.
        assertTrue(policy.decide(listOf(row("a", "send"))).locks.isNotEmpty())
        // The same row now finished: nothing is active, so both locks drop and
        // the service stops.
        val drained = policy.decide(listOf(row("a", "send", state = TransferState.Done)))
        assertFalse(drained.serviceRunning)
        assertTrue(drained.locks.isEmpty(), "the last transfer ending must release the locks")
    }

    @Test
    fun locksAreReleasedEvenWhenAFinishedRowRemainsInTheList() {
        val state = policy.decide(
            listOf(
                row("done", "send", state = TransferState.Done),
                row("failed", "receive", state = TransferState.Failed, message = "checksum mismatch"),
            ),
        )
        assertFalse(state.serviceRunning)
        assertTrue(state.locks.isEmpty())
    }

    @Test
    fun backNeverCancelsATransfer() {
        val decision = policy.lifecycle(TransferLifecycleEvent.BACK, listOf(row("s", "send")))
        assertFalse(decision.cancelTransfers, "Back must never cancel a transfer")
        assertTrue(decision.keepService)
    }

    @Test
    fun backNeverCancelsWithNothingRunningEither() {
        val decision = policy.lifecycle(TransferLifecycleEvent.BACK, emptyList())
        assertFalse(decision.cancelTransfers)
        assertFalse(decision.keepService)
    }

    @Test
    fun swipeAwayKeepsTheServiceWhileATransferRuns() {
        for (direction in listOf("send", "receive")) {
            val decision = policy.lifecycle(TransferLifecycleEvent.SWIPE_AWAY, listOf(row("x", direction)))
            assertFalse(decision.cancelTransfers, "a swipe-away must not cancel a $direction")
            assertTrue(decision.keepService, "a swipe-away during a $direction keeps the service")
        }
    }

    @Test
    fun swipeAwayWithNothingRunningStopsTheService() {
        val decision = policy.lifecycle(TransferLifecycleEvent.SWIPE_AWAY, emptyList())
        assertFalse(decision.cancelTransfers)
        assertFalse(decision.keepService)
    }

    @Test
    fun interruptedRowTextIsWillResume() {
        val interrupted = row(
            "i",
            "receive",
            state = TransferState.Failed,
            message = ForegroundTransferPolicy.INTERRUPTED_MESSAGE,
            done = 40,
        )
        assertEquals("Interrupted – will resume", interrupted.message)
        assertEquals(ForegroundTransferPolicy.INTERRUPTED_MESSAGE, interrupted.message)
    }

    @Test
    fun resumeDecisionResumesFromPartialWhenThePeerIsReachable() {
        val interrupted = row(
            "i",
            "receive",
            state = TransferState.Failed,
            message = ForegroundTransferPolicy.INTERRUPTED_MESSAGE,
            done = 40,
        )
        val decision = policy.resumeDecision(interrupted, peerReachable = true, partialBytes = 40)
        assertTrue(decision.resume)
        assertTrue(decision.fromPartial, "40 bytes are already on disk: resume from them")
    }

    @Test
    fun resumeDecisionResumesFromZeroWithoutAPartial() {
        val interrupted = row(
            "i",
            "send",
            state = TransferState.Failed,
            message = ForegroundTransferPolicy.INTERRUPTED_MESSAGE,
        )
        val decision = policy.resumeDecision(interrupted, peerReachable = true, partialBytes = 0)
        assertTrue(decision.resume)
        assertFalse(decision.fromPartial)
    }

    @Test
    fun resumeDecisionWaitsWhileThePeerIsUnreachable() {
        val interrupted = row(
            "i",
            "receive",
            state = TransferState.Failed,
            message = ForegroundTransferPolicy.INTERRUPTED_MESSAGE,
            done = 40,
        )
        val decision = policy.resumeDecision(interrupted, peerReachable = false, partialBytes = 40)
        assertFalse(decision.resume, "resume must wait for the reachable signal")
    }

    @Test
    fun resumeDecisionIgnoresRowsThatAreNotInterrupted() {
        assertFalse(policy.resumeDecision(null, peerReachable = true).resume)
        val done = row("d", "send", state = TransferState.Done)
        assertFalse(policy.resumeDecision(done, peerReachable = true).resume)
        val failed = row("f", "send", state = TransferState.Failed, message = "checksum mismatch")
        assertFalse(policy.resumeDecision(failed, peerReachable = true).resume)
    }
}
