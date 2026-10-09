package io.github.tuscani712.lanyard.core

/**
 * Unpairing a peer and the wording for the outcomes a person needs to know
 * about. When the local entry is gone but the peer could not be told, the other
 * side may still list this device; that must not be silent, and the phone keeps
 * a pending record so the notification is retried when the peer is next seen.
 */
object Unpair {
    /**
     * Shown when the pairing was removed on this device but the peer could not be
     * reached to drop its own copy. It is retried automatically when that device
     * is next seen.
     */
    const val REMOTE_NOT_NOTIFIED = "Removed here. The other device could not be notified; we will keep trying."

    /**
     * Shown when a peer we still had paired answered a request with 403
     * "not paired": it has unpaired us, so the stale entry was removed.
     */
    const val UNPAIRED_BY_PEER = "The other device unpaired this one. It has been removed from your devices."

    /** What an unpair did, ready for a screen to surface. */
    data class Outcome(val remoteNotified: Boolean) {
        /** The message to show, or null when the peer was told cleanly. */
        val notice: String? get() = if (remoteNotified) null else REMOTE_NOT_NOTIFIED
    }

    /**
     * The one unpair routine, shared by the Settings list and the device screen
     * so both behave identically. [notifyPeer] is the idempotent remote revoke:
     * a peer that already dropped us answers 200 (or, from an older peer, 403
     * "not paired"), both of which count as success. The local entry and its
     * running transfers are always cleared; when the peer could not be told the
     * caller records a pending retry instead.
     */
    fun perform(
        notifyPeer: () -> Boolean,
        removeLocal: () -> Unit,
        cancelTransfers: () -> Unit,
        onRemoteNotified: () -> Unit,
        onRemoteNotNotified: () -> Unit,
    ): Outcome {
        val notified = notifyPeer()
        removeLocal()
        cancelTransfers()
        if (notified) onRemoteNotified() else onRemoteNotNotified()
        return Outcome(notified)
    }
}

/**
 * The device screen's unpair confirmation: tapping "Unpair this device" stages
 * the peer, and it is only handed back once the confirmation is accepted. Pure
 * so the "a confirmation is required" rule is testable without Compose.
 */
data class UnpairPrompt(val target: PairedPeer? = null) {
    /** True while the confirmation dialog should be showing. */
    val isConfirming: Boolean get() = target != null

    fun request(peer: PairedPeer): UnpairPrompt = UnpairPrompt(peer)

    fun cancel(): UnpairPrompt = UnpairPrompt()

    /** The staged peer to unpair, or null when nothing was confirmed. */
    fun confirmed(): PairedPeer? = target

    /** Clears the prompt after the user confirms and the unpair is dispatched. */
    fun confirm(): UnpairPrompt = UnpairPrompt()
}

/**
 * Which body the device-detail screen shows. Crucially, an error (the peer is
 * offline or refused the connection) is NOT a terminal, action-free state: the
 * screen still shows the peer page, so "Unpair this device" and Back-to-list
 * stay available instead of being replaced by a bare "Failed to connect".
 */
enum class PeerDetailBody { LOADING, PEER_PAGE, SHARE_TREE }

fun peerDetailBody(loading: Boolean, openShare: Boolean): PeerDetailBody = when {
    loading -> PeerDetailBody.LOADING
    openShare -> PeerDetailBody.SHARE_TREE
    else -> PeerDetailBody.PEER_PAGE
}
