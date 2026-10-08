package io.github.tuscani712.lanyard.core

/**
 * Unpairing a peer and the wording for the one outcome a person needs to know
 * about: when the local entry is gone but the peer could not be told, the other
 * side may still list this device, so it must not be silent.
 */
object Unpair {
    /**
     * Shown when the pairing was removed on this device but the peer could not be
     * reached to drop its own copy, so it may still show this device as paired.
     */
    const val REMOTE_NOT_NOTIFIED = "Removed here. The desktop could not be notified."
}
