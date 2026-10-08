package io.github.tuscani712.lanyard.core

import java.nio.file.Files
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * A finished row must stay on the Transfers list and survive a restart. These
 * cover the pure half of that: the board keeps the row with its result fields,
 * the JSON store round-trips it, the cap drops the oldest, and Clear finished
 * only removes rows that are no longer active.
 */
class TransferHistoryTest {
    private var now = 1_000L

    private fun board(cap: Int = TransferBoard.HISTORY_CAP) =
        TransferBoard(historyCap = cap, stalledAfterMillis = 10 * 60_000, clock = { now })

    private fun row(
        id: String,
        direction: String = "send",
        state: TransferState = TransferState.Running,
        total: Long = 100,
        startedAt: Long = now,
    ) = TransferRecord(
        id = id,
        direction = direction,
        peerName = "Desk",
        peerFingerprint = "ab".repeat(32),
        label = "file-$id",
        total = total,
        done = 0,
        state = state,
        startedAt = startedAt,
    )

    private fun store(cap: Int = TransferBoard.HISTORY_CAP): TransferHistoryStore {
        val dir = Files.createTempDirectory("lanyard-history").toFile()
        return JsonFileTransferHistoryStore(dir.resolve("transfers.json"), cap = cap)
    }

    @Test
    fun aFinishedRowStaysAndCarriesItsSizeAndAverageSpeed() {
        val b = board()
        b.add(row("t_send", "send", TransferState.Running, total = 100, startedAt = 1_000))
        now = 3_000 // 2 seconds for 100 bytes

        b.end("t_send", TransferState.Done, "Sent 1 file(s)")

        val done = b.firstOrNull("t_send")
        assertNotNull(done, "a finished row must stay on the list")
        assertEquals(TransferState.Done, done?.state)
        assertEquals(100L, done?.done, "Done rows show their full size")
        assertEquals(100L, done?.total)
        assertEquals(50.0, done?.averageSpeed ?: 0.0, 0.001, "Done rows show their average speed")
        assertTrue(b.running().isEmpty(), "a finished row is not active")
    }

    @Test
    fun aFinishedReceiveKeepsTheFolderItLandedIn() {
        val b = board()
        b.add(row("p_recv", "receive", TransferState.Running, total = 400))
        now = 5_000

        b.end("p_recv", TransferState.Done, "Received 1 file(s)", destinationFolder = "LANyard")

        assertEquals("LANyard", b.firstOrNull("p_recv")?.destinationFolder)
        assertEquals(TransferState.Done, b.firstOrNull("p_recv")?.state)
    }

    @Test
    fun aFailedRowHasNoAverageSpeedButStaysOnTheList() {
        val b = board()
        b.add(row("t_fail", "send", TransferState.Running, startedAt = 1_000))
        now = 3_000

        b.end("t_fail", TransferState.Failed, "Connection lost")

        assertEquals(TransferState.Failed, b.firstOrNull("t_fail")?.state)
        assertEquals(0.0, b.firstOrNull("t_fail")?.averageSpeed ?: -1.0, 0.001)
        assertTrue(b.running().isEmpty(), "a failed row is not active")
    }

    @Test
    fun aCancelledRowStaysOnTheListButIsNotActive() {
        val b = board()
        b.add(row("t_cancel", "send", TransferState.Running))

        assertNotNull(b.cancel("t_cancel"))

        assertEquals(TransferState.Cancelled, b.firstOrNull("t_cancel")?.state)
        assertTrue(b.running().isEmpty())
        assertNull(b.cancel("t_cancel"), "cancelling a finished row is a no-op")
    }

    @Test
    fun historySurvivesASimulatedRestart() {
        val store = store()
        val first = board()
        first.add(row("t_done", "receive", TransferState.Running, total = 250))
        now = 2_500
        first.end("t_done", TransferState.Done, "Received 1 file(s)", destinationFolder = "Downloads")
        store.save(first.snapshot())

        // A fresh process: a new board seeded only from what was written to disk.
        val restarted = board()
        restarted.replaceAll(store.load())

        val restored = restarted.firstOrNull("t_done")
        assertNotNull(restored, "the finished row must survive a restart")
        assertEquals(TransferState.Done, restored?.state)
        assertEquals(250L, restored?.total)
        assertEquals(250L, restored?.done)
        assertEquals("Downloads", restored?.destinationFolder)
        assertTrue(restored!!.averageSpeed > 0, "the average speed is persisted too")
    }

    @Test
    fun historyCapKeepsOnlyTheNewestHundred() {
        val b = board()
        repeat(150) { i ->
            now = 1_000L + i
            b.add(row("t_$i", "send", TransferState.Done, startedAt = now))
        }

        assertEquals(TransferBoard.HISTORY_CAP, b.snapshot().size)
        assertEquals("t_149", b.snapshot().first().id, "the newest row is kept")
        assertNull(b.firstOrNull("t_0"), "the oldest rows are dropped")
        assertNull(b.firstOrNull("t_49"), "exactly the newest 100 remain")
        assertNotNull(b.firstOrNull("t_50"))
    }

    @Test
    fun theStoreAlsoCapsWhatItLoads() {
        val store = store(cap = 10)
        // Newest first, exactly as the board hands them to the store.
        val rows = (0 until 25).map { row("t_$it", "send", TransferState.Done, startedAt = 10_000L - it) }

        store.save(rows)

        val loaded = store.load()
        assertEquals(10, loaded.size, "only the newest 10 are kept")
        assertEquals("t_0", loaded.first().id, "the newest row is first")
        assertEquals("t_9", loaded.last().id)
    }

    @Test
    fun clearFinishedRemovesOnlyFinishedRows() {
        val b = board()
        b.add(row("t_live", "send", TransferState.Running))
        b.add(row("t_done", "send", TransferState.Done))
        b.add(row("t_fail", "send", TransferState.Failed))
        b.add(row("t_cancel", "send", TransferState.Cancelled))

        b.clearFinished()

        assertEquals(listOf("t_live"), b.snapshot().map { it.id }, "only the active row survives")
        assertEquals(TransferState.Running, b.firstOrNull("t_live")?.state)
        assertFalse(b.snapshot().any { it.state == TransferState.Done })
    }
}
