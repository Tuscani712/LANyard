import XCTest
@testable import LanyardCore

/// The responder-side pairing state machine. Ported from the Kotlin
/// `PairingSessionsTest`.
final class PairingSessionsTests: XCTestCase {
    private var now: Int64 = 1_000_000
    private let selfFp = String(repeating: "aa", count: 32)

    private lazy var trust: TrustStore = {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("lanyard-trust-\(UUID().uuidString)")
        return JsonFileTrustStore(file: dir.appendingPathComponent("peers.json"))
    }()

    private func sessions() -> PairingSessions {
        PairingSessions(selfFp: { self.selfFp }, trust: trust, clock: { self.now })
    }

    private func nonce() -> String { String(repeating: "11", count: 16) }

    private let invites = PairInvites()

    func testAcceptThenConfirmWritesTrust() throws {
        let s = sessions()
        let peer = String(repeating: "bb", count: 32)
        let v = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "Bob", peerDevice: "bob-dev",
            peerHost: "10.0.0.5", peerNonce: nonce(), requested: Permissions(browse: true, push: true)
        )
        XCTAssertEqual(PairingSessions.STATUS_PENDING, v.status)
        XCTAssertEqual(1, s.pending().count)
        XCTAssertTrue(s.accept(v.id))
        XCTAssertNil(trust.find(peer), "no trust entry before the initiator confirms")
        let active = try s.confirm(v.id, callerFp: peer)
        XCTAssertEqual(PairingSessions.STATUS_ACTIVE, active.status)
        let stored = trust.find(peer)
        XCTAssertNotNil(stored)
        XCTAssertTrue(stored!.browse && stored!.push)
    }

    func testDeclineLeavesNothingAndRefusesConfirm() throws {
        let s = sessions()
        let peer = String(repeating: "cc", count: 32)
        let v = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "Cy", peerDevice: "cy",
            peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        XCTAssertTrue(s.decline(v.id))
        XCTAssertTrue(s.pending().isEmpty)
        XCTAssertNil(trust.find(peer))
        XCTAssertThrowsError(try s.confirm(v.id, callerFp: peer))
    }

    func testConfirmRequiresAcceptance() throws {
        let s = sessions()
        let peer = String(repeating: "dd", count: 32)
        let v = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "D", peerDevice: "d",
            peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        XCTAssertThrowsError(try s.confirm(v.id, callerFp: peer))
    }

    func testReplayConfirmRefused() throws {
        let s = sessions()
        let peer = String(repeating: "ee", count: 32)
        let v = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "E", peerDevice: "e",
            peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        XCTAssertTrue(s.accept(v.id))
        _ = try s.confirm(v.id, callerFp: peer)
        XCTAssertThrowsError(try s.confirm(v.id, callerFp: peer))
    }

    func testWrongPeerRefused() throws {
        let s = sessions()
        let v = try s.createIncoming(
            mode: "pair", peerFp: String(repeating: "ff", count: 32), peerName: "F",
            peerDevice: "f", peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        XCTAssertThrowsError(try s.statusFor(v.id, callerFp: String(repeating: "00", count: 32))) { error in
            XCTAssertEqual(403, (error as? PeerHttpException)?.code)
        }
    }

    func testOnePendingPerPeer() throws {
        let s = sessions()
        let peer = String(repeating: "22", count: 32)
        _ = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "P", peerDevice: "p",
            peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        XCTAssertThrowsError(try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "P", peerDevice: "p",
            peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )) { error in
            XCTAssertEqual(409, (error as? PeerHttpException)?.code)
        }
    }

    func testThreePendingTotalCap() throws {
        let s = sessions()
        for i in 0..<3 {
            let peer = String(format: "%02x", i) + String(repeating: String(format: "%02x", i), count: 31)
            _ = try s.createIncoming(
                mode: "connect", peerFp: peer, peerName: "P\(i)", peerDevice: "p",
                peerHost: "h", peerNonce: nonce(), requested: Permissions()
            )
        }
        XCTAssertThrowsError(try s.createIncoming(
            mode: "connect", peerFp: String(repeating: "99", count: 32), peerName: "P",
            peerDevice: "p", peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )) { error in
            XCTAssertEqual(429, (error as? PeerHttpException)?.code)
        }
    }

    func testPendingExpires() throws {
        let s = sessions()
        let peer = String(repeating: "33", count: 32)
        let v = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "P", peerDevice: "p",
            peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        now += PairingSessions.PAIRING_TTL_MS + 1
        XCTAssertTrue(s.pending().isEmpty)
        XCTAssertEqual(PairingSessions.STATUS_EXPIRED, try s.statusFor(v.id, callerFp: peer).status)
    }

    func testSasMatchesTheAlgorithm() throws {
        let s = sessions()
        let peer = String(repeating: "44", count: 32)
        let peerNonce = nonce()
        let v = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "P", peerDevice: "p",
            peerHost: "h", peerNonce: peerNonce, requested: Permissions()
        )
        XCTAssertEqual(Sas.code(fpA: selfFp, fpB: peer, nonceA: v.nonce, nonceB: peerNonce), v.sas)
    }

    func testTerminalSessionsArePurgedSoTheCapDoesNotFill() throws {
        let s = sessions()
        for i in 0..<PairingSessions.MAX_SESSIONS {
            let hex = String(i, radix: 16)
            let peer = String(repeating: "0", count: 64 - hex.count) + hex
            let v = try s.createIncoming(
                mode: "connect", peerFp: peer, peerName: "P", peerDevice: "p",
                peerHost: "h", peerNonce: nonce(), requested: Permissions()
            )
            XCTAssertTrue(s.decline(v.id))
        }
        XCTAssertEqual(PairingSessions.MAX_SESSIONS, s.count())
        // Without the purge these terminals would fill the cap and this would 503.
        now += PairingSessions.TERMINAL_TTL_MS + 1
        let v = try s.createIncoming(
            mode: "connect", peerFp: String(repeating: "ff", count: 32), peerName: "P",
            peerDevice: "p", peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        XCTAssertNotNil(v)
        XCTAssertTrue(s.count() < PairingSessions.MAX_SESSIONS)
    }

    func testAcceptedButUnconfirmedExpiresAndIsThenPurged() throws {
        let s = sessions()
        let peer = String(repeating: "12", count: 32)
        let v = try s.createIncoming(
            mode: "pair", peerFp: peer, peerName: "P", peerDevice: "p",
            peerHost: "h", peerNonce: nonce(), requested: Permissions()
        )
        XCTAssertTrue(s.accept(v.id))
        now += PairingSessions.PAIRING_TTL_MS + 1
        XCTAssertEqual(PairingSessions.STATUS_EXPIRED, try s.statusFor(v.id, callerFp: peer).status)
        now += PairingSessions.TERMINAL_TTL_MS + 1
        XCTAssertThrowsError(try s.statusFor(v.id, callerFp: peer)) { error in
            XCTAssertEqual(404, (error as? PeerHttpException)?.code)
        }
    }

    func testRejectedRequestLeavesTheInviteUsable() throws {
        let s = sessions()
        for i in 0..<3 {
            let peer = String(format: "%02x", i) + String(repeating: String(format: "%02x", i), count: 31)
            _ = try s.createIncoming(
                mode: "connect", peerFp: peer, peerName: "P\(i)", peerDevice: "p",
                peerHost: "h", peerNonce: nonce(), requested: Permissions()
            )
        }
        let invite = invites.mint().token
        XCTAssertThrowsError(try s.createIncoming(
            mode: "pair", peerFp: String(repeating: "ab", count: 32), peerName: "P",
            peerDevice: "p", peerHost: "h", peerNonce: nonce(), requested: Permissions(),
            consumeInvite: { self.invites.consume(invite) }
        )) { error in
            XCTAssertEqual(429, (error as? PeerHttpException)?.code)
        }
        XCTAssertTrue(invites.consume(invite), "a cap rejection must not spend the one-time code")
    }

    func testBadInviteIsForbiddenAndNeverFallsBackToSas() throws {
        let s = sessions()
        XCTAssertThrowsError(try s.createIncoming(
            mode: "pair", peerFp: String(repeating: "cd", count: 32), peerName: "P",
            peerDevice: "p", peerHost: "h", peerNonce: nonce(), requested: Permissions(),
            consumeInvite: { self.invites.consume("deadbeef") }
        )) { error in
            XCTAssertEqual(403, (error as? PeerHttpException)?.code)
        }
    }
}
