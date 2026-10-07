import XCTest
@testable import LanyardCore

/// `PairingFlow` against a fake transport. The Kotlin `PairingFlowTest` runs the
/// same four cases against a real Go peer over loopback TLS, gated on
/// `LANYARD_BIN`. That live path needs sockets, JSSE/BouncyCastle and a built Go
/// binary, none of which exist on this Linux box, so the flow's socket/TLS work
/// is behind `PairingTransport` and these tests drive the identical state machine
/// with a controllable double. The assertions are the Kotlin ones.
final class PairingFlowTests: XCTestCase {
    private struct TestIdentity: PairingIdentity {
        let deviceId = String(repeating: "a", count: 64)
    }

    private enum FakeError: Error { case cannotConnect }

    private final class FakeProbe: PairingProbe {
        var fingerprint: String
        var failHello: Bool
        init(fingerprint: String, failHello: Bool = false) {
            self.fingerprint = fingerprint
            self.failHello = failHello
        }
        func hello() throws { if failHello { throw FakeError.cannotConnect } }
        func observedFingerprint() -> String { fingerprint }
    }

    private final class FakeClient: PairingClient {
        var startResult: [String: Any] = ["session_id": "s1"]
        var startError: Error?
        var statusSequence: [[String: Any]] = []
        var statusError: Error?
        var confirmError: Error?
        var helloName = "desktop"
        var helloError: Error?
        private var statusIndex = 0

        func startSession(
            mode: String, name: String, deviceId: String, nonce: String,
            requested: Permissions, invite: String
        ) throws -> [String: Any] {
            if let e = startError { throw e }
            return startResult
        }

        func sessionStatus(_ sessionId: String) throws -> [String: Any] {
            if let e = statusError { throw e }
            if statusSequence.isEmpty { return ["status": "pending"] }
            let s = statusSequence[min(statusIndex, statusSequence.count - 1)]
            statusIndex += 1
            return s
        }

        func confirmSession(_ sessionId: String) throws {
            if let e = confirmError { throw e }
        }

        func hello() throws -> PairHello {
            if let e = helloError { throw e }
            return PairHello(name: helloName)
        }
    }

    private final class FakeTransport: PairingTransport {
        let probe: FakeProbe
        let client: FakeClient
        init(probe: FakeProbe, client: FakeClient) {
            self.probe = probe
            self.client = client
        }
        func probe(host: String, port: Int, identity: PairingIdentity) -> PairingProbe { probe }
        func client(host: String, port: Int, identity: PairingIdentity, expectedFingerprint: String) -> PairingClient { client }
    }

