package io.github.tuscani712.lanyard.core

/**
 * Safe display of attacker-controlled text and identifiers in the UI.
 *
 * A paired-request name comes from the other device, so it is untrusted: it is
 * stripped of control characters and bidirectional-override characters (which
 * can visually reorder the rest of a line) and capped in length before it ever
 * reaches a dialog.
 */
object Display {
    /** Longest peer name kept for display, matching the desktop's cleanLabel. */
    const val MAX_NAME = 64

    fun safeName(raw: String): String {
        val sb = StringBuilder(raw.length)
        for (ch in raw) {
            if (ch.isISOControl() || isBidi(ch)) continue
            sb.append(ch)
        }
        val trimmed = sb.toString().trim()
        return if (trimmed.length <= MAX_NAME) trimmed else trimmed.substring(0, MAX_NAME)
    }

    /** A fingerprint as uppercase groups of four, e.g. `4D63 5A83 F403 3D53 …`. */
    fun groupedHex(fingerprint: String): String =
        fingerprint.uppercase().chunked(4).joinToString(" ")

    private fun isBidi(ch: Char): Boolean =
        ch == '\u061c' || ch == '\u200e' || ch == '\u200f' ||
            ch in '\u202a'..'\u202e' || ch in '\u2066'..'\u2069'
}
