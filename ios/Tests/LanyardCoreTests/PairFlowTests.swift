import XCTest
@testable import LanyardCore

/// `PairFlow`, the pairing UI state machine.
///
/// There is no direct Kotlin test of this state machine (Android spreads it
/// across `DevicesViewModel` and the Compose dialogs), so these cases are newly
/// written from the documented behaviour: `PairingSessions`' 2-minute TTL,
/// `PairInvites`' invite, `PairingFlow`'s permission request, and the
/// spec §4.3 pairing flow. The clock is injected so the window is deterministic.
final class PairFlowTests: XCTestCase {
    private var now: Int64 = 1_000_000
    private let selfFp = String(repeating: "aa", count: 32)
    private let peerFp = String(repeating: "bb", count: 32)
    private let peerNonce = String(repeating: "11", count: 16)

    private lazy var trust: TrustStore = {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("pairflow-\(UUID().uuidString)")
        return JsonFileTrustStore(file: dir.appendingPathComponent("peers.json"))
    }()

    private func flow(port: PortProvider = FixedPortProvider(47800)) -> PairFlow {
        PairFlow(trust: trust, selfFingerprint: { self.selfFp }, clock: { self.now }, portProvider: port)
    }

    /// A `PortProvider` whose value can change, to exercise the port becoming
    /// known after `start()`.
    private final class MutablePortProvider: PortProvider {
        var port: Int?
        init(_ port: Int?) { self.port = port }
        func currentPort() -> Int? { port }
    }

    private func offer(receivedAt: Int64? = nil) -> PairOffer {
        PairOffer(
            peerFingerprint: peerFp,
            peerName: "Bob",
            peerDevice: "bob-device",
            peerHost: "10.0.0.5",
            peerNonce: peerNonce,
            requested: Permissions(browse: true, push: true),
            viaQr: true,
            receivedAt: receivedAt ?? now
        )
    }

    // MARK: - Generating

    func testStartMintsATwoMinuteInvite() {
        let f = flow()
        XCTAssertEqual(.idle, f.state)
        let (token, expiresAt) = f.start()
        XCTAssertEqual(32, token.count)
        XCTAssertEqual(now + PairFlow.inviteTTLMillis, expiresAt)
        guard case .generating = f.state else { return XCTFail("expected generating") }
        XCTAssertEqual(token, f.currentInvite?.token)
    }

    // MARK: - Port gating (ITEM 0)

    func testStartWithKnownPortGeneratesInviteWithThatPort() {
        let f = flow(port: FixedPortProvider(47800))
        XCTAssertFalse(f.readyToInvite)
        let (token, _) = f.start()
        guard case .generating = f.state else { return XCTFail("expected generating") }
        XCTAssertTrue(f.readyToInvite)
        XCTAssertEqual(token, f.currentInvite?.token)
        XCTAssertEqual(
            ["192.168.1.20:47800", "[fe80::1]:47800"],
            f.inviteAddresses(localAddresses: ["192.168.1.20", "fe80::1"])
        )
    }

    func testStartWithUnknownPortIsStarting() {
        let f = flow(port: FixedPortProvider(nil))
        f.start()
        XCTAssertEqual(.starting, f.state)
        XCTAssertFalse(f.readyToInvite)
        XCTAssertNil(f.currentInvite, "starting exposes no invite/QR")
        XCTAssertTrue(f.inviteAddresses(localAddresses: ["192.168.1.20"]).isEmpty)
    }

    func testStartWithZeroPortIsStarting() {
        let f = flow(port: FixedPortProvider(0))
        f.start()
        XCTAssertEqual(.starting, f.state)
        XCTAssertFalse(f.readyToInvite)
        XCTAssertTrue(f.inviteAddresses(localAddresses: ["192.168.1.20"]).isEmpty)
    }

    func testTickPromotesStartingWhenPortBecomesKnown() {
        let port = MutablePortProvider(nil)
        let f = flow(port: port)
        let (token, expiresAt) = f.start()
        XCTAssertEqual(.starting, f.state)
        f.tick(now: now)
        XCTAssertEqual(.starting, f.state, "still no port; stays starting")
        port.port = 51234
        f.tick(now: now)
        guard case let .generating(invite, exp) = f.state else { return XCTFail("expected generating") }
        XCTAssertEqual(token, invite, "the pending token is promoted, not re-minted")
        XCTAssertEqual(expiresAt, exp)
        XCTAssertEqual(["10.0.0.9:51234"], f.inviteAddresses(localAddresses: ["10.0.0.9"]))
    }

    func testStartingInviteExpiresAfterTheWindow() {
        let f = flow(port: FixedPortProvider(nil))
        f.start()
        f.tick(now: now + PairFlow.inviteTTLMillis)
        XCTAssertEqual(.expired, f.state)
    }

