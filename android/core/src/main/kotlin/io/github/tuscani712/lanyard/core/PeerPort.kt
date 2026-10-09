package io.github.tuscani712.lanyard.core

/**
 * The user-owned peer listener port.
 *
 * A user-set value is [MIN]..[MAX]; [UNSET] (0) means "automatic", in which case
 * the listener fills in whatever port it actually bound. The port is user-owned:
 * once the person picks one, the app never overwrites it (not even with the port
 * it eventually bound, and not with a temporary fallback).
 */
object PeerPort {
    const val MIN = 1024
    const val MAX = 65535

    /** No choice: the listener picks its own port. */
    const val UNSET = 0

    /**
     * A clear error for [raw], or null when it is a valid port. A blank field is
     * valid and means [UNSET] (automatic), so clearing the field restores it.
     */
    fun error(raw: String): String? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val port = text.toIntOrNull() ?: return "Enter a port number between $MIN and $MAX."
        return if (port in MIN..MAX) null else "Port must be between $MIN and $MAX."
    }

    /**
     * The port [raw] names: [UNSET] for blank, the port when valid, or null when
     * [raw] is not a usable port (so a caller can leave the setting alone).
     */
    fun parse(raw: String): Int? {
        val text = raw.trim()
        if (text.isEmpty()) return UNSET
        val port = text.toIntOrNull() ?: return null
        return if (port in MIN..MAX) port else null
    }

    /**
     * What to persist after the listener bound [boundPort]:
     *  - a user-set [configured] port is never overwritten (it stays user-owned);
     *  - an unset one is filled with [boundPort] so a paired desktop's stored
     *    address stays valid;
     *  - a temporary fallback ([temporary]) is never persisted, so the next start
     *    tries the configured port again.
     */
    fun toPersist(configured: Int, boundPort: Int, temporary: Boolean): Int = when {
        configured != UNSET -> configured
        temporary -> UNSET
        else -> boundPort
    }
}

/**
 * Why the listener fell back to a temporary port this run: the configured port
 * was in use. [holder] names the process holding it when the OS let us
 * determine it.
 */
data class PortConflict(val configuredPort: Int, val holder: String? = null) {
    /**
     * "Using temporary port X because Y is in use", with the holding process
     * appended when it is known.
     */
    fun message(boundPort: Int): String =
        "Using temporary port $boundPort because $configuredPort is in use" +
            (holder?.takeIf { it.isNotBlank() }?.let { " (held by $it)" } ?: "")
}

/**
 * The current listener port, surfaced to Settings so it can explain a temporary
 * fallback. [temporary] is true only when a configured port was tried and could
 * not be bound.
 */
data class PeerPortStatus(
    val boundPort: Int,
    val configuredPort: Int,
    val temporary: Boolean,
    val holder: String? = null,
) {
    /** The "Using temporary port…" line, or null when the configured port was used. */
    fun message(): String? =
        if (temporary) PortConflict(configuredPort, holder).message(boundPort) else null
}