    private func store() -> (JsonFileTrustStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("pair-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("peers.json")
        return (JsonFileTrustStore(file: file), file)
    }

    private func link(fingerprint: String, addr: String, nonce: String = String(repeating: "11", count: 16)) -> String {
        PairLink.build(PairLink.Payload(fingerprint: fingerprint, name: "desktop", addrs: [addr], nonce: nonce))
    }

    private func paired(_ r: PairResult) -> PairedPeer? {
        if case let .paired(p) = r { return p }
        return nil
    }

    func testPairsSuccessfully() {
        let fp = String(repeating: "ab", count: 32)
        let probe = FakeProbe(fingerprint: fp)
        let client = FakeClient()
        client.statusSequence = [
            ["status": "pending"],
            ["status": "accepted", "granted": ["browse": true, "push": true]],
        ]
        let transport = FakeTransport(probe: probe, client: client)
        let (trust, _) = store()

        let result = PairingFlow.pair(
            link: link(fingerprint: fp, addr: "127.0.0.1:47800"),
            identity: TestIdentity(), selfName: "iPhone test", store: trust,
            timeoutMs: 5_000, transport: transport, clock: { 1_000 }, sleep: { _ in }
        )

        XCTAssertNotNil(paired(result), "expected Paired, got \(result)")
        let record = trust.find(fp)
        XCTAssertNotNil(record, "the pairing must be saved to the trust store")
        XCTAssertEqual(fp.lowercased(), record!.fingerprint)
        XCTAssertEqual("127.0.0.1", record!.host)
        XCTAssertEqual(47800, record!.port)
        XCTAssertTrue(record!.browse, "browse permission must be granted")
        XCTAssertTrue(record!.push, "push permission must be granted")
    }

    func testWrongFingerprintInLinkFailsClosed() {
        let expected = String(repeating: "11", count: 32)
        // The address answers, but presents a different certificate.
        let probe = FakeProbe(fingerprint: String(repeating: "00", count: 32))
        let client = FakeClient()
        let transport = FakeTransport(probe: probe, client: client)
        let (trust, _) = store()

        let result = PairingFlow.pair(
            link: link(fingerprint: expected, addr: "127.0.0.1:47800"),
            identity: TestIdentity(), selfName: "iPhone test", store: trust,
            timeoutMs: 5_000, transport: transport, clock: { 1_000 }, sleep: { _ in }
        )

        if case .fingerprintMismatch = result {} else {
            XCTFail("expected FingerprintMismatch, got \(result)")
        }
        XCTAssertTrue(trust.list().isEmpty, "nothing may be saved on a mismatch")
    }

    func testBadInviteIsRefused() {
        let fp = String(repeating: "ab", count: 32)
        let probe = FakeProbe(fingerprint: fp)
        let client = FakeClient()
        // The peer rejects the session request with a non-2xx status.
        client.startError = PeerStatusException(403, "invalid or used invite")
        let transport = FakeTransport(probe: probe, client: client)
        let (trust, _) = store()

        let result = PairingFlow.pair(
            link: link(fingerprint: fp, addr: "127.0.0.1:47800"),
            identity: TestIdentity(), selfName: "iPhone test", store: trust,
            timeoutMs: 5_000, transport: transport, clock: { 1_000 }, sleep: { _ in }
        )

        if case .refused = result {} else {
            XCTFail("expected Refused, got \(result)")
        }
        XCTAssertTrue(trust.list().isEmpty)
    }

    func testUnreachableAddressReportsUnreachable() {
        let fp = String(repeating: "ab", count: 32)
        let probe = FakeProbe(fingerprint: fp, failHello: true)
        let client = FakeClient()
        let transport = FakeTransport(probe: probe, client: client)
        let (trust, _) = store()

        let result = PairingFlow.pair(
            link: link(fingerprint: fp, addr: "127.0.0.1:47800"),
            identity: TestIdentity(), selfName: "iPhone test", store: trust,
            timeoutMs: 5_000, transport: transport, clock: { 1_000 }, sleep: { _ in }
        )

        if case .unreachable = result {} else {
            XCTFail("expected Unreachable, got \(result)")
        }
    }

    func testInvalidLinkIsRejected() {
        let transport = FakeTransport(probe: FakeProbe(fingerprint: String(repeating: "00", count: 32)), client: FakeClient())
        let (trust, _) = store()
        let result = PairingFlow.pair(
            link: "not a lanyard link", identity: TestIdentity(), selfName: "iPhone test",
            store: trust, transport: transport, clock: { 1_000 }, sleep: { _ in }
        )
        if case .invalidLink = result {} else {
            XCTFail("expected InvalidLink, got \(result)")
        }
    }

    func testExpiredWindowReportsExpired() {
        let fp = String(repeating: "ab", count: 32)
        let probe = FakeProbe(fingerprint: fp)
        let client = FakeClient()
        client.statusSequence = [["status": "expired"]]
        let transport = FakeTransport(probe: probe, client: client)
        let (trust, _) = store()
        let result = PairingFlow.pair(
            link: link(fingerprint: fp, addr: "127.0.0.1:47800"),
            identity: TestIdentity(), selfName: "iPhone test", store: trust,
            timeoutMs: 5_000, transport: transport, clock: { 1_000 }, sleep: { _ in }
        )
        if case .expired = result {} else {
            XCTFail("expected Expired, got \(result)")
        }
    }
}
