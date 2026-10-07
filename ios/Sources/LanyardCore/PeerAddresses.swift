import Foundation

/// A discovered mDNS service's address, used to fill a paired peer's port.
struct DiscoveredAddr: Equatable {
    var shortId: String
    var host: String
    var port: Int
}

/// Reconciles paired peers with what discovery sees. When a desktop pairs *to*
/// the phone, the phone records it with no port (the pairing request carries
/// none). The address is filled from mDNS once the desktop is discovered, so the
/// phone can then reach it and not show it permanently offline.
enum PeerAddresses {
    /// The peers whose stored address should be updated: those with no port
    /// (`port <= 0`) whose short id matches a discovered service. Peers that
    /// already have a port are left alone. Returns only the peers that change.
    static func fillFromDiscovery(peers: [PairedPeer], discovered: [DiscoveredAddr]) -> [PairedPeer] {
        if discovered.isEmpty { return [] }
        var byShort: [String: DiscoveredAddr] = [:]
        for d in discovered {
            if d.shortId.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty || d.port <= 0 || d.port > 65535 {
                continue
            }
            byShort[short(d.shortId)] = d
        }
        if byShort.isEmpty { return [] }
        var updates: [PairedPeer] = []
        for p in peers {
            if p.port > 0 { continue }
            guard let d = byShort[short(p.fingerprint)] else { continue }
            if p.host == d.host && p.port == d.port { continue }
            var updated = p
            updated.host = d.host
            updated.port = d.port
            updates.append(updated)
        }
        return updates
    }

    private static func short(_ id: String) -> String {
        SelfFilter.ownShortId(id).lowercased()
    }
}
