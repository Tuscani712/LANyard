import XCTest
@testable import LanyardCore

// Ports the per-request authorization cases from `PeerServerTest.kt`
// (helloIsOpen, sessionRequestIsOpenAndShowsPendingWithSas, unpairedClient-
// CannotRevoke, pairedClientCanRevokeItselfOnly) and `PeerServerTask30Test.kt`
// (pairingOnAReusedConnectionIsHonoredPerRequest), plus the share/push
// permission gates from the Kotlin `PeerServer.requirePush`/`handleShares`.
// The live one-off Connect session rule is ported from the Go `trust.Access`
// (`internal/trust/trust.go`) authorization semantics. Session-ownership
// rejections (otherPeerCannotReadYourSession) belong to `PairingSessions`, not
// this pure layer.

final class AuthorizerTests: XCTestCase {
    private let fingerprint = "aa".padding(toLength: 64, withPad: "0", startingAt: 0)

    private func resolved(_ byFingerprint: [String: PeerAccess]) -> Authorizer {
        Authorizer { fp in byFingerprint[fp] ?? .none }
    }

    private func authorize(
        _ authorizer: Authorizer,
        _ method: String,
        _ path: String,
        fp: String? = nil
    ) -> AuthorizationDecision {
        authorizer.authorize(method: method, path: path, fingerprint: fp ?? fingerprint)
    }

    // MARK: - Open endpoints

    func testHelloIsOpen() {
        // Ported: PeerServerTest.helloIsOpen.
        let decision = authorize(resolved([:]), "GET", "/api/v1/hello")
        XCTAssertEqual(.allow, decision)
        XCTAssertEqual(200, decision.statusCode)
    }

    func testSessionRequestIsOpen() {
        // Ported: PeerServerTest.sessionRequestIsOpenAndShowsPendingWithSas.
        XCTAssertEqual(.allow, authorize(resolved([:]), "POST", "/api/v1/session/request"))
    }

    func testHelloIgnoresAQueryString() {
        XCTAssertEqual(.allow, authorize(resolved([:]), "GET", "/api/v1/hello?x=1"))
    }

    func testMissingCertificateIsUnauthorized() {
        // The Go `withPeer` guard: no client certificate is 401.
        let authorizer = resolved([:])
        let noCert = authorizer.authorize(method: "GET", path: "/api/v1/hello", fingerprint: nil)
        XCTAssertEqual(.unauthorized(reason: "client certificate required"), noCert)
        XCTAssertEqual(401, noCert.statusCode)
        XCTAssertEqual(.unauthorized(reason: "client certificate required"), authorizer.authorize(method: "GET", path: "/api/v1/hello", fingerprint: ""))
    }

    func testSessionOwnershipEndpointsPassThrough() {
        // The session store, not the authorizer, enforces "not your session".
        XCTAssertEqual(.allow, authorize(resolved([:]), "GET", "/api/v1/session/c_1"))
        XCTAssertEqual(.allow, authorize(resolved([:]), "POST", "/api/v1/session/c_1/confirm"))
        XCTAssertEqual(.allow, authorize(resolved([:]), "POST", "/api/v1/session/c_1/close"))
    }

    // MARK: - Revoke

