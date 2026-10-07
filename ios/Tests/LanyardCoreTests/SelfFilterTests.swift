import XCTest
@testable import LanyardCore

/// The "is this me?" rule for discovery: the phone drops its own advertised
/// short id but keeps everyone else, and an unknown own id hides nothing.
final class SelfFilterTests: XCTestCase {
    private let own = "4d635a83f4033d53"

    func testDropsTheOwnId() {
        XCTAssertTrue(SelfFilter.isSelf(own, own))
    }

    func testKeepsOtherIds() {
        XCTAssertFalse(SelfFilter.isSelf("aaaaaaaaaaaaaaaa", own))
        XCTAssertFalse(SelfFilter.isSelf("4d635a83f4033d54", own))
    }

    func testEmptyOwnIdDropsNothing() {
        XCTAssertFalse(SelfFilter.isSelf(own, ""))
        XCTAssertFalse(SelfFilter.isSelf("", ""))
        XCTAssertFalse(SelfFilter.isSelf("bbbbbbbbbbbbbbbb", "   "))
    }

    func testOwnShortIdIsTrimmedToSixteenHex() {
        XCTAssertEqual(own, SelfFilter.ownShortId("  \(own)" + "4047c031c72aae11  "))
        XCTAssertEqual("", SelfFilter.ownShortId("   "))
    }

    func testMatchingIsCaseInsensitive() {
        XCTAssertTrue(SelfFilter.isSelf("4D635A83F4033D53", own))
    }
}
