package io.github.tuscani712.lanyard.core

/**
 * Pure rules for receiving a push, mirroring the desktop's `internal/inbox` but
 * with phone-sized limits. Kept free of Android types so every rule is unit
 * testable.
 *
 * Deviations from the Go protocol (deliberate, phone memory limits):
 *  - offer body cap 8 MB (Go: 128 MB) and at most [MAX_OFFER_FILES] files
 *    (Go: 500 000); both answer 413.
 *  - free space must cover the spool **and** the destination copy, so the rule
 *    is total + largest single file + [FREE_SPACE_MARGIN].
 */
object PushProtocol {
    /** Most files in one offer accepted on the phone. */
    const val MAX_OFFER_FILES = 50_000

    /** Largest offer JSON body accepted on the phone. */
    const val MAX_OFFER_BODY_BYTES = 8 * 1024 * 1024

    /** Free space kept aside beyond the bytes a push needs. */
    const val FREE_SPACE_MARGIN = 64L * 1024 * 1024

    /** Longest single path segment accepted. */
    const val MAX_NAME_LENGTH = 255

    /**
     * The free space a push needs: every byte is spooled, then (one file at a
     * time) copied into the destination, so the peak is the whole spool plus one
     * destination file plus a margin.
     */
    fun requiredFreeSpace(total: Long, largestFile: Long, margin: Long = FREE_SPACE_MARGIN): Long =
        total + largestFile + margin

    /**
     * Canonical, safe relative path for a received file, or null if it must be
     * rejected. Rejects absolute paths, `..`/`.`/empty segments, backslashes,
     * NUL, `:` (drive/ADS), and segments longer than [MAX_NAME_LENGTH]; strips
     * control characters and trims trailing dots/spaces (which Windows rejects),
     * falling back to `_` for an empty segment.
     */
    fun sanitizeRel(rel: String): String? {
        if (rel.isEmpty()) return null
        if (rel.startsWith("/") || rel.startsWith("\\")) return null
        if (rel.indexOf('\u0000') >= 0 || rel.indexOf('\\') >= 0) return null
        val clean = rel.replace(Regex("/+"), "/")
        val segments = clean.split('/')
        val out = ArrayList<String>(segments.size)
        for (raw in segments) {
            if (raw.isEmpty() || raw == "." || raw == "..") return null
            if (raw.indexOf(':') >= 0) return null
            var s = raw.filter { !it.isISOControl() }.trimEnd('.', ' ')
            if (s.isEmpty()) s = "_"
            if (s.length > MAX_NAME_LENGTH) return null
            out.add(s)
        }
        return if (out.isEmpty()) null else out.joinToString("/")
    }

    /** Whether a manifest-relative path (used when serving) is safe to read. */
    fun isSafeRelPath(path: String): Boolean {
        if (path.isEmpty()) return true
        if (path.startsWith("/")) return false
        if (path.indexOf('\\') >= 0 || path.indexOf('\u0000') >= 0) return false
        return path.split('/').none { it.isEmpty() || it == "." || it == ".." || it.indexOf(':') >= 0 }
    }
}
