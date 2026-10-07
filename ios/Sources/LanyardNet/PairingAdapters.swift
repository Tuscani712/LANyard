// PairingAdapters.swift — the small Apple-only adapters the pairing UI needs
// that are not in LanyardCore's public surface.
//
// WRITTEN, NOT COMPILED. Guarded like its LanyardNet siblings so it compiles to
// nothing on Linux.
//
// Three things:
//   * `LanyardTrustStore` — a `TrustStore` backed by a JSON file. LanyardCore's
//     `JsonFileTrustStore` is internal to that module, so LanyardNet supplies its
//     own conformance (the protocol is public).
//   * `DeviceIdentity` — loads the Keychain identity via `SecIdentityFactory` and
//     exposes the certificate fingerprint (the "self fingerprint" the Devices
//     and pairing models filter/advertise by), plus the device's local IP
//     addresses for the invite link.
//   * `PairLinkBuilder` — renders a `lanyard://pair?...` invite link. LanyardCore's
//     `PairLink.build` is internal, so this mirrors its exact wire format.

#if canImport(Network) && canImport(Security)
import Foundation
import Security
import LanyardCore

// MARK: - Trust store

/// A `TrustStore` persisted as one JSON file. Writes are atomic so a crash cannot
/// leave a half-written store; a missing/unreadable file reads as empty. Mirrors
/// the behaviour of LanyardCore's internal `JsonFileTrustStore`.
public final class LanyardTrustStore: TrustStore {
    private let url: URL
    private let encoder: JSONEncoder
    private let decoder = JSONDecoder()

    public init(file: URL) {
        self.url = file
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        self.encoder = encoder
    }

    /// The default store: Application Support/peers.json. Application Support is
    /// used (not Cache) because a lost trust store means re-pairing.
    public static func applicationSupportStore() -> LanyardTrustStore {
        let root = FileManager.default.urls(for: .applicationSupportDirectory, in: .userDomainMask)[0]
        try? FileManager.default.createDirectory(at: root, withIntermediateDirectories: true)
        return LanyardTrustStore(file: root.appendingPathComponent("peers.json"))
    }

    public func list() -> [PairedPeer] {
        Array(read().values)
    }

    public func find(_ fingerprint: String) -> PairedPeer? {
        read()[key(fingerprint)]
    }

    public func save(_ peer: PairedPeer) {
        var normalized = peer
        normalized.fingerprint = key(peer.fingerprint)
        var peers = read()
        peers[normalized.fingerprint] = normalized
        write(peers)
    }

    public func remove(_ fingerprint: String) {
        var peers = read()
        if peers.removeValue(forKey: key(fingerprint)) != nil {
            write(peers)
        }
    }

    private func key(_ fingerprint: String) -> String {
        fingerprint.lowercased()
    }

    private func read() -> [String: PairedPeer] {
        guard let data = try? Data(contentsOf: url) else { return [:] }
        return (try? decoder.decode([String: PairedPeer].self, from: data)) ?? [:]
    }

    private func write(_ peers: [String: PairedPeer]) {
        guard let data = try? encoder.encode(peers) else { return }
        try? data.write(to: url, options: .atomic)
    }
}

// MARK: - Identity

/// The device's own identity, as the app layer needs it.
public struct DeviceIdentity {
    /// Lowercase hex SHA-256 of the certificate SPKI — the authoritative
    /// fingerprint used for self-filtering and advertising.
    public let fingerprint: String
    /// The `SecIdentity` the TLS layer uses.
    public let identity: SecIdentity

    public init(fingerprint: String, identity: SecIdentity) {
        self.fingerprint = fingerprint
        self.identity = identity
    }

    /// Loads the stored identity or creates one.
    public static func load() throws -> DeviceIdentity {
        let material = try SecIdentityFactory.loadOrCreate(
            deviceName: ProcessInfo.processInfo.hostName
        )
        return DeviceIdentity(fingerprint: material.deviceId, identity: material.identity)
    }

    /// The device's own non-loopback IP addresses, as host strings (no port).
    /// Used to fill the `addr=` parameter of the pairing invite link.
    ///
    /// MAC-SPIKE: `ifa_addr.pointee.sa_len` is the Darwin spelling; confirm on
    /// the SDK if this is ever built for another platform. IPv6 is skipped here;
    /// add it (bracketed, scope-stripped) if a v6-only network must pair.
    public static func localAddresses() -> [String] {
        var results: [String] = []
        var ifaddr: UnsafeMutablePointer<ifaddrs>?
        guard getifaddrs(&ifaddr) == 0, let first = ifaddr else { return results }
        defer { freeifaddrs(ifaddr) }

        var pointer: UnsafeMutablePointer<ifaddrs>? = first
        while let ifa = pointer {
            let flags = Int32(ifa.pointee.ifa_flags)
            if let addr = ifa.pointee.ifa_addr, addr.pointee.sa_family == UInt8(AF_INET) {
                let isUp = (flags & IFF_UP) != 0
                let isLoopback = (flags & IFF_LOOPBACK) != 0
                if isUp && !isLoopback {
                    var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
                    let length = socklen_t(addr.pointee.sa_len)
                    if getnameinfo(addr, length, &host, socklen_t(host.count), nil, 0, NI_NUMERICHOST) == 0 {
                        results.append(String(cString: host))
                    }
                }
            }
            pointer = ifa.pointee.ifa_next
        }
        return results
    }
}

// MARK: - Invite link

/// Renders a `lanyard://pair?fp=&name=&addr=&n=` link.
///
/// Renders an invite link. `PairLink` is public in LanyardCore, so this is a
/// thin, named convenience over `PairLink.build` (one builder, one wire format).
public enum PairLinkBuilder {
    public static func build(
        fingerprint: String,
        name: String,
        addresses: [String],
        nonce: String
    ) -> String {
        PairLink.build(
            PairLink.Payload(
                fingerprint: fingerprint,
                name: name,
                addrs: addresses,
                nonce: nonce
            )
        )
    }
}
#endif
