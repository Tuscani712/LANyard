package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The cancel and no-progress-aging rules for every row kind. These are the pure
 * half of `TransferManager.cancel` (send / download / receive) and its aging
 * sweep; the Android side only adds the side effects.
 */
class TransferBoardTest {
    private var now = 1_000L
    private fun board(stalledAfter: Long = 10 * 60_000) = TransferBoard(stalledAfterMillis = stalledAfter, clock = { now })

    private fun row(id: String, direction: String, state: TransferState, startedAt: Long = now) =
        TransferRecord(
            id = id,
            direction = direction,
            peerName = "Desk",
            peerFingerprint = "ab".repeat(32),
            label = "file",
            total = 100,
            done = 0,
            state = state,
            startedAt = startedAt,
        )

    @Test
    fun cancelEndsASendRow() {
        val b = board()
        b.add(row("t_send", "send", TransferState.Running))
        val ended = b.cancel("t_send")
        assertNotNull(ended, "a Running send row must be cancellable")
        assertEquals(TransferState.Cancelled, b.firstOrNull("t_send")?.state)
        assertEquals("Cancelled", b.firstOrNull("t_send")?.message)
        assertTrue(b.running().isEmpty(), "a cancelled row must no longer be active")
    }

    @Test
    fun cancelEndsADownloadRow() {
        val b = board()
        b.add(row("t_dl", "receive", TransferState.Queued))
        val ended = b.cancel("t_dl")
        assertNotNull(ended, "a Queued download row must be cancellable")
        assertEquals(TransferState.Cancelled, b.firstOrNull("t_dl")?.state)
        assertNull(b.cancel("t_dl"), "cancelling an already-cancelled row is a no-op")
    }

    @Test
    fun cancelEndsAReceiveRow() {
        val b = board()
        b.add(row("p_recv", "receive", TransferState.Running))
        val ended = b.cancel("p_recv")
        assertNotNull(ended, "a receive row made by noteReceiveStarted must be cancellable")
        assertEquals(TransferState.Cancelled, b.firstOrNull("p_recv")?.state)
    }

    @Test
    fun cancelLeavesFinishedRowsAlone() {
        val b = board()
        b.add(row("t_done", "send", TransferState.Done))
        assertNull(b.cancel("t_done"))
        assertEquals(TransferState.Done, b.firstOrNull("t_done")?.state)
    }

    @Test
    fun noProgressAgingFailsAStuckRunningRow() {
        val b = board(stalledAfter = 1_000)
        b.add(row("t_stuck", "send", TransferState.Running, startedAt = now))
        now += 1_001
        val aged = b.age()
        assertEquals(listOf("t_stuck"), aged.map { it.id })
        assertEquals(TransferState.Failed, b.firstOrNull("t_stuck")?.state)
        assertEquals("No progress", b.firstOrNull("t_stuck")?.message)
    }

    @Test
    fun agingFiresForEveryLiveDirection() {
        val b = board(stalledAfter = 1_000)
        b.add(row("a", "send", TransferState.Running, startedAt = now))
        b.add(row("b", "receive", TransferState.Running, startedAt = now))
        now += 1_001
        assertEquals(setOf("a", "b"), b.age().map { it.id }.toSet())
        assertEquals(2, b.snapshot().count { it.state == TransferState.Failed && it.message == "No progress" })
    }

    @Test
    fun recentProgressResetsTheAgingDeadline() {
        val b = board(stalledAfter = 1_000)
        b.add(row("t_live", "send", TransferState.Running, startedAt = now))
        now += 900
        b.progress("t_live", done = 50, total = 100, speed = 1.0)
        now += 900 // 900ms since progress, under the 1000ms window
        assertTrue(b.age().isEmpty(), "a row that made progress must not age out")
        now += 200 // now 1100ms since progress
        assertEquals(listOf("t_live"), b.age().map { it.id })
    }

    @Test
    fun agingDoesNotTouchQueuedOrFinishedRows() {
        val b = board(stalledAfter = 1_000)
        b.add(row("q", "send", TransferState.Queued, startedAt = now))
        b.add(row("d", "send", TransferState.Done, startedAt = now))
        now += 10_000
        assertTrue(b.age().isEmpty(), "only Running rows age out")
        assertEquals(TransferState.Queued, b.firstOrNull("q")?.state)
        assertEquals(TransferState.Done, b.firstOrNull("d")?.state)
    }

    @Test
    fun finishingWithDoneRecordsTheAverageSpeed() {
        val b = board()
        b.add(row("t_send", "send", TransferState.Running, startedAt = 1_000))
        now = 3_000 // 2 seconds for 100 bytes
        b.end("t_send", TransferState.Done, "Sent 1 file(s)")
        assertEquals(50.0, b.firstOrNull("t_send")?.averageSpeed ?: 0.0, 0.001)
        assertEquals(100L, b.firstOrNull("t_send")?.done)
    }

    @Test
    fun aFailedRowHasNoAverageSpeed() {
        val b = board()
        b.add(row("t_send", "send", TransferState.Running, startedAt = 1_000))
        now = 3_000
        b.end("t_send", TransferState.Failed, "Connection lost")
        assertEquals(0.0, b.firstOrNull("t_send")?.averageSpeed ?: -1.0, 0.001)
    }

    @Test
    fun restartFailsInterruptedRows() {
        val b = board()
        b.add(row("r", "receive", TransferState.Running))
        b.add(row("q", "send", TransferState.Queued))
        b.add(row("d", "send", TransferState.Done))
        assertEquals(setOf("r", "q"), b.failInterrupted().map { it.id }.toSet())
        assertEquals(TransferState.Failed, b.firstOrNull("r")?.state)
        assertEquals("Interrupted", b.firstOrNull("q")?.message)
        assertEquals(TransferState.Done, b.firstOrNull("d")?.state)
    }
}
