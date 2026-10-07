import Foundation

/// What a status poll returns to the initiating device.
///
/// Ported from the Kotlin `SessionView` in `PairingSessions.kt`.
struct SessionView: Equatable {
    let id: String
    let status: String
    let mode: String
    let nonce: String
    let granted: Permissions
    let sas: String
    let error: String
}

/// One incoming pairing request, as the phone's dialog shows it.
///
/// Ported from the Kotlin `IncomingRequest` in `PairingSessions.kt`.
struct IncomingRequest: Equatable {
    let id: String
    let peerFp: String
    let peerName: String
    let peerDevice: String
    let mode: String
    let requested: Permissions
    let viaQr: Bool
    let sas: String
    let createdAt: Int64
}

/// The responder side of the pairing state machine, mirroring `internal/trust`:
/// a request is recorded as pending, the person accepts or declines, and only
/// when the initiator confirms is the pairing written to the `TrustStore`. A
/// cancelled or unconfirmed pairing therefore leaves nothing behind on either
/// side.
///
/// Every method is serialized on a recursive lock; the server calls them from
/// worker threads and the UI from the main thread. The original Kotlin methods
/// are `@Synchronized`, which is reentrant, so a recursive lock preserves the
/// same reentrancy for `onChange` callbacks.
///
/// Ported from the Kotlin `PairingSessions` in `PairingSessions.kt`.
final class PairingSessions {
    static let MODE_CONNECT = "connect"
    static let MODE_PAIR = "pair"

    static let STATUS_PENDING = "pending"
    static let STATUS_ACCEPTED = "accepted"
    static let STATUS_ACTIVE = "active"
    static let STATUS_REJECTED = "rejected"
    static let STATUS_CLOSED = "closed"
    static let STATUS_EXPIRED = "expired"

    static let PAIRING_TTL_MS: Int64 = 2 * 60 * 1000
    // Terminal sessions (rejected/closed/expired) are dropped this long after
    // their last update, so they cannot accumulate toward MAX_SESSIONS.
    static let TERMINAL_TTL_MS: Int64 = 5 * 60 * 1000
    // An active session nobody closes is closed after this long.
    static let SESSION_INACTIVITY_MS: Int64 = 15 * 60 * 1000
    static let MAX_SESSIONS = 64
    static let MAX_PENDING_PER_PEER = 1
    static let MAX_PENDING_TOTAL = 3

    private final class Session {
        let id: String
        let mode: String
        let peerFp: String
        let peerName: String
        let peerDevice: String
        let peerHost: String
        let selfNonce: String
        let peerNonce: String
        let requested: Permissions
        let viaQr: Bool
        var status: String
        var granted: Permissions
        var error: String
        let createdAt: Int64
        var updatedAt: Int64

        init(
            id: String,
            mode: String,
            peerFp: String,
            peerName: String,
            peerDevice: String,
            peerHost: String,
            selfNonce: String,
            peerNonce: String,
            requested: Permissions,
            viaQr: Bool,
            status: String,
            granted: Permissions,
            error: String,
            createdAt: Int64,
            updatedAt: Int64
        ) {
            self.id = id
            self.mode = mode
            self.peerFp = peerFp
            self.peerName = peerName
            self.peerDevice = peerDevice
            self.peerHost = peerHost
            self.selfNonce = selfNonce
            self.peerNonce = peerNonce
            self.requested = requested
            self.viaQr = viaQr
            self.status = status
            self.granted = granted
            self.error = error
            self.createdAt = createdAt
            self.updatedAt = updatedAt
        }
    }

    private let selfFp: () -> String
    private let trust: TrustStore
    private let onChange: () -> Void
    private let clock: () -> Int64
    private let randomBytes: (Int) -> [UInt8]
    private let lock = NSRecursiveLock()
    // A LinkedHashMap in Kotlin: an array keeps insertion order, which the
    // pending list's stable sort relies on.
    private var sessions: [Session] = []

    init(
        selfFp: @escaping () -> String,
        trust: TrustStore,
        onChange: @escaping () -> Void = {},
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        random: ((Int) -> [UInt8])? = nil
    ) {
        self.selfFp = selfFp
        self.trust = trust
        self.onChange = onChange
        self.clock = clock
        self.randomBytes = random ?? { n in
            var rng = SystemRandomNumberGenerator()
            return (0..<n).map { _ in UInt8.random(in: 0...255, using: &rng) }
        }
    }

