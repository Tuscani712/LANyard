package io.github.tuscani712.lanyard.core

/**
 * One action on the device page. The order of [DevicePage.ACTIONS] is the single
 * canonical order the UI renders on both platforms.
 */
enum class DeviceAction {
    SEND_FILES,
    SEND_FOLDER,
    SEND_TEXT,
    BROWSE_SHARES,
    RENAME,
    UNPAIR,
}

/** One editable permission row in the device page's "They can" block. */
enum class PermissionAction {
    BROWSE,
    PUSH,
    TEXT,
}

/**
 * An editable permission row: the action, its phrase, and the current tri-state
 * value from the same store as the Settings trust editor.
 */
data class PermissionRow(
    val action: PermissionAction,
    val label: String,
    val value: Permission,
)

/** Whether one action is enabled, and when not, the reason to show. */
data class DeviceActionState(
    val action: DeviceAction,
    val enabled: Boolean,
    val reason: String? = null,
)

/**
 * The device page's pure rules: the canonical action order/labels, the
 * enabled/disabled decision for each action (offline or a missing permission
 * leaves the control visible but disabled with a reason), the editable "They
 * can" permission rows, the read-only "They allow me" lines, and the
 * connection/status block. Free of Android types so every rule is unit testable,
 * and shared so both platforms render the same thing.
 */
object DevicePage {
    /** The canonical order of the device-page controls. */
    val ACTIONS: List<DeviceAction> = listOf(
        DeviceAction.SEND_FILES,
        DeviceAction.SEND_FOLDER,
        DeviceAction.SEND_TEXT,
        DeviceAction.BROWSE_SHARES,
        DeviceAction.RENAME,
        DeviceAction.UNPAIR,
    )

    /** The canonical label for [action]. */
    fun label(action: DeviceAction): String = when (action) {
        DeviceAction.SEND_FILES -> "Send files…"
        DeviceAction.SEND_FOLDER -> "Send folder…"
        DeviceAction.SEND_TEXT -> "Send text"
        DeviceAction.BROWSE_SHARES -> "Browse their shares"
        DeviceAction.RENAME -> "Rename…"
        DeviceAction.UNPAIR -> "Unpair this device"
    }

    /** The reason shown while a send is still being prepared. */
    const val PREPARING_REASON = "Wait for the current send to finish preparing."

    /** The reason shown when the peer is offline. */
    const val OFFLINE_REASON = "Offline"

    /** The reason shown when the peer has not allowed files from us. */
    const val NO_PUSH_REASON = "Has not allowed files from you"

    /** The reason shown when the peer does not let us browse its shares. */
    const val NO_BROWSE_REASON = "Has not allowed you to browse their shares"

    /**
     * The enabled/reason decision for one [action].
     *
     * [online] is the peer's last-known reachability. [canPush]/[canBrowse] are
     * what the peer allows us to do (the read-only "They allow me" direction).
     * [preparingFiles] is how many files are queued/spooling for a push to this
     * peer right now. Rename and Unpair are always available (they are local).
     */
    fun state(
        action: DeviceAction,
        online: Boolean,
        canPush: Boolean,
        canBrowse: Boolean,
        preparingFiles: Int = 0,
    ): DeviceActionState = when (action) {
        DeviceAction.RENAME, DeviceAction.UNPAIR -> DeviceActionState(action, true)
        DeviceAction.SEND_FILES, DeviceAction.SEND_FOLDER -> when {
            !online -> DeviceActionState(action, false, OFFLINE_REASON)
            !canPush -> DeviceActionState(action, false, NO_PUSH_REASON)
            preparingFiles > 0 -> DeviceActionState(action, false, PREPARING_REASON)
            else -> DeviceActionState(action, true)
        }
        DeviceAction.SEND_TEXT -> when {
            !online -> DeviceActionState(action, false, OFFLINE_REASON)
            !canPush -> DeviceActionState(action, false, NO_PUSH_REASON)
            else -> DeviceActionState(action, true)
        }
        DeviceAction.BROWSE_SHARES -> when {
            !online -> DeviceActionState(action, false, OFFLINE_REASON)
            !canBrowse -> DeviceActionState(action, false, NO_BROWSE_REASON)
            else -> DeviceActionState(action, true)
        }
    }

