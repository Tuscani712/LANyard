import Foundation

/// The outcome of a pairing attempt, in plain terms the UI can show.
///
/// Ported from the Kotlin `PairResult` sealed class in `PairingFlow.kt`.
enum PairResult {
    /// The peer accepted; the record was saved to the `TrustStore`.
    case paired(PairedPeer)
    /// The peer (or its server) rejected the request.
    case refused
    /// The pairing window closed before the peer accepted.
    case expired
    /// No address in the link answered.
    case unreachable
    /// An address answered, but not with the certificate the link promised.
    case fingerprintMismatch
    /// The pasted text was not a usable pairing link.
    case invalidLink
}

/// A peer answered with an unexpected HTTP status.
///
/// Ported from `PeerStatusException` in the Kotlin `PeerClient.kt`. The actual
/// `PairingFlow` catches this to map a rejection onto `PairResult.refused`.
final class PeerStatusException: Error, CustomStringConvertible {
    let code: Int
    let body: String

    init(_ code: Int, _ body: String) {
        self.code = code
        self.body = body
    }

    var description: String { "HTTP \(code): \(String(body.prefix(200)))" }
}

/// The identity the pairing flow needs: only the device ID is read by the state
/// machine. The real Ed25519 key/X.509 certificate (BouncyCastle on Kotlin,
/// Security.framework on iOS) is not pure and stays behind the transport seam.
protocol PairingIdentity {
    var deviceId: String { get }
}

/// The `/hello` response as the flow uses it: only the name.
struct PairHello {
    let name: String
}

/// The discovery probe: one `GET /api/v1/hello` plus the fingerprint of the
/// certificate that answered. Implemented on iOS over an unpinned TLS
/// connection; faked in tests. Mirrors the Kotlin `ProbeClient`.
protocol PairingProbe: AnyObject {
    func hello() throws
    func observedFingerprint() -> String
}

/// The session API the flow drives. Implemented on iOS over pinned mTLS;
/// faked in tests. Mirrors the Kotlin `PeerClient` pairing methods.
protocol PairingClient: AnyObject {
    func startSession(
        mode: String,
        name: String,
        deviceId: String,
        nonce: String,
        requested: Permissions,
        invite: String
    ) throws -> [String: Any]
    func sessionStatus(_ sessionId: String) throws -> [String: Any]
    func confirmSession(_ sessionId: String) throws
    func hello() throws -> PairHello
}

/// The socket/TLS seam. The pairing state machine only ever talks to the peer
/// through these two factories, so it is pure and testable on Linux; the real
/// implementation lives with the Apple networking code.
protocol PairingTransport {
    func probe(host: String, port: Int, identity: PairingIdentity) -> PairingProbe
    func client(
        host: String,
        port: Int,
        identity: PairingIdentity,
        expectedFingerprint: String
    ) -> PairingClient
}

/// A host and port parsed out of a link address.
struct PairingHostPort: Equatable {
    let host: String
    let port: Int
}

/// Runs the client half of pairing: parse a `PairLink`, find the peer among its
/// addresses, verify the certificate it presents really is the one the link
/// promised, then exchange the one-time invite for a trust entry.
///
/// Blocking (like the Kotlin `PeerClient`); call it off the main thread. The
/// fingerprint check is fail-closed: a mismatch never proceeds to the session
/// request.
///
/// Ported from the Kotlin `PairingFlow`. The socket/TLS work is behind
/// `PairingTransport`; the clock and sleep are injected so the loop is
/// deterministic under test.
enum PairingFlow {
    static func pair(
        link: String,
        identity: PairingIdentity,
        selfName: String,
        store: TrustStore,
        requested: Permissions = Permissions(browse: true, push: true),
        timeoutMs: Int64 = 30_000,
        transport: PairingTransport,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        sleep: @escaping (Int64) -> Void = { Thread.sleep(forTimeInterval: Double($0) / 1000.0) }
    ) -> PairResult {
        let payload: PairLink.Payload
        do {
            payload = try PairLink.parse(link)
        } catch {
            return .invalidLink
        }

        let outcome = probeAddresses(payload.addrs, expected: payload.fingerprint, identity: identity, transport: transport)
        guard let match = outcome.match else {
            return outcome.mismatch ? .fingerprintMismatch : .unreachable
        }

        let client = transport.client(
            host: match.host,
            port: match.port,
            identity: identity,
            expectedFingerprint: payload.fingerprint
        )

        let session: [String: Any]
        do {
            session = try client.startSession(
                mode: "pair",
                name: selfName,
                deviceId: identity.deviceId,
                nonce: randomNonce(),
                requested: requested,
                invite: payload.nonce
            )
        } catch is PeerStatusException {
            return .refused
        } catch {
            return .unreachable
        }

        let sessionId = jsonStr(session, "session_id")
        if sessionId.isEmpty { return .refused }

        let deadline = clock() + timeoutMs
        while clock() < deadline {
            let status: [String: Any]
            do {
                status = try client.sessionStatus(sessionId)
            } catch is PeerStatusException {
                return .refused
            } catch {
                return .unreachable
            }
            switch jsonStr(status, "status") {
            case "accepted":
                return finish(client, sessionId: sessionId, status: status, match: match, payload: payload, selfName: selfName, store: store, clock: clock)
            case "rejected", "closed":
                return .refused
            case "expired":
                return .expired
            default:
                break
            }
            sleep(200)
        }
        return .expired
    }

