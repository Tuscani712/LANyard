import Foundation

/// Persists paired peers. Keyed by certificate fingerprint (lowercase hex).
public protocol TrustStore {
    func list() -> [PairedPeer]
    func find(_ fingerprint: String) -> PairedPeer?
    func save(_ peer: PairedPeer)
    func remove(_ fingerprint: String)
}

/// A `TrustStore` backed by a single JSON file. Writes go to a sibling temp file
/// and are then atomically renamed into place, so a crash mid-write can never
/// leave a half-written store. A missing or unreadable file reads as empty.
final class JsonFileTrustStore: TrustStore {
    private let url: URL
    private let encoder: JSONEncoder
    private let decoder = JSONDecoder()

    init(file: URL) {
        self.url = file
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        self.encoder = encoder
    }

    func list() -> [PairedPeer] {
        Array(read().values)
    }

    func find(_ fingerprint: String) -> PairedPeer? {
        read()[key(fingerprint)]
    }

    func save(_ peer: PairedPeer) {
        var normalized = peer
        normalized.fingerprint = key(peer.fingerprint)
        var peers = read()
        peers[normalized.fingerprint] = normalized
        write(peers)
    }

    func remove(_ fingerprint: String) {
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
        try? atomicWrite(data, to: url)
    }
}