    /** Every action's state, in canonical order, for rendering. */
    fun states(
        online: Boolean,
        canPush: Boolean,
        canBrowse: Boolean,
        preparingFiles: Int = 0,
    ): List<DeviceActionState> =
        ACTIONS.map { state(it, online, canPush, canBrowse, preparingFiles) }

    /** The canonical label for one editable permission [action]. */
    fun permissionLabel(action: PermissionAction): String = when (action) {
        PermissionAction.BROWSE -> "browse your shares"
        PermissionAction.PUSH -> "send files to you"
        PermissionAction.TEXT -> "send text to you"
    }

    /**
     * The editable "They can" rows, in canonical order, from the local grant.
     * This is the same store the Settings trust editor writes.
     */
    fun theyCan(peer: PairedPeer): List<PermissionRow> = PermissionAction.entries.map { action ->
        PermissionRow(
            action = action,
            label = permissionLabel(action),
            value = when (action) {
                PermissionAction.BROWSE -> peer.browse
                PermissionAction.PUSH -> peer.push
                PermissionAction.TEXT -> peer.text
            },
        )
    }

    /** The two-word description of a tri-state value ("allow"/"ask"/"never"). */
    fun permissionWord(p: Permission): String = when (p) {
        Permission.ALLOW -> "allow"
        Permission.ASK -> "ask"
        Permission.NEVER -> "never"
    }

    /** The human phrase for one tri-state value. */
    fun permissionPhrase(p: Permission): String = when (p) {
        Permission.ALLOW -> "Allow without asking"
        Permission.ASK -> "Ask each time"
        Permission.NEVER -> "Never"
    }

    /**
     * The read-only "They allow me" lines, from what the peer granted us at
     * pairing. An empty list means the peer has not allowed us anything.
     */
    fun theyAllow(peer: PairedPeer): List<String> = buildList {
        if (peer.allowBrowse) add("browse their shares")
        if (peer.allowPush) add("send files to them")
        if (peer.allowText) add("send text to them")
    }

    /** The "They allow me" lines, with a fallback when empty. */
    fun theyAllowText(peer: PairedPeer): String =
        theyAllow(peer).ifEmpty { listOf("nothing yet") }.joinToString("; ")

    /**
     * The connection/status line: the connection status, the last-seen phrase and
     * the address (`host:port`). [now]/[lastSeenMillis] drive the last-seen text;
     * a non-positive [lastSeenMillis] means the peer has not been seen yet.
     */
    fun statusText(
        online: Boolean,
        host: String,
        port: Int,
        lastSeenMillis: Long,
        now: Long = System.currentTimeMillis(),
    ): String {
        val status = if (online) "Online" else "Offline"
        val seen = when {
            online -> "seen just now"
            lastSeenMillis <= 0 -> "not seen yet"
            else -> "last seen ${ago(now - lastSeenMillis)}"
        }
        val addr = if (host.isBlank()) "no address" else "$host:$port"
        return "$status · $seen · $addr"
    }

    /**
     * The header's status line: Online/Offline with the reason when offline, the
     * last-seen phrase, the `address:port`, and the short ID.
     */
    fun header(
        online: Boolean,
        host: String,
        port: Int,
        lastSeenMillis: Long,
        fingerprint: String,
        offlineReason: String? = null,
        now: Long = System.currentTimeMillis(),
    ): Header {
        val status = if (online) {
            "Online"
        } else {
            offlineReason?.takeIf { it.isNotBlank() }?.let { "Offline — $it" } ?: "Offline"
        }
        val seen = when {
            online -> "seen just now"
            lastSeenMillis <= 0 -> "not seen yet"
            else -> "last seen ${ago(now - lastSeenMillis)}"
        }
        val addr = if (host.isBlank()) "no address" else "$host:$port"
        return Header(status = status, lastSeen = seen, address = addr, shortId = Display.shortFp(fingerprint))
    }

    /** The pieces of the device-page header. */
    data class Header(
        val status: String,
        val lastSeen: String,
        val address: String,
        val shortId: String,
    )

    /** A compact "how long ago" phrase for the last-seen text. */
    fun ago(elapsedMillis: Long): String {
        val secs = (elapsedMillis / 1000).coerceAtLeast(0)
        return when {
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            secs < 86_400 -> "${secs / 3600}h ago"
            else -> "${secs / 86_400}d ago"
        }
    }
}
