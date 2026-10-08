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
}