    /// Records an incoming request. `consumeInvite` is called only after every
    /// cap has passed, so a request rejected for a cap does not waste a valid QR
    /// code. When it is present the request is a QR pairing: a false result is a
    /// hard 403 and never falls back to the SAS path.
    func createIncoming(
        mode: String,
        peerFp: String,
        peerName: String,
        peerDevice: String,
        peerHost: String,
        peerNonce: String,
        requested: Permissions,
        consumeInvite: (() -> Bool)? = nil
    ) throws -> SessionView {
        lock.lock(); defer { lock.unlock() }
        if selfFp().trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw PeerHttpException(503, "identity not ready")
        }
        if peerFp.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw PeerHttpException(400, "missing peer identity")
        }
        if mode != Self.MODE_CONNECT && mode != Self.MODE_PAIR {
            throw PeerHttpException(400, "mode must be connect or pair")
        }
        if peerNonce.count < 16 || peerNonce.count > 128 {
            throw PeerHttpException(400, "nonce required")
        }
        if consumeInvite != nil && mode != Self.MODE_PAIR {
            throw PeerHttpException(400, "an invite requires mode pair")
        }
        sweep()
        // Caps are checked before the one-time invite is spent.
        if sessions.count >= Self.MAX_SESSIONS {
            throw PeerHttpException(503, "too many sessions")
        }
        // Matches the Kotlin exactly: the raw (not lowercased) peerFp is
        // compared against the stored, lowercased value.
        let pendingFromPeer = sessions.filter { $0.peerFp == peerFp && $0.status == Self.STATUS_PENDING }.count
        if pendingFromPeer >= Self.MAX_PENDING_PER_PEER {
            throw PeerHttpException(409, "a request from this device is already waiting")
        }
        let pendingTotal = sessions.filter { $0.status == Self.STATUS_PENDING }.count
        if pendingTotal >= Self.MAX_PENDING_TOTAL {
            throw PeerHttpException(429, "too many pending requests")
        }
        var viaQr = false
        if let consumeInvite = consumeInvite {
            if !consumeInvite() { throw PeerHttpException(403, "invalid or used invite") }
            viaQr = true
        }
        let now = clock()
        let sess = Session(
            id: "c_" + randomHex(6),
            mode: mode,
            peerFp: peerFp.lowercased(),
            peerName: Display.safeName(peerName),
            peerDevice: peerDevice,
            peerHost: peerHost,
            selfNonce: randomHex(16),
            peerNonce: peerNonce,
            requested: requested,
            viaQr: viaQr,
            status: Self.STATUS_PENDING,
            granted: Permissions(),
            error: "",
            createdAt: now,
            updatedAt: now
        )
        sessions.append(sess)
        onChange()
        return view(sess)
    }

    /// The status poll, for the session's own peer only.
    func statusFor(_ id: String, callerFp: String) throws -> SessionView {
        lock.lock(); defer { lock.unlock() }
        let sess = try requirePeer(id, callerFp: callerFp)
        return view(sess)
    }

    /// The phone's person accepts the prompt; the granted set mirrors the request.
    func accept(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let sess = session(id) else { return false }
        if sess.status != Self.STATUS_PENDING { return false }
        sess.granted = sess.requested
        sess.status = Self.STATUS_ACCEPTED
        sess.updatedAt = clock()
        onChange()
        return true
    }

    /// The phone's person declines the prompt.
    func decline(_ id: String) -> Bool {
        lock.lock(); defer { lock.unlock() }
        guard let sess = session(id) else { return false }
        if sess.status == Self.STATUS_PENDING || sess.status == Self.STATUS_ACCEPTED {
            sess.status = Self.STATUS_REJECTED
            sess.updatedAt = clock()
            onChange()
        }
        return true
    }

    /// The initiator confirms; only then is the pairing written to the trust
    /// store, with the permissions this phone granted.
    func confirm(_ id: String, callerFp: String) throws -> SessionView {
        lock.lock(); defer { lock.unlock() }
        let sess = try requirePeer(id, callerFp: callerFp)
        if sess.status == Self.STATUS_PENDING {
            throw PeerHttpException(409, "the request was not accepted")
        }
        if sess.status != Self.STATUS_ACCEPTED {
            throw PeerHttpException(409, "session is \(sess.status)")
        }
        sess.status = Self.STATUS_ACTIVE
        sess.updatedAt = clock()
        if sess.mode == Self.MODE_PAIR {
            trust.save(
                PairedPeer(
                    fingerprint: sess.peerFp,
                    name: sess.peerName.isEmpty ? Display.groupedHex(sess.peerFp) : sess.peerName,
                    host: sess.peerHost,
                    port: 0,
                    browse: sess.granted.browse,
                    push: sess.granted.push,
                    pairedAt: clock()
                )
            )
        }
        onChange()
        return view(sess)
    }

    /// Ends a session from either side.
    func close(_ id: String, callerFp: String) throws -> Bool {
        lock.lock(); defer { lock.unlock() }
        let sess = try requirePeer(id, callerFp: callerFp)
        sess.status = Self.STATUS_CLOSED
        sess.updatedAt = clock()
        onChange()
        return true
    }

    /// The prompts the UI should show, oldest first.
    func pending() -> [IncomingRequest] {
        lock.lock(); defer { lock.unlock() }
        sweep()
        // A stable sort by createdAt preserves insertion order for equal times,
        // matching the Kotlin `sortedBy` over a LinkedHashMap.
        let ordered = sessions.enumerated()
            .filter { $0.element.status == Self.STATUS_PENDING }
            .sorted { ($0.element.createdAt, $0.offset) < ($1.element.createdAt, $1.offset) }
            .map { $0.element }
        return ordered.map { it in
            IncomingRequest(
                id: it.id,
                peerFp: it.peerFp,
                peerName: it.peerName,
                peerDevice: it.peerDevice,
                mode: it.mode,
                requested: it.requested,
                viaQr: it.viaQr,
                sas: sas(it),
                createdAt: it.createdAt
            )
        }
    }

    func count() -> Int {
        lock.lock(); defer { lock.unlock() }
        return sessions.count
    }

    func clear() {
        lock.lock(); defer { lock.unlock() }
        sessions.removeAll()
        onChange()
    }

    private func session(_ id: String) -> Session? {
        sessions.first { $0.id == id }
    }

    private func requirePeer(_ id: String, callerFp: String) throws -> Session {
        sweep()
        guard let sess = session(id) else {
            throw PeerHttpException(404, "session not found")
        }
        if !sess.peerFp.lowercased().elementsEqual(callerFp.lowercased()) {
            throw PeerHttpException(403, "not your session")
        }
        return sess
    }

    private func view(_ sess: Session) -> SessionView {
        SessionView(
            id: sess.id,
            status: sess.status,
            mode: sess.mode,
            nonce: sess.selfNonce,
            granted: sess.granted,
            sas: sas(sess),
            error: sess.error
        )
    }

    /// The SAS is only meaningful once the peer's nonce is known.
    private func sas(_ sess: Session) -> String {
        if sess.peerNonce.isEmpty { return "" }
        return Sas.code(fpA: selfFp(), fpB: sess.peerFp, nonceA: sess.selfNonce, nonceB: sess.peerNonce)
    }

    /// Expires stale sessions and drops terminal ones, so they cannot accumulate
    /// toward `MAX_SESSIONS`: pending and accepted-but-unconfirmed sessions expire
    /// after `PAIRING_TTL_MS`; rejected/closed/expired sessions are removed
    /// `TERMINAL_TTL_MS` after their last update; an untouched active session is
    /// closed after `SESSION_INACTIVITY_MS`.
    private func sweep() {
        let now = clock()
        var changed = false
        var remove: Set<String> = []
        for s in sessions {
            switch s.status {
            case Self.STATUS_PENDING, Self.STATUS_ACCEPTED:
                if now - s.updatedAt > Self.PAIRING_TTL_MS {
                    s.status = Self.STATUS_EXPIRED
                    s.updatedAt = now
                    changed = true
                }
            case Self.STATUS_ACTIVE:
                if now - s.updatedAt > Self.SESSION_INACTIVITY_MS {
                    s.status = Self.STATUS_CLOSED
                    s.updatedAt = now
                    changed = true
                }
            case Self.STATUS_REJECTED, Self.STATUS_CLOSED, Self.STATUS_EXPIRED:
                if now - s.updatedAt > Self.TERMINAL_TTL_MS {
                    remove.insert(s.id)
                }
            default:
                break
            }
        }
        if !remove.isEmpty {
            sessions.removeAll { remove.contains($0.id) }
            changed = true
        }
        if changed { onChange() }
    }

    private func randomHex(_ bytes: Int) -> String {
        Hex.encode(randomBytes(bytes))
    }
}
