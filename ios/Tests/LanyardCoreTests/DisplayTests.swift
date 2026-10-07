import XCTest
@testable import LanyardCore

/// Attacker-controlled names and identifiers shown in the pairing dialog.
final class DisplayTests: XCTestCase {
    func testStripsControlCharacters() {
        XCTAssertEqual("HackerX", Display.safeName("Ha\u{0}ck\u{7}erX"))
        XCTAssertEqual("line1line2", Display.safeName("line1\nline2"))
    }

    func testStripsBidiOverrides() {
        // U+202E RIGHT-TO-LEFT OVERRIDE can reorder the rest of the dialog line.
        XCTAssertEqual("evil.exe", Display.safeName("evil.exe\u{202e}"))
        XCTAssertEqual("abc", Display.safeName("\u{202a}abc\u{2069}"))
        XCTAssertEqual("", Display.safeName("\u{200e}\u{200f}"))
    }

    func testCapsLength() {
        XCTAssertEqual(Display.MAX_NAME, Display.safeName(String(repeating: "a", count: 500)).count)
    }

    func testGroupsFingerprintInFours() {
        XCTAssertEqual("4D63 5A83 F403 3D53", Display.groupedHex("4d635a83f4033d53"))
        XCTAssertEqual("4D63 5A83", Display.groupedHex("4d635a83"))
    }
}
