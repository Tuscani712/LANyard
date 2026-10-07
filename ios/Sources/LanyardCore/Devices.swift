import Foundation

/// A device seen over mDNS or the UDP beacon, before it is merged into the
/// devices list. Mirrors the Kotlin `NearbyDevice` plus the last-seen stamp the
/// Go `discovery.Peer` registry keeps.
public struct DiscoveredPeer: Equatable {
    public var shortId: String
    public var name: String
    public var os: String
    public var deviceLabel: String
    public var host: String
    public var port: Int
    /// When this announcement was last refreshed, in the caller's clock.
    public var lastSeen: Int64

    public init(
        shortId: String,
        name: String = "",
        os: String = "",
        deviceLabel: String = "",
        host: String = "",
        port: Int = 0,
        lastSeen: Int64 = 0
    ) {
        self.shortId = shortId
        self.name = name
        self.os = os
        self.deviceLabel = deviceLabel
        self.host = host
        self.port = port
        self.lastSeen = lastSeen
    }
}

/// One row of the Devices list: a paired peer or a discovered device, with its
/// reachability resolved.
public enum DeviceStatus: String, Equatable, CaseIterable, Sendable {
    /// Paired and currently reachable.
    case paired
    /// Discovered but not paired.
    case online
    /// Paired but not currently reachable.
    case offline
}

/// A single row of the Devices list, ready for the UI.
public struct DeviceRow: Equatable, Identifiable {
    /// The advertised short id (first 16 hex of the fingerprint when paired).
    public let shortId: String
    /// The full certificate fingerprint, present only for a paired peer.
    public let fingerprint: String?
    public let name: String
    public let os: String
    public let deviceLabel: String
    public let host: String
    public let port: Int
    public let status: DeviceStatus
    public let isPaired: Bool
    /// The paired peer's browse (pull) permission; false for an unpaired device.
    public let browse: Bool
    /// The paired peer's push permission; false for an unpaired device.
    public let push: Bool
    public let lastSeen: Int64

    public init(
        shortId: String,
        fingerprint: String? = nil,
        name: String,
        os: String = "",
        deviceLabel: String = "",
        host: String = "",
        port: Int = 0,
        status: DeviceStatus,
        isPaired: Bool,
        browse: Bool = false,
        push: Bool = false,
        lastSeen: Int64 = 0
    ) {
        self.shortId = shortId
        self.fingerprint = fingerprint
        self.name = name
        self.os = os
        self.deviceLabel = deviceLabel
        self.host = host
        self.port = port
        self.status = status
        self.isPaired = isPaired
        self.browse = browse
        self.push = push
        self.lastSeen = lastSeen
    }

    /// Stable identity for SwiftUI lists: the full fingerprint when paired, else
    /// the short id.
    public var id: String { fingerprint ?? shortId }

    /// One-off **Connect** is offered to a device that was seen but is not paired
    /// (spec §4.2); an offline peer cannot be connected to.
    public var connectEnabled: Bool { !isPaired && status != .offline }

    /// **Browse** is offered to a paired, online peer that granted the browse
    /// permission (spec §4.3); an offline peer's shares are unreachable.
    public var browseEnabled: Bool { isPaired && status == .paired && browse }
}

