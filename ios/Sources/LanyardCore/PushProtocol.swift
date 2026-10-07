import Foundation

/// Pure rules for receiving a push, mirroring the desktop's `internal/inbox` but
/// with phone-sized limits. Kept free of platform types so every rule is unit
/// testable.
///
/// Deviations from the Go protocol (deliberate, phone memory limits):
///  - offer body cap 8 MB (Go: 128 MB) and at most `MAX_OFFER_FILES` files
///    (Go: 500 000); both answer 413.
///  - free space must cover the spool **and** the destination copy, so the rule
///    is total + largest single file + `FREE_SPACE_MARGIN`.
enum PushProtocol {
    /// Most files in one offer accepted on the phone.
    static let MAX_OFFER_FILES = 50_000

    /// Largest offer JSON body accepted on the phone.
    static let MAX_OFFER_BODY_BYTES = 8 * 1024 * 1024

    /// Free space kept aside beyond the bytes a push needs.
    static let FREE_SPACE_MARGIN: Int64 = 64 * 1024 * 1024

    /// Longest single path segment accepted.
    static let MAX_NAME_LENGTH = 255

    /// The free space a push needs: every byte is spooled, then (one file at a
    /// time) copied into the destination, so the peak is the whole spool plus one
    /// destination file plus a margin.
    static func requiredFreeSpace(total: Int64, largestFile: Int64, margin: Int64 = FREE_SPACE_MARGIN) -> Int64 {
        total + largestFile + margin
    }

    /// Canonical, safe relative path for a received file, or nil if it must be
    /// rejected. Rejects absolute paths, `..`/`.`/empty segments, backslashes,
    /// NUL, `:` (drive/ADS), and segments longer than `MAX_NAME_LENGTH`; strips
    /// control characters and trims trailing dots/spaces (which Windows rejects),
    /// falling back to `_` for an empty segment.
    static func sanitizeRel(_ rel: String) -> String? {
        if rel.isEmpty { return nil }
        if rel.hasPrefix("/") || rel.hasPrefix("\\") { return nil }
        if rel.contains("\u{0000}") || rel.contains("\\") { return nil }

        // Collapse runs of '/' to a single '/'.
        var clean = ""
        var prevSlash = false
        for c in rel {
            if c == "/" {
                if !prevSlash { clean.append(c) }
                prevSlash = true
            } else {
                clean.append(c)
                prevSlash = false
            }
        }

        var out: [String] = []
        for raw in clean.split(separator: "/", omittingEmptySubsequences: false) {
            let seg = String(raw)
            if seg.isEmpty || seg == "." || seg == ".." { return nil }
            if seg.contains(":") { return nil }
            var s = String(seg.filter { !isISOControl($0) })
            s = String(s.reversed().drop { $0 == "." || $0 == " " }.reversed())
            if s.isEmpty { s = "_" }
            if s.count > MAX_NAME_LENGTH { return nil }
            out.append(s)
        }
        return out.isEmpty ? nil : out.joined(separator: "/")
    }

    /// Whether a manifest-relative path (used when serving) is safe to read.
    static func isSafeRelPath(_ path: String) -> Bool {
        if path.isEmpty { return true }
        if path.hasPrefix("/") { return false }
        if path.contains("\\") || path.contains("\u{0000}") { return false }
        return !path.split(separator: "/", omittingEmptySubsequences: false).contains {
            $0.isEmpty || $0 == "." || $0 == ".." || $0.contains(":")
        }
    }

    /// Kotlin `Char.isISOControl`: U+0000..U+001F and U+007F..U+009F.
    private static func isISOControl(_ c: Character) -> Bool {
        guard c.unicodeScalars.count == 1, let v = c.unicodeScalars.first?.value else { return false }
        return (0x00...0x1F).contains(v) || (0x7F...0x9F).contains(v)
    }
}
