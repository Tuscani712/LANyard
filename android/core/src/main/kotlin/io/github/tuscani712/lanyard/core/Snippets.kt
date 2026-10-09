package io.github.tuscani712.lanyard.core

import java.security.SecureRandom

/**
 * One received text snippet, as stored on the phone. Mirrors the desktop's
 * `inbox.Snippet` closely enough for the wire: `id`, the sending peer's
 * fingerprint and the text itself. The text is only ever shown to the person,
 * never written to a file.
 */
data class ReceivedSnippet(
    val id: String,
    val peerFingerprint: String,
    val text: String,
    val receivedAt: Long,
)

/**
 * The rules for accepting a text snippet, mirroring the desktop's
 * `inbox.ValidateSnippet`: non-empty, within [MAX_SNIPPET_BYTES] of UTF-8, and
 * free of control characters other than newline and tab.
 */
object SnippetProtocol {
    /** The largest text accepted (64 KB of UTF-8), matching the desktop cap. */
    const val MAX_SNIPPET_BYTES = 64 * 1024

    /**
     * The largest JSON request body accepted for `POST /snippet`. JSON escaping
     * can inflate the text (a `\uXXXX` escape is six bytes for one byte), so the
     * body cap allows the same headroom the desktop uses (`*2 + 4096`).
     */
    const val MAX_BODY_BYTES = MAX_SNIPPET_BYTES * 2 + 4096

    /** Null when [text] is a valid snippet, else a person-readable refusal. */
    fun validate(text: String): String? {
        if (text.isEmpty()) return "snippet is empty"
        if (text.toByteArray(Charsets.UTF_8).size > MAX_SNIPPET_BYTES) {
            return "snippet exceeds $MAX_SNIPPET_BYTES bytes"
        }
        for (r in text) {
            if (r.isISOControl() && r != '\n' && r != '\t') return "snippet contains control characters"
        }
        return null
    }
}

/**
 * A small, bounded, in-memory store of snippets received from paired peers. The
 * phone had no landing spot for inbound text before this: a desktop's
 * `POST /snippet` was answered 404 and silently dropped. The Transfers screen
 * shows these with a Copy button.
 */
class ReceivedSnippets(
    private val cap: Int = DEFAULT_CAP,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val items = ArrayDeque<ReceivedSnippet>()
    private val random = SecureRandom()

    /** Stores [text] from [peerFingerprint] and returns the stored snippet. */
    @Synchronized
    fun add(peerFingerprint: String, text: String): ReceivedSnippet {
        val snippet = ReceivedSnippet(newId(), peerFingerprint, text, clock())
        items.addFirst(snippet)
        while (items.size > cap) items.removeLast()
        return snippet
    }

    /** Every stored snippet, newest first. */
    @Synchronized
    fun list(): List<ReceivedSnippet> = items.toList()

    /** Removes the snippet with [id]; true when one was present. */
    @Synchronized
    fun dismiss(id: String): Boolean {
        val existing = items.firstOrNull { it.id == id } ?: return false
        items.remove(existing)
        return true
    }

    @Synchronized
    fun clear() = items.clear()

    private fun newId(): String {
        val b = ByteArray(6)
        random.nextBytes(b)
        return "s_" + b.joinToString("") { "%02x".format(it) }
    }

    companion object {
        /** How many received snippets are kept before the oldest is dropped. */
        const val DEFAULT_CAP = 100
    }
}