/// The devices list model: it merges discovered peers, the trust store and the
/// online set into rows, filtering out this device by certificate fingerprint.
///
/// This is the pure half of the Android `DevicesViewModel`: the stateful
/// discovery, probing and store IO live in the app / `LanyardNet`; the mapping
/// to rows is a function of its inputs.
public enum Devices {
    /// Builds the ordered rows.
    ///
    /// - `discovered`: current mDNS/beacon peers.
    /// - `paired`: entries from the `TrustStore`.
    /// - `onlineFingerprints`: fingerprints (or their short ids) currently
    ///   reachable.
    /// - `now`: the caller's clock, for last-seen bookkeeping.
    /// - `selfFingerprint`: this device's full certificate fingerprint, used to
    ///   drop its own advertisement and any self-entry in the trust store. Never
    ///   filter by hostname or by the Device ID label (spec §5.1).
    ///
    /// Ordering is deterministic: paired first, then online, then offline; ties
    /// by case-insensitive name, then by short id.
    public static func build(
        discovered: [DiscoveredPeer],
        paired: [PairedPeer],
        onlineFingerprints: Set<String>,
        now: Int64,
        selfFingerprint: String = ""
    ) -> [DeviceRow] {
        let ownShort = DiscoveryTxt.shortId(selfFingerprint).lowercased()
        let online = Set(onlineFingerprints.map { $0.lowercased() })

        func isOnline(_ fingerprint: String) -> Bool {
            let fp = fingerprint.lowercased()
            if online.contains(fp) { return true }
            let short = DiscoveryTxt.shortId(fp).lowercased()
            return !short.isEmpty && online.contains(short)
        }

        // Latest announcement per short id, with this device's own record dropped.
        var discoveredByShort: [String: DiscoveredPeer] = [:]
        for device in discovered {
            let key = device.shortId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            if key.isEmpty { continue }
            if !ownShort.isEmpty && key == ownShort { continue }
            if let existing = discoveredByShort[key], existing.lastSeen > device.lastSeen { continue }
            discoveredByShort[key] = device
        }

        var rows: [DeviceRow] = []
        var pairedShorts: Set<String> = []

        for peer in paired {
            let fp = peer.fingerprint.lowercased()
            let short = DiscoveryTxt.shortId(fp).lowercased()
            if !ownShort.isEmpty && !short.isEmpty && short == ownShort { continue }
            if !short.isEmpty { pairedShorts.insert(short) }

            let match = discoveredByShort[short]
            let onlineNow = isOnline(peer.fingerprint)
            rows.append(
                DeviceRow(
                    shortId: match?.shortId ?? DiscoveryTxt.shortId(peer.fingerprint),
                    fingerprint: peer.fingerprint,
                    name: firstNonEmpty(match?.name, peer.name),
                    os: match?.os ?? "",
                    deviceLabel: firstNonEmpty(match?.deviceLabel, nil),
                    host: firstNonEmpty(match?.host, peer.host),
                    port: match.map { $0.port > 0 ? $0.port : peer.port } ?? peer.port,
                    status: onlineNow ? .paired : .offline,
                    isPaired: true,
                    browse: peer.browse,
                    push: peer.push,
                    lastSeen: match?.lastSeen ?? peer.pairedAt
                )
            )
        }

        for (key, device) in discoveredByShort where !pairedShorts.contains(key) {
            rows.append(
                DeviceRow(
                    shortId: device.shortId,
                    fingerprint: nil,
                    name: device.name,
                    os: device.os,
                    deviceLabel: device.deviceLabel,
                    host: device.host,
                    port: device.port,
                    status: .online,
                    isPaired: false,
                    browse: false,
                    push: false,
                    lastSeen: device.lastSeen
                )
            )
        }

        rows.sort { a, b in
            let ra = rank(a.status), rb = rank(b.status)
            if ra != rb { return ra < rb }
            let na = a.name.lowercased(), nb = b.name.lowercased()
            if na != nb { return na < nb }
            return a.shortId < b.shortId
        }
        _ = now
        return rows
    }

    /// Drops this device's own record from a discovered list, by certificate
    /// fingerprint. An unknown self fingerprint hides nothing.
    public static func selfFilter(_ discovered: [DiscoveredPeer], selfFingerprint: String) -> [DiscoveredPeer] {
        let ownShort = DiscoveryTxt.shortId(selfFingerprint).lowercased()
        if ownShort.isEmpty { return discovered }
        return discovered.filter {
            $0.shortId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() != ownShort
        }
    }

    private static func rank(_ status: DeviceStatus) -> Int {
        switch status {
        case .paired: return 0
        case .online: return 1
        case .offline: return 2
        }
    }

    private static func firstNonEmpty(_ values: String?...) -> String {
        for value in values {
            if let value, !value.isEmpty { return value }
        }
        return ""
    }
}

