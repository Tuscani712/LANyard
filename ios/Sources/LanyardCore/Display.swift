import Foundation

/// Safe display of attacker-controlled text and identifiers in the UI.
///
/// A paired-request name comes from the other device, so it is untrusted: it is
/// stripped of control characters and bidirectional-override characters (which
/// can visually reorder the rest of a line) and capped in length before it ever
/// reaches a dialog.
enum Display {
    /// Longest peer name kept for display, matching the desktop's cleanLabel.
    static let MAX_NAME = 64

    static func safeName(_ raw: String) -> String {
        var scalars = String.UnicodeScalarView()
        for s in raw.unicodeScalars {
            if s.properties.generalCategory == .control || isBidi(s) { continue }
            scalars.append(s)
        }
        let trimmed = String(scalars).trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.count <= MAX_NAME { return trimmed }
        return String(trimmed.prefix(MAX_NAME))
    }

    /// A fingerprint as uppercase groups of four, e.g. `4D63 5A83 F403 3D53 …`.
    static func groupedHex(_ fingerprint: String) -> String {
        let up = fingerprint.uppercased()
        var groups: [String] = []
        var idx = up.startIndex
        while idx < up.endIndex {
            let end = up.index(idx, offsetBy: 4, limitedBy: up.endIndex) ?? up.endIndex
            groups.append(String(up[idx..<end]))
            idx = end
        }
        return groups.joined(separator: " ")
    }

    private static func isBidi(_ s: Unicode.Scalar) -> Bool {
        let v = s.value
        return v == 0x061c || v == 0x200e || v == 0x200f ||
            (0x202a...0x202e).contains(v) || (0x2066...0x2069).contains(v)
    }
}
