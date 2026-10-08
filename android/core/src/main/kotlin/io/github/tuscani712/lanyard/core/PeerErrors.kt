package io.github.tuscani712.lanyard.core

import com.google.gson.JsonParser

/**
 * The one place a peer's HTTP failure becomes a line a person should read.
 *
 * This mirrors the desktop's `peerapi.UserMessage` / `peerapi.IsNotPaired`:
 * a 403 is a pairing or permission refusal, never "could not reach device".
 * The peer's own explanation is kept when it is specific; the generic refusals
 * (`not paired`, `not permitted`, ...) become [NOT_PAIRED].
 */
object PeerErrors {
    /** Shown when a peer refuses an action with a generic 403. */
    const val NOT_PAIRED = "Not paired with this device."

    /**
     * The only reason that means the peer dropped the pairing. Match is exact
     * (case-insensitive): a peer that denies a permission answers with its own
     * wording, and that must never be read as an unpair.
     */
    private const val NOT_PAIRED_REASON = "not paired"

    /**
     * Display-only: 403 reasons that are generic refusals, so a person gets
     * [NOT_PAIRED] rather than a bare "forbidden". The permission refusals are
     * deliberately in this list — a peer that denies pull/push has not unpaired
     * us — but this set is **never** consulted by [isNotPaired].
     */
    private val genericForbidden = setOf(
        "not permitted",
        "push not permitted",
        "pull not permitted",
        "forbidden",
        "not paired",
    )

    /**
     * True only when [t] is a 403 whose reason is exactly "not paired"
     * (case-insensitive). A peer that answers this to a request from a device we
     * believe is paired has dropped the pairing on its side, so the stale local
     * entry may go.
     *
     * Empty, `forbidden`, and anything mentioning permissions return false: the
     * phone and its peer can each deny pull/push, and that is not an unpair.
     * This is the predicate the auto-unpair path uses; the wider
     * [genericForbidden] set is for [userMessage] wording only.
     */
    fun isNotPaired(t: Throwable?): Boolean {
        val e = t as? PeerStatusException ?: return false
        if (e.code != 403) return false
        return reason(e.body).equals(NOT_PAIRED_REASON, ignoreCase = true)
    }

    /**
     * Maps a peer-client failure to the line the UI shows. A 403 keeps the
     * peer's specific reason when it has one, and otherwise reads
     * [NOT_PAIRED]; a transport failure is passed through unchanged.
     */
    fun userMessage(t: Throwable?): String {
        if (t == null) return ""
        if (t !is PeerStatusException) return t.message ?: "Could not reach the device."
        val msg = reason(t.body)
        if (t.code == 403) {
            return if (msg.isNotEmpty() && !genericForbidden.contains(msg.lowercase())) msg else NOT_PAIRED
        }
        return msg.ifEmpty { "The other device answered with an error (HTTP ${t.code})." }
    }

    /** Extracts the `error` field of an error body, else the trimmed body. */
    private fun reason(body: String): String {
        val body = body.trim()
        if (body.isEmpty()) return ""
        val parsed = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
        val field = parsed?.get("error")?.takeIf { !it.isJsonNull }?.asString
        return (field ?: body).trim()
    }
}
