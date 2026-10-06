package io.github.tuscani712.lanyard.core

/** One receiver row in the share picker, with a reason when it can't be chosen. */
data class ShareTarget(
    val peer: PairedPeer,
    val enabled: Boolean,
    val reason: String? = null,
)

/** The verdict for one incoming share URI, as plain data so it is testable. */
data class UriVerdict(val accepted: Boolean, val reason: String? = null)

/** One file in the share spool directory, for stale-file sweeping. */
data class SpoolEntry(val name: String, val modifiedMillis: Long)

/**
 * Rules for accepting a share intent's contents. The app supplies plain strings
 * and booleans, so none of this needs an Android type to test.
 *
 * Only `content:` URIs are accepted. `file:` shares have been blocked from other
 * apps since Android 7, so one arriving here is almost certainly hostile; we
 * cannot tell a legitimate path from one that resolves into our own storage.
 */
object ShareValidation {
    /** The most items a single share may carry; the rest are dropped. */
    const val MAX_ITEMS = 100

    /** Text above this is sent as a `.txt` file rather than a snippet. */
    const val SNIPPET_MAX_BYTES = 64 * 1024

    /** Spooled share files older than this are swept at app start. */
    const val SPOOL_TTL_MILLIS = 60L * 60 * 1000

    fun validateUri(scheme: String?, authority: String?, readable: Boolean): UriVerdict = when {
        !scheme.equals("content", ignoreCase = true) ->
            UriVerdict(false, "Only items shared from other apps can be sent.")
        authority.isNullOrBlank() ->
            UriVerdict(false, "The item has no provider.")
        !readable ->
            UriVerdict(false, "The item could not be opened.")
        else -> UriVerdict(true)
    }

    /** Returns how many of [count] items to keep, reporting whether any were cut. */
    fun capItemCount(count: Int): Int = count.coerceAtMost(MAX_ITEMS)

    /** Whether [text] is too large for a snippet and must go as a file. */
    fun textExceedsSnippet(text: String): Boolean = text.toByteArray(Charsets.UTF_8).size > SNIPPET_MAX_BYTES

    /**
     * A safe file name for a shared item. Takes the last path segment, then
     * rejects anything that is empty, `.`/`..`, or contains `\`, `:` or NUL,
     * falling back to a neutral name.
     */
    fun safeShareName(raw: String?): String {
        val base = raw?.substringAfterLast('/') ?: return "shared-file"
        if (base.isEmpty() || base == "." || base == "..") return "shared-file"
        if (base.any { it == '\\' || it == ':' || it == '\u0000' }) return "shared-file"
        return base
    }

    /** The spool files older than [ttlMillis] that should be deleted. */
    fun staleSpoolFiles(
        entries: List<SpoolEntry>,
        now: Long,
        ttlMillis: Long = SPOOL_TTL_MILLIS,
    ): List<String> = entries.filter { now - it.modifiedMillis > ttlMillis }.map { it.name }

    /** Builds the picker rows: online and permitted peers first as enabled. */
    fun shareTargets(peers: List<PairedPeer>, onlineFingerprints: Set<String>): List<ShareTarget> {
        val online = onlineFingerprints.mapTo(HashSet()) { it.lowercase() }
        return peers.map { peer ->
            when {
                !peer.push -> ShareTarget(peer, enabled = false, reason = "Has not allowed files from you")
                peer.fingerprint.lowercase() !in online -> ShareTarget(peer, enabled = false, reason = "Offline")
                else -> ShareTarget(peer, enabled = true)
            }
        }
    }
}