    private static func finish(
        _ client: PairingClient,
        sessionId: String,
        status: [String: Any],
        match: PairingHostPort,
        payload: PairLink.Payload,
        selfName: String,
        store: TrustStore,
        clock: () -> Int64
    ) -> PairResult {
        do {
            try client.confirmSession(sessionId)
        } catch is PeerStatusException {
            return .refused
        } catch {
            return .unreachable
        }
        let grantedRaw = status["granted"]
        let granted: [String: Any]? = (grantedRaw is NSNull) ? nil : (grantedRaw as? [String: Any])
        let name: String
        do {
            let helloName = try client.hello().name
            name = helloName.isEmpty ? (payload.name.isEmpty ? selfName : payload.name) : helloName
        } catch {
            name = payload.name
        }
        let peer = PairedPeer(
            fingerprint: payload.fingerprint.lowercased(),
            name: name.isEmpty ? payload.name : name,
            host: match.host,
            port: match.port,
            browse: jsonBool(granted, "browse") ?? true,
            push: jsonBool(granted, "push") ?? false,
            pairedAt: clock()
        )
        store.save(peer)
        return .paired(peer)
    }

    private struct ProbeOutcome {
        let match: PairingHostPort?
        let mismatch: Bool
    }

    private static func probeAddresses(
        _ addrs: [String],
        expected: String,
        identity: PairingIdentity,
        transport: PairingTransport
    ) -> ProbeOutcome {
        var mismatch = false
        for addr in addrs {
            guard let hp = splitAddr(addr) else { continue }
            do {
                let probe = transport.probe(host: hp.host, port: hp.port, identity: identity)
                try probe.hello()
                if probe.observedFingerprint().lowercased() == expected.lowercased() {
                    return ProbeOutcome(match: hp, mismatch: mismatch)
                }
                mismatch = true
            } catch {
                // try the next address
            }
        }
        return ProbeOutcome(match: nil, mismatch: mismatch)
    }

    private static func splitAddr(_ addr: String) -> PairingHostPort? {
        let host: String
        let portText: String
        if addr.hasPrefix("[") {
            guard let end = addr.firstIndex(of: "]") else { return nil }
            let afterEnd = addr.index(after: end)
            guard afterEnd < addr.endIndex, addr[afterEnd] == ":" else { return nil }
            host = String(addr[addr.index(after: addr.startIndex)..<end])
            portText = String(addr[addr.index(after: afterEnd)...])
        } else {
            guard let c = addr.lastIndex(of: ":"), c > addr.startIndex else { return nil }
            host = String(addr[addr.startIndex..<c])
            portText = String(addr[addr.index(after: c)...])
        }
        if host.isEmpty { return nil }
        guard let port = Int(portText) else { return nil }
        if port < 1 || port > 65535 { return nil }
        return PairingHostPort(host: host, port: port)
    }

    private static func randomNonce() -> String {
        var rng = SystemRandomNumberGenerator()
        let buf = (0..<16).map { _ in UInt8.random(in: 0...255, using: &rng) }
        return Hex.encode(buf)
    }

    private static func jsonStr(_ o: [String: Any], _ key: String) -> String {
        guard let v = o[key], !(v is NSNull) else { return "" }
        return v as? String ?? ""
    }

    private static func jsonBool(_ o: [String: Any]?, _ key: String) -> Bool? {
        guard let v = o?[key], !(v is NSNull), let b = v as? Bool else { return nil }
        return b
    }
}
