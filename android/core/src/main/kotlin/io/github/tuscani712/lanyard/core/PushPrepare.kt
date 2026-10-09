package io.github.tuscani712.lanyard.core

/**
 * The outcome of asking to start preparing a push (spooling the picked files).
 * Pure data so the dedupe rules are unit-testable without Android.
 */
sealed interface PrepareStart {
    /** No prepare is running for this peer; the caller owns the window under [key]. */
    data class Started(val key: String) : PrepareStart

    /** The same URI set to the same peer is already preparing: ignore the repeat. */
    data class Duplicate(val key: String) : PrepareStart

    /** A different send to the same peer is already preparing: block this one. */
    data class Busy(val key: String, val reason: String) : PrepareStart
}

/**
 * The prepare window for a push: the stretch between the file picker returning
 * and `enqueuePush` creating the real transfer row, during which the picked URIs
 * are copied into the spool. While a window is open the UI can show it (so the
 * screen is never blank) and Cancel can end it.
 *
 * The gate is the dedupe rule, kept pure so it is tested directly:
 *
 *  - **one prepare per peer** at a time. A second identical selection (same peer
 *    + same URI set, order-insensitive) is a [PrepareStart.Duplicate]; a
 *    different selection for the same peer is a [PrepareStart.Busy] and is
 *    refused with an explanation rather than silently queued twice.
 *  - a selection for a *different* peer is unaffected, so a send to another
 *    device is not held up.
 *
 * The key is derived from the peer fingerprint plus the URI set, so the same
 * files picked again map to the same window and the duplicate is recognised.
 */
// Opened on the UI thread and released from the spool coroutine (IO), so every
// access is synchronized: a lost update could leave a peer stuck "busy".
class PushPrepareGate {
    /** The open windows, keyed by key, remembering which peer each belongs to. */
    private val keys = HashSet<String>()
    private val peerKey = HashMap<String, String>()

    /** The stable, order-insensitive key for one selection. */
    fun key(peerFingerprint: String, uris: List<String>): String {
        val set = uris.asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()
            .sorted()
            .joinToString(SEPARATOR)
        return peerFingerprint.lowercase() + PEER_SEPARATOR + set
    }

    /**
     * Opens a prepare window for [uris] to the peer with [peerFingerprint], or
     * reports why it cannot be opened. On [PrepareStart.Started] the caller must
     * eventually call [end] with the returned key (on success, failure or cancel).
     */
    @Synchronized
    fun begin(peerFingerprint: String, uris: List<String>): PrepareStart {
        val peer = peerFingerprint.lowercase()
        val key = key(peer, uris)
        val open = peerKey[peer]
        return when {
            open == null -> {
                keys += key
                peerKey[peer] = key
                PrepareStart.Started(key)
            }
            open == key -> PrepareStart.Duplicate(key)
            else -> PrepareStart.Busy(key, BUSY_REASON)
        }
    }

    /** Closes the window opened for [key]. Idempotent: an unknown key is a no-op. */
    @Synchronized
    fun end(key: String) {
        if (keys.remove(key)) {
            peerKey.entries.removeAll { it.value == key }
        }
    }

    /** True while a prepare window is open for the peer with [peerFingerprint]. */
    @Synchronized
    fun isPreparing(peerFingerprint: String): Boolean = peerKey.containsKey(peerFingerprint.lowercase())

    /** How many prepare windows are open (all peers). */
    @Synchronized
    fun activeCount(): Int = keys.size

    companion object {
        /**
         * Shown when a different selection is made for a peer that is already
         * preparing one. Deliberately the same "not now, try again" shape as the
         * Wi-Fi-only refusal, so the UI surfaces it through the normal notice.
         */
        const val BUSY_REASON = "Preparing files for this device. Wait for it to finish, then try again."

        private const val PEER_SEPARATOR = "|"
        private const val SEPARATOR = "\u0000"
    }
}
