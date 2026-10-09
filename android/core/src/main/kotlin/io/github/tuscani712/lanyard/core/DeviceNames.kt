package io.github.tuscani712.lanyard.core

/**
 * Local, person-chosen device names (aliases).
 *
 * An alias is stored only in this device's trust store and is never sent on the
 * wire: pairing, discovery and every request keep using the broadcast name, so
 * an alias can never shadow or overwrite what the other device calls itself.
 * Display falls back to the broadcast name when no alias is set.
 */
object DeviceNames {
    /** A safe, person-readable name for [name]/[alias], never blank. */
    fun display(name: String, alias: String): String {
        val cleanAlias = Display.safeName(alias)
        if (cleanAlias.isNotBlank()) return cleanAlias
        val cleanName = Display.safeName(name)
        return cleanName.ifBlank { "Unnamed device" }
    }

    /** The display name for a paired [peer], alias first. */
    fun display(peer: PairedPeer): String = display(peer.name, peer.alias)

    /**
     * A diagnostics/log label for a peer: the display name plus a short
     * fingerprint, so a log can name the device without revealing the full
     * fingerprint. Never carries the alias on the wire; this is local only.
     */
    fun logLabel(name: String, alias: String, fingerprint: String): String =
        "${display(name, alias)} (${Display.shortFp(fingerprint)})"

    /** The log label for a paired [peer]. */
    fun logLabel(peer: PairedPeer): String = logLabel(peer.name, peer.alias, peer.fingerprint)

    /**
     * Whether [alias] may be stored: it is trimmed and bounded like any other
     * display name. An empty or whitespace-only alias clears the local name and
     * falls back to the broadcast name.
     */
    fun normalizeAlias(raw: String): String = Display.safeName(raw).trim()
}
