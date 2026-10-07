import XCTest
@testable import LanyardCore

/// Pure receive-path rules: name sanitization and the free-space rule. Ported
/// from the Kotlin `PushProtocolTest`.
final class PushProtocolTests: XCTestCase {
    func testKeepsSimpleAndNestedNames() {
        XCTAssertEqual("a.txt", PushProtocol.sanitizeRel("a.txt"))
        XCTAssertEqual("dir/sub/a.txt", PushProtocol.sanitizeRel("dir/sub/a.txt"))
        XCTAssertEqual("dir/a.txt", PushProtocol.sanitizeRel("dir//a.txt"))
    }

    func testStripsControlAndTrailingDotsAndSpaces() {
        XCTAssertEqual("ab.txt", PushProtocol.sanitizeRel("a\u{0001}b.txt"))
        XCTAssertEqual("name", PushProtocol.sanitizeRel("name. . ."))
        XCTAssertEqual("_", PushProtocol.sanitizeRel("   "))
    }

    func testRejectsTraversalAndUnsafeNames() {
        XCTAssertNil(PushProtocol.sanitizeRel(""))
        XCTAssertNil(PushProtocol.sanitizeRel("/etc/passwd"))
        XCTAssertNil(PushProtocol.sanitizeRel(".."))
        XCTAssertNil(PushProtocol.sanitizeRel("a/../b"))
        XCTAssertNil(PushProtocol.sanitizeRel("a/./b"))
        XCTAssertNil(PushProtocol.sanitizeRel("a\\b"))
        XCTAssertNil(PushProtocol.sanitizeRel("a\u{0000}b"))
        XCTAssertNil(PushProtocol.sanitizeRel("c:stream"))
        XCTAssertNil(PushProtocol.sanitizeRel("x/" + String(repeating: "a", count: 256)))
    }

    func testFreeSpaceCountsBothCopies() {
        XCTAssertEqual(100 + 10 + PushProtocol.FREE_SPACE_MARGIN, PushProtocol.requiredFreeSpace(total: 100, largestFile: 10))
    }

    func testSafeManifestPath() {
        XCTAssertEqual(true, PushProtocol.isSafeRelPath(""))
        XCTAssertEqual(true, PushProtocol.isSafeRelPath("a/b.txt"))
        XCTAssertEqual(false, PushProtocol.isSafeRelPath("../x"))
        XCTAssertEqual(false, PushProtocol.isSafeRelPath("/x"))
    }
}