    func testCountdownRoundsUpAndStopsAtZero() {
        let f = flow()
        f.start()
        XCTAssertEqual(120, f.secondsRemaining(now: now))
        XCTAssertEqual(1, f.secondsRemaining(now: now + 119_001))
        XCTAssertEqual(0, f.secondsRemaining(now: now + 120_000))
        XCTAssertEqual(0, f.secondsRemaining(now: now + 130_000))
    }

    func testTickExpiresTheInvite() {
        let f = flow()
        f.start()
        f.tick(now: now + 119_000)
        guard case .generating = f.state else { return XCTFail("still generating") }
        f.tick(now: now + 120_000)
        XCTAssertEqual(.expired, f.state)
    }

    func testSecondsRemainingIsZeroOutsideAWindow() {
        XCTAssertEqual(0, flow().secondsRemaining())
    }

    // MARK: - Offer + confirm

    func testOfferShowsTheMatchingSas() {
        let f = flow()
        let (token, _) = f.start()
        XCTAssertTrue(f.offer(offer()))
        guard case let .awaitingConfirmation(_, sas) = f.state else {
            return XCTFail("expected awaitingConfirmation")
        }
        XCTAssertEqual(
            Sas.code(fpA: selfFp, fpB: peerFp, nonceA: token, nonceB: peerNonce),
            sas
        )
    }

    func testConfirmWritesThePairedPeer() {
        let f = flow()
        f.start()
        XCTAssertTrue(f.offer(offer()))
        let peer = f.confirmSas()
        XCTAssertNotNil(peer)
        XCTAssertEqual(peerFp, peer?.fingerprint)
        XCTAssertEqual("Bob", peer?.name)
        XCTAssertEqual("10.0.0.5", peer?.host)
        XCTAssertEqual(0, peer?.port, "a QR pairing stores no port; mDNS fills it")
        XCTAssertEqual(.paired(peer!), f.state)
        XCTAssertNotNil(trust.find(peerFp))
    }

    func testPermissionTogglesAreGrantedOnConfirm() {
        let f = flow()
        f.start()
        f.setBrowse(false)
        f.setPush(true)
        XCTAssertTrue(f.offer(offer()))
        let peer = f.confirmSas()
        XCTAssertEqual(false, peer?.browse)
        XCTAssertEqual(true, peer?.push)
        XCTAssertEqual(false, trust.find(peerFp)?.browse)
        XCTAssertEqual(true, trust.find(peerFp)?.push)
    }

    func testStartResetsPermissionToggles() {
        let f = flow()
        f.start()
        f.setBrowse(false)
        f.start()
        XCTAssertTrue(f.permissions.browse)
    }

    func testConfirmWithoutAnOfferDoesNothing() {
        let f = flow()
        f.start()
        XCTAssertNil(f.confirmSas())
        XCTAssertTrue(trust.list().isEmpty)
    }

    // MARK: - Decline / expiry

    func testDeclineWritesNothing() {
        let f = flow()
        f.start()
        f.offer(offer())
        XCTAssertTrue(f.decline())
        XCTAssertEqual(.declined, f.state)
        XCTAssertTrue(trust.list().isEmpty)
    }

    func testDeclineWithoutAnOfferIsRejected() {
        XCTAssertFalse(flow().decline())
    }

    func testOfferAfterExpiryBecomesExpired() {
        let f = flow()
        f.start()
        now += PairFlow.inviteTTLMillis
        XCTAssertFalse(f.offer(offer()))
        XCTAssertEqual(.expired, f.state)
    }

    func testUnconfirmedOfferExpires() {
        let f = flow()
        f.start()
        f.offer(offer())
        f.tick(now: now + PairFlow.inviteTTLMillis + 1)
        XCTAssertEqual(.expired, f.state)
        XCTAssertTrue(trust.list().isEmpty)
    }

    // MARK: - Unpair

    func testUnpairRemovesTrustAndResetsState() {
        let f = flow()
        f.start()
        f.offer(offer())
        _ = f.confirmSas()
        f.unpair(fingerprint: peerFp)
        XCTAssertNil(trust.find(peerFp))
        XCTAssertEqual(.idle, f.state)
    }

    // MARK: - Manual add

    func testManualLinkParsesThroughPairLink() {
        let link = PairLink.build(
            PairLink.Payload(fingerprint: peerFp, name: "Desk", addrs: ["127.0.0.1:47800"], nonce: peerNonce)
        )
        let manual = PairFlow.parseManualLink(link)
        XCTAssertEqual(peerFp, manual?.fingerprint)
        XCTAssertEqual("Desk", manual?.name)
        XCTAssertEqual(["127.0.0.1:47800"], manual?.addresses)
        XCTAssertEqual(peerNonce, manual?.nonce)
    }

    func testManualLinkRejectsAnythingElse() {
        XCTAssertNil(PairFlow.parseManualLink("192.168.1.20:47800"))
        XCTAssertNil(PairFlow.parseManualLink("lanyard://pair?fp=zz"))
        XCTAssertNil(PairFlow.parseManualLink(""))
    }
}
