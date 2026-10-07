import Foundation

/// One receiver row in the share picker, with a reason when it can't be chosen.
struct ShareTarget: Equatable {
    let peer: PairedPeer
    let enabled: Bool
    let reason: String?

    init(peer: PairedPeer, enabled: Bool, reason: String? = nil) {
        self.peer = peer
        self.enabled = enabled
        self.reason = reason
    }
}

/// The verdict for one incoming share URI, as plain data so it is testable.
struct UriVerdict: Equatable {
    let accepted: Bool
    let reason: String?

    init(accepted: Bool, reason: String? = nil) {
        self.accepted = accepted
        self.reason = reason
    }
}

/// One file in the share spool directory, for stale-file sweeping.
struct SpoolEntry: Equatable {
    let name: String
    let modifiedMillis: Int64
}

/// Rules for accepting a share intent's contents. The app supplies plain strings
/// and booleans, so none of this needs a platform type to test.
///
/// Only `content:` URIs are accepted. `file:` shares have been blocked from other
/// apps since Android 7, so one arriving here is almost certainly hostile; we
/// cannot tell a legitimate path from one that resolves into our own storage.
enum ShareValidation {
    /// The most items a single share may carry; the rest are dropped.
    static let MAX_ITEMS = 100

    /// Text above this is sent as a `.txt` file rather than a snippet.
    static let SNIPPET_MAX_BYTES = 64 * 1024

    /// Spooled share files older than this are swept at app start.
    static let SPOOL_TTL_MILLIS: Int64 = 60 * 60 * 1000

    static func validateUri(scheme: String?, authority: String?, readable: Bool) -> UriVerdict {
        if scheme?.lowercased() != "content" {
            return UriVerdict(accepted: false, reason: "Only items shared from other apps can be sent.")
        }
        if authority == nil || authority!.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return UriVerdict(accepted: false, reason: "The item has no provider.")
        }
        if !readable {
            return UriVerdict(accepted: false, reason: "The item could not be opened.")
        }
        return UriVerdict(accepted: true)
    }

    /// Returns how many of `count` items to keep, reporting whether any were cut.
    static func capItemCount(_ count: Int) -> Int {
        min(count, MAX_ITEMS)
    }

    /// Whether `text` is too large for a snippet and must go as a file.
    static func textExceedsSnippet(_ text: String) -> Bool {
        text.utf8.count > SNIPPET_MAX_BYTES
    }

    /// A safe file name for a shared item. Takes the last path segment, then
    /// rejects anything that is empty, `.`/`..`, or contains `\`, `:` or NUL,
    /// falling back to a neutral name.
    static func safeShareName(_ raw: String?) -> String {
        guard let raw else { return "shared-file" }
        let base = raw.contains("/") ? String(raw[raw.index(after: raw.lastIndex(of: "/")!)...]) : raw
        if base.isEmpty || base == "." || base == ".." { return "shared-file" }
        if base.contains("\\") || base.contains(":") || base.contains("\u{0000}") { return "shared-file" }
        return base
    }

    /// The spool files older than `ttlMillis` that should be deleted.
    static func staleSpoolFiles(_ entries: [SpoolEntry], now: Int64, ttlMillis: Int64 = SPOOL_TTL_MILLIS) -> [String] {
        entries.filter { now - $0.modifiedMillis > ttlMillis }.map { $0.name }
    }

    /// Builds the picker rows: online and permitted peers first as enabled.
    static func shareTargets(_ peers: [PairedPeer], onlineFingerprints: Set<String>) -> [ShareTarget] {
        let online = Set(onlineFingerprints.map { $0.lowercased() })
        return peers.map { peer in
            if !peer.push {
                return ShareTarget(peer: peer, enabled: false, reason: "Has not allowed files from you")
            }
            if !online.contains(peer.fingerprint.lowercased()) {
                return ShareTarget(peer: peer, enabled: false, reason: "Offline")
            }
            return ShareTarget(peer: peer, enabled: true)
        }
    }
}