/// Last-seen tracking and eviction for discovered peers, ported from the Go
/// `discovery.registry` (`registry.go`) plus the spec §5.1 eviction rule:
///
///  - an announcement refreshes `lastSeen` and clears the miss counter;
///  - two consecutive missed probes mark a peer offline (Go deletes at this
///    point; the spec keeps it listed, greyed, for a grace period);
///  - a peer not refreshed for 2× the TTL is marked offline;
///  - offline peers are removed after the grace period;
///  - a clean goodbye (TTL 0 / `remove`) drops the peer at once.
///
/// The registry is keyed by the short id, exactly like the Go map.
public final class DeviceRegistry {
    /// The assumed mDNS record TTL.
    public let ttlMillis: Int64
    /// How long an offline peer is kept before it is removed.
    public let removeGraceMillis: Int64
    /// Consecutive failed probes that mark a peer offline.
    public let maxMisses: Int

    public struct Entry: Equatable {
        public let shortId: String
        public var lastSeen: Int64
        public var misses: Int
        public var online: Bool

        public init(shortId: String, lastSeen: Int64, misses: Int, online: Bool) {
            self.shortId = shortId
            self.lastSeen = lastSeen
            self.misses = misses
            self.online = online
        }
    }

    private var entries: [String: Entry] = [:]
    private let lock = NSLock()

    public init(ttlMillis: Int64 = 120_000, removeGraceMillis: Int64 = 60_000, maxMisses: Int = 2) {
        self.ttlMillis = ttlMillis
        self.removeGraceMillis = removeGraceMillis
        self.maxMisses = max(1, maxMisses)
    }

    /// Records an announcement, refreshing `lastSeen` and clearing misses.
    public func observe(_ shortId: String, now: Int64) {
        let key = normalize(shortId)
        guard !key.isEmpty else { return }
        lock.lock(); defer { lock.unlock() }
        if var entry = entries[key] {
            entry.lastSeen = now
            entry.misses = 0
            entry.online = true
            entries[key] = entry
        } else {
            entries[key] = Entry(shortId: shortId, lastSeen: now, misses: 0, online: true)
        }
    }

    /// Records a failed probe; at `maxMisses` the peer is marked offline.
    public func miss(_ shortId: String, now: Int64) {
        let key = normalize(shortId)
        lock.lock(); defer { lock.unlock() }
        guard var entry = entries[key] else { return }
        entry.misses += 1
        if entry.misses >= maxMisses { entry.online = false }
        // Touch lastSeen so the removal grace is measured from the last attempt.
        entry.lastSeen = max(entry.lastSeen, now)
        entries[key] = entry
    }

    /// A clean goodbye: drop the peer immediately.
    public func remove(_ shortId: String) {
        let key = normalize(shortId)
        lock.lock(); defer { lock.unlock() }
        entries.removeValue(forKey: key)
    }

    public func removeAll() {
        lock.lock(); defer { lock.unlock() }
        entries.removeAll()
    }

    /// Ages out stale peers: refresh older than 2× the TTL goes offline; an
    /// offline peer older than the grace is dropped. Returns the surviving peers.
    @discardableResult
    public func sweep(now: Int64) -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        let offlineAfter = ttlMillis * 2
        let removeAfter = offlineAfter + removeGraceMillis
        for (key, entry) in entries {
            let age = now - entry.lastSeen
            if age > removeAfter {
                entries.removeValue(forKey: key)
                continue
            }
            if age > offlineAfter {
                var updated = entry
                updated.online = false
                entries[key] = updated
            }
        }
        return listLocked()
    }

    public func entry(_ shortId: String) -> Entry? {
        let key = normalize(shortId)
        lock.lock(); defer { lock.unlock() }
        return entries[key]
    }

    /// The short ids currently believed online.
    public func onlineShortIds() -> Set<String> {
        lock.lock(); defer { lock.unlock() }
        return Set(entries.values.filter(\.online).map { $0.shortId })
    }

    /// All entries, ordered by short id for determinism.
    public func list() -> [Entry] {
        lock.lock(); defer { lock.unlock() }
        return listLocked()
    }

    private func listLocked() -> [Entry] {
        entries.values.sorted { $0.shortId.lowercased() < $1.shortId.lowercased() }
    }

    private func normalize(_ shortId: String) -> String {
        shortId.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
    }
}
