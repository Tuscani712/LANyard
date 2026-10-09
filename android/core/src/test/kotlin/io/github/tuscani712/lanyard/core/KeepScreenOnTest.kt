package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the keep-screen-on rule: the setting gates everything, a live transfer
 * (Preparing included) holds the screen awake, and draining it lets go.
 */
class KeepScreenOnTest {
    private fun record(state: TransferState, direction: String = "send") = TransferRecord(
        id = "t1",
        direction = direction,
        peerName = "Peer",
        label = "file",
        total = 1,
        done = 0,
        state = state,
        startedAt = 0,
    )

    @Test
    fun settingOffNeverKeepsScreenOn() {
        assertFalse(KeepScreenOn.decide(settingEnabled = false, anyLiveTransfer = true))
        assertFalse(KeepScreenOn.decide(settingEnabled = false, anyLiveTransfer = false))
    }

    @Test
    fun settingOnWithoutTransferDoesNotKeepScreenOn() {
        assertFalse(KeepScreenOn.decide(settingEnabled = true, anyLiveTransfer = false))
    }

    @Test
    fun settingOnWithLiveTransferKeepsScreenOn() {
        assertTrue(KeepScreenOn.decide(settingEnabled = true, anyLiveTransfer = true))
    }

    @Test
    fun preparingCountsAsALiveTransfer() {
        val rows = listOf(record(TransferState.Preparing))
        assertTrue(KeepScreenOn.anyLiveTransfer(rows))
        assertTrue(KeepScreenOn.decide(true, KeepScreenOn.anyLiveTransfer(rows)))
    }

    @Test
    fun runningSendsAndReceivesAndDownloadsCount() {
        assertTrue(KeepScreenOn.anyLiveTransfer(listOf(record(TransferState.Running, "send"))))
        assertTrue(KeepScreenOn.anyLiveTransfer(listOf(record(TransferState.Running, "receive"))))
        assertTrue(KeepScreenOn.anyLiveTransfer(listOf(record(TransferState.Queued, "receive"))))
    }

    @Test
    fun drainingStopsKeepingScreenOn() {
        val rows = listOf(
            record(TransferState.Done),
            record(TransferState.Failed),
            record(TransferState.Cancelled),
        )
        assertFalse(KeepScreenOn.anyLiveTransfer(rows))
        assertFalse(KeepScreenOn.decide(settingEnabled = true, anyLiveTransfer = KeepScreenOn.anyLiveTransfer(rows)))
    }

    @Test
    fun reasonNamesTheCause() {
        assertEquals(KeepScreenOn.REASON_SETTING_OFF, KeepScreenOn.reason(false, true))
        assertEquals(KeepScreenOn.REASON_TRANSFER_LIVE, KeepScreenOn.reason(true, true))
        assertEquals(KeepScreenOn.REASON_DRAINED, KeepScreenOn.reason(true, false))
    }

    @Test
    fun logLineFormatsOnAndOff() {
        assertEquals(
            "[transfer] keep-screen-on=on reason=transfer-live",
            KeepScreenOn.logLine(true, KeepScreenOn.REASON_TRANSFER_LIVE),
        )
        assertEquals(
            "[transfer] keep-screen-on=off reason=drained",
            KeepScreenOn.logLine(false, KeepScreenOn.REASON_DRAINED),
        )
        assertEquals(
            "[transfer] keep-screen-on=off reason=activity-stop",
            KeepScreenOn.logLine(false, KeepScreenOn.REASON_ACTIVITY_STOP),
        )
    }
}
