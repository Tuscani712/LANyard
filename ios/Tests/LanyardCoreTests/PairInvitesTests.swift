import XCTest
@testable import LanyardCore

/// The phone's one-time QR pairing invites. Ported from the Kotlin
/// `PairInvitesTest`.
final class PairInvitesTests: XCTestCase {
    private var now: Int64 = 1_000_000

    private func invites() -> PairInvites {
        PairInvites(ttlMillis: 1000, clock: { self.now })
    }

    func testTokenIs128BitHex() {
        let t = invites().mint().token
        XCTAssertEqual(32, t.count)
        XCTAssertTrue(t.allSatisfy { "0123456789abcdef".contains($0) })
    }

    func testConsumedOnFirstUseOnly() {
        let p = invites()
        let inv = p.mint()
        XCTAssertTrue(p.consume(inv.token), "first use should succeed")
        XCTAssertFalse(p.consume(inv.token), "a reused invite must fail")
    }

    func testExpiredIsRejected() {
        let p = invites()
        let inv = p.mint()
        now += 2000
        XCTAssertFalse(p.consume(inv.token))
    }

    func testValidUntilExpiryWithoutConsuming() {
        let p = invites()
        let inv = p.mint()
        XCTAssertNotNil(p.valid(inv.token))
        XCTAssertNotNil(p.valid(inv.token), "valid must not consume")
        now += 1001
        XCTAssertNil(p.valid(inv.token))
    }

    func testUnknownTokenRejected() {
        let p = invites()
        XCTAssertFalse(p.consume("deadbeef"))
        XCTAssertNil(p.valid("deadbeef"))
    }
}
