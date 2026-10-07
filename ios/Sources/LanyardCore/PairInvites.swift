import Foundation

/// One-time QR pairing invites minted by this device, mirroring the desktop's
/// `trust.Store` invite handling:
///
///  - 128 bits of randomness, hex-encoded;
///  - valid for `ttlMillis` (2 minutes by default);
///  - consumed on the first use, valid or not, so a reused or expired invite can
///    never be retried or downgraded to the SAS path;
///  - compared in constant time.
///
/// `valid(_:)` is the non-consuming lookup used by the QR screen so it can keep
/// showing the same code until it expires or is used.
final class PairInvites {
    struct Invite: Equatable {
        let token: String
        let expiresAt: Int64
    }

    private let ttlMillis: Int64
    private let clock: () -> Int64
    private let lock = NSLock()
    private var invites: [String: Int64] = [:]

    init(ttlMillis: Int64 = 2 * 60 * 1000, clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }) {
        self.ttlMillis = ttlMillis
        self.clock = clock
    }

    @discardableResult
    func mint() -> Invite {
        lock.lock(); defer { lock.unlock() }
        sweep()
        var rng = SystemRandomNumberGenerator()
        var bytes = [UInt8]()
        bytes.reserveCapacity(16)
        for _ in 0..<16 { bytes.append(UInt8.random(in: 0...255, using: &rng)) }
        let token = Hex.encode(bytes)
        let expiresAt = clock() + ttlMillis
        invites[token] = expiresAt
        return Invite(token: token, expiresAt: expiresAt)
    }

    /// The expiry of a still-valid invite, or nil if unknown, used or expired.
    func valid(_ token: String) -> Int64? {
        if token.isEmpty { return nil }
        lock.lock(); defer { lock.unlock() }
        sweep()
        guard let exp = invites[token] else { return nil }
        if clock() < exp { return exp }
        invites.removeValue(forKey: token)
        return nil
    }

    /// Consumes `token` on the first attempt; true only if it was valid.
    func consume(_ token: String) -> Bool {
        if token.isEmpty { return false }
        lock.lock(); defer { lock.unlock() }
        sweep()
        var found: String? = nil
        var exp: Int64 = 0
        let want = Array(token.utf8)
        for (t, e) in invites {
            if constantTimeEquals(Array(t.utf8), want) {
                found = t
                exp = e
            }
        }
        guard let match = found else { return false }
        invites.removeValue(forKey: match)
        return clock() < exp
    }

    private func sweep() {
        let now = clock()
        invites = invites.filter { now < $0.value }
    }

    /// Byte-wise comparison whose running time does not depend on where the
    /// first differing byte is, matching `MessageDigest.isEqual`.
    private func constantTimeEquals(_ a: [UInt8], _ b: [UInt8]) -> Bool {
        if a.count != b.count { return false }
        var diff: UInt8 = 0
        for i in 0..<a.count {
            diff |= a[i] ^ b[i]
        }
        return diff == 0
    }
}