    func testUnpairedClientCannotRevoke() {
        // Ported: PeerServerTest.unpairedClientCannotRevoke.
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(resolved([:]), "POST", "/api/v1/trust/revoke"))
    }

    func testPairedClientCanRevoke() {
        // Ported: PeerServerTest.pairedClientCanRevokeItselfOnly (the
        // "own entry only" part is the router's job).
        let authorizer = resolved([fingerprint: PeerAccess(paired: true, browse: true, push: false)])
        XCTAssertEqual(.allow, authorize(authorizer, "POST", "/api/v1/trust/revoke"))
    }

    // MARK: - Push

    func testUnpairedPushOfferIsForbidden() {
        // Ported: PeerServerTask30Test.pairingOnAReusedConnectionIsHonoredPerRequest
        // (the "before pairing" half).
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(resolved([:]), "POST", "/api/v1/push/offer"))
    }

    func testPairedWithoutPushPermissionIsForbidden() {
        // Ported: PeerServer.requirePush.
        let authorizer = resolved([fingerprint: PeerAccess(paired: true, browse: true, push: false)])
        XCTAssertEqual(.forbidden(reason: "push not permitted"), authorize(authorizer, "POST", "/api/v1/push/offer"))
    }

    func testPairedWithPushMayOffer() {
        // Ported: PeerServerTask30Test.pairingOnAReusedConnectionIsHonoredPerRequest
        // (the "after pairing" half).
        let authorizer = resolved([fingerprint: PeerAccess(paired: true, browse: false, push: true)])
        XCTAssertEqual(.allow, authorize(authorizer, "POST", "/api/v1/push/offer"))
    }

    func testPushFileAndCompleteRequirePush() {
        let allowed = resolved([fingerprint: PeerAccess(paired: true, push: true)])
        XCTAssertEqual(.allow, authorize(allowed, "PUT", "/api/v1/push/p_1/file"))
        XCTAssertEqual(.allow, authorize(allowed, "POST", "/api/v1/push/p_1/complete"))

        let denied = resolved([fingerprint: PeerAccess(paired: true, push: false)])
        XCTAssertEqual(.forbidden(reason: "push not permitted"), authorize(denied, "PUT", "/api/v1/push/p_1/file"))
        XCTAssertEqual(.forbidden(reason: "push not permitted"), authorize(denied, "POST", "/api/v1/push/p_1/complete"))
    }

    // MARK: - Pull (shares)

    func testUnpairedSharesIsForbidden() {
        // Ported: PeerServer.handleShares (peer == null -> 403 "not paired").
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(resolved([:]), "GET", "/api/v1/shares"))
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(resolved([:]), "GET", "/api/v1/shares/s_1/tree"))
    }

    func testPairedWithoutBrowseIsForbidden() {
        // Ported: PeerServer.handleShares (!peer.browse -> 403 "pull not permitted").
        let authorizer = resolved([fingerprint: PeerAccess(paired: true, browse: false, push: true)])
        XCTAssertEqual(.forbidden(reason: "pull not permitted"), authorize(authorizer, "GET", "/api/v1/shares"))
    }

    func testPairedWithBrowseMayPull() {
        let authorizer = resolved([fingerprint: PeerAccess(paired: true, browse: true, push: false)])
        XCTAssertEqual(.allow, authorize(authorizer, "GET", "/api/v1/shares"))
        XCTAssertEqual(.allow, authorize(authorizer, "GET", "/api/v1/shares/s_1/manifest"))
        XCTAssertEqual(.allow, authorize(authorizer, "HEAD", "/api/v1/shares/s_1/file"))
    }

    // MARK: - Live one-off Connect sessions

    func testLiveConnectSessionAllowsAnOfferedShare() {
        // Ported from Go trust.Access/GetVisible: a Connect session is offered
        // exactly the shares it may read.
        let session = PeerAccess(sessionId: "c_1", offeredShares: ["s_1", "s_2"])
        let authorizer = resolved([fingerprint: session])
        XCTAssertEqual(.allow, authorize(authorizer, "GET", "/api/v1/shares/s_1/file"))
        XCTAssertEqual(.allow, authorize(authorizer, "GET", "/api/v1/shares/s_2/tree"))
    }

    func testLiveConnectSessionDeniesAnUnlistedShare() {
        let session = PeerAccess(sessionId: "c_1", offeredShares: ["s_1"])
        let authorizer = resolved([fingerprint: session])
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(authorizer, "GET", "/api/v1/shares/s_9/file"))
    }

    func testLiveConnectSessionAllowsShareListWhenAnyOffered() {
        let session = PeerAccess(sessionId: "c_1", offeredShares: ["s_1"])
        XCTAssertEqual(.allow, authorize(resolved([fingerprint: session]), "GET", "/api/v1/shares"))
    }

    func testLiveConnectSessionDoesNotAllowPush() {
        // A Connect session offers shares (a pull); it cannot push.
        let session = PeerAccess(browse: true, sessionId: "c_1", offeredShares: ["s_1"])
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(resolved([fingerprint: session]), "POST", "/api/v1/push/offer"))
    }

    func testLiveConnectSessionWithoutOffersCannotList() {
        let session = PeerAccess(sessionId: "c_1")
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(resolved([fingerprint: session]), "GET", "/api/v1/shares"))
    }

    // MARK: - Per-request re-evaluation

    func testAuthorizationIsReevaluatedPerRequest() {
        // Ported: PeerServerTask30Test.pairingOnAReusedConnectionIsHonoredPerRequest.
        // The same authorizer must honour a pairing that happened between two
        // requests on one (keep-alive) connection.
        var access: [String: PeerAccess] = [:]
        let authorizer = Authorizer { access[$0] ?? .none }

        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(authorizer, "POST", "/api/v1/push/offer"))

        access[fingerprint] = PeerAccess(paired: true, push: true)

        XCTAssertEqual(.allow, authorize(authorizer, "POST", "/api/v1/push/offer"))
    }

    // MARK: - Default

    func testUnknownPathRequiresPairing() {
        XCTAssertEqual(.forbidden(reason: "not paired"), authorize(resolved([:]), "GET", "/api/v1/unknown"))
        let paired = resolved([fingerprint: PeerAccess(paired: true)])
        XCTAssertEqual(.allow, authorize(paired, "GET", "/api/v1/unknown"))
    }
}
