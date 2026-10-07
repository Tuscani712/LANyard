import XCTest
@testable import LanyardCore

/// Ported from the Kotlin `PairLinkTest` (itself ported from
/// `internal/pairlink/pairlink_test.go`).
final class PairLinkTests: XCTestCase {
    private let testFp = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    private let testNonce = "00112233445566778899aabbccddeeff"

    func testRoundTrip() {
        let input = PairLink.Payload(
            fingerprint: testFp,
            name: "Kitchen PC",
            addrs: ["192.168.1.20:47800", "10.0.0.5:47800"],
            nonce: testNonce
        )
        let out = try! PairLink.parse(PairLink.build(input))
        XCTAssertEqual(input, out)
    }

    func testRoundTripWithoutName() {
        let out = try! PairLink.parse(
            PairLink.build(PairLink.Payload(fingerprint: testFp, name: "", addrs: ["192.168.1.20:47800"], nonce: testNonce))
        )
        XCTAssertEqual("", out.name)
    }

    func testBuildLeavesAddrUnescaped() {
        let uri = PairLink.build(
            PairLink.Payload(
                fingerprint: testFp, name: "Kitchen PC",
                addrs: ["192.168.1.20:47800", "10.0.0.5:47800"], nonce: testNonce
            )
        )
        XCTAssertTrue(!uri.lowercased().contains("%3a") && !uri.lowercased().contains("%2c"), "addr must stay unescaped: \(uri)")
        XCTAssertTrue(uri.contains("addr=192.168.1.20:47800,10.0.0.5:47800"), "unexpected addr: \(uri)")
        XCTAssertTrue(!uri.contains("name=Kitchen PC"), "name must be escaped: \(uri)")
    }

    func testParseAcceptsEncodedAndUnescapedAddr() {
        let unescaped = "lanyard://pair?fp=\(testFp)&addr=192.168.1.20:47800&n=\(testNonce)"
        let encoded = "lanyard://pair?fp=\(testFp)&addr=192.168.1.20%3A47800&n=\(testNonce)"
        for (name, raw) in [("unescaped", unescaped), ("encoded", encoded)] {
            let p = try! PairLink.parse(raw)
            XCTAssertEqual(["192.168.1.20:47800"], p.addrs, "\(name) addresses")
        }
    }

    func testParseRejectsMalformed() {
        let good = PairLink.build(PairLink.Payload(fingerprint: testFp, name: "", addrs: ["192.168.1.20:47800"], nonce: testNonce))
        let cases: [(String, String)] = [
            ("empty", ""),
            ("wrong scheme", good.replacingOccurrences(of: "lanyard://", with: "http://")),
            ("wrong host", good.replacingOccurrences(of: "lanyard://pair", with: "lanyard://other")),
            ("missing fp", "lanyard://pair?addr=192.168.1.20:47800&n=\(testNonce)"),
            ("missing addr", "lanyard://pair?fp=\(testFp)&n=\(testNonce)"),
            ("missing nonce", "lanyard://pair?fp=\(testFp)&addr=192.168.1.20:47800"),
            ("extra param", "\(good)&evil=1"),
            ("duplicate fp", "lanyard://pair?fp=\(testFp)&fp=\(testFp)&addr=192.168.1.20:47800&n=\(testNonce)"),
            ("short fp", "lanyard://pair?fp=abcd&addr=192.168.1.20:47800&n=\(testNonce)"),
            ("nonhex fp", "lanyard://pair?fp=\(String(repeating: "z", count: 64))&addr=192.168.1.20:47800&n=\(testNonce)"),
            ("short nonce", "lanyard://pair?fp=\(testFp)&addr=192.168.1.20:47800&n=abcd"),
            ("bad host name", "lanyard://pair?fp=\(testFp)&addr=example.com:47800&n=\(testNonce)"),
            ("bad port", "lanyard://pair?fp=\(testFp)&addr=192.168.1.20:99999&n=\(testNonce)"),
            ("no port", "lanyard://pair?fp=\(testFp)&addr=192.168.1.20&n=\(testNonce)"),
        ]
        for (name, raw) in cases {
            XCTAssertThrowsError(try PairLink.parse(raw), "\(name) should be rejected")
        }
    }

    func testParseCapsAddrs() {
        let addrs = Array(repeating: "192.168.1.20:47800", count: PairLink.MAX_ADDRS + 1)
        XCTAssertThrowsError(try PairLink.parse(PairLink.build(PairLink.Payload(fingerprint: testFp, name: "", addrs: addrs, nonce: testNonce))))
    }

    func testParseCapsLength() {
        let long = "lanyard://pair?fp=\(testFp)&addr=192.168.1.20:47800&n=\(testNonce)&name=" + String(repeating: "a", count: PairLink.MAX_LEN)
        XCTAssertThrowsError(try PairLink.parse(long))
    }

    func testParseCapsName() {
        let out = try! PairLink.parse(
            PairLink.build(PairLink.Payload(fingerprint: testFp, name: String(repeating: "n", count: 250), addrs: ["192.168.1.20:47800"], nonce: testNonce))
        )
        XCTAssertEqual(200, out.name.count)
    }
}
