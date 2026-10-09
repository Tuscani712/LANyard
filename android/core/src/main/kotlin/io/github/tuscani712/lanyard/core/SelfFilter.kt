package io.github.tuscani712.lanyard.core

/**
 * Discovery helpers kept pure so the "is this me?" rule can be unit-tested
 * without Android.
 *
 * mDNS gives each device a short id: the first 16 hex characters of its
 * certificate fingerprint. A phone that hears its own advertisement must not
 * list itself as a nearby device.
 */
object SelfFilter {
    /** The short id a device advertises for itself, from its full Device ID. */
    fun ownShortId(deviceId: String): String = deviceId.trim().take(16)

    /**
     * True when [shortId] is this device's own advertised id. A blank
     * [ownShortId] (identity not loaded yet) matches nothing, so no real device
     * is ever hidden by mistake.
     */
    fun isSelf(shortId: String, ownShortId: String): Boolean =
        ownShortId.isNotBlank() && shortId.equals(ownShortId, ignoreCase = true)
}
