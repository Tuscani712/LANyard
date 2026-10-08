package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The device screen's "Unpair this device" action must run the exact same
 * idempotent routine as the Settings list: notify the peer (a 403 for a peer
 * that already dropped us still counts as success), always drop the local entry
 * and its transfers, remember a pending retry when the peer is offline, and
 * surface the result. The confirmation dialog and its button are Compose-only;
 * [UnpairPrompt] is the pure gate they use, so the "a confirmation is required"
 * rule is covered here.
 */
class DeviceUnpairTest {

    private fun peer(): PairedPeer =
        PairedPeer("ab".repeat(32), "Desk", "10.0.0.5", 47800, browse = true, push = true, pairedAt = 0)

    @Test
    fun unpairNotifiesPeerClearsLocalAndPendingOnSuccess() {
        var notified = false
        var localRemoved = false
        var transfersCancelled = false
        var pendingCleared = false
        var pendingRemembered = false

        val outcome = Unpair.perform(
            notifyPeer = { notified = true; true },
            removeLocal = { localRemoved = true },
            cancelTransfers = { transfersCancelled = true },
            onRemoteNotified = { pendingCleared = true },
            onRemoteNotNotified = { pendingRemembered = true },
        )

        assertTrue(notified, "the idempotent revoke must run")
        assertTrue(localRemoved, "the local entry is always removed")
        assertTrue(transfersCancelled, "running transfers to the peer are cancelled")
        assertTrue(pendingCleared, "a notified peer clears any pending retry")
        assertFalse(pendingRemembered)
        assertTrue(outcome.remoteNotified)
        assertNull(outcome.notice, "a cleanly notified peer shows no notice")
    }

    @Test
    fun unpairOfflineRemovesLocalAndRecordsPendingWithNotice() {
        var localRemoved = false
        var transfersCancelled = false
        var pendingRemoved = false
        var pendingRemembered = false

        val outcome = Unpair.perform(
            notifyPeer = { false },
            removeLocal = { localRemoved = true },
            cancelTransfers = { transfersCancelled = true },
            onRemoteNotified = { pendingRemoved = true },
            onRemoteNotNotified = { pendingRemembered = true },
        )

        assertFalse(outcome.remoteNotified)
        assertEquals(Unpair.REMOTE_NOT_NOTIFIED, outcome.notice, "the result must surface to the person")
        assertTrue(localRemoved, "the local entry is removed even when the peer is offline")
        assertTrue(transfersCancelled)
        assertTrue(pendingRemembered, "an offline peer is recorded so the notification is retried")
        assertFalse(pendingRemoved)
    }

    @Test
    fun anOfflinePeerStillShowsThePeerPageSoUnpairAndBackStay() {
        // The device-detail error/offline state must be the normal peer page,
        // which carries Unpair and the Back handler, never a bare message that
        // hides the actions.
        assertEquals(PeerDetailBody.PEER_PAGE, peerDetailBody(loading = false, openShare = false))
        assertEquals(PeerDetailBody.SHARE_TREE, peerDetailBody(loading = false, openShare = true))
        assertEquals(PeerDetailBody.LOADING, peerDetailBody(loading = true, openShare = false))
    }

    @Test
    fun confirmIsRequiredBeforeTheDeviceScreenUnpairs() {
        val idle = UnpairPrompt()
        assertFalse(idle.isConfirming, "no dialog before the action is chosen")
        assertNull(idle.confirmed(), "nothing is unpair-able until the person confirms")

        val staged = idle.request(peer())
        assertTrue(staged.isConfirming, "choosing the action opens the confirmation")
        assertEquals(peer(), staged.confirmed())

        assertNull(staged.cancel().confirmed(), "cancelling clears the staged target")
        assertNull(staged.confirm().confirmed(), "confirming clears the target after it is dispatched")
    }
}
