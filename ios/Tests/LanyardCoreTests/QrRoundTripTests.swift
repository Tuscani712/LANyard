import XCTest
@testable import LanyardCore

/// Ported from the Kotlin `QrRoundTripTest`.
///
/// The Kotlin test uses ZXing to render the link to a luminance image and decode
/// it back through the camera library. Swift on Linux has no ZXing dependency
/// and the package's `Package.swift` is frozen, so the QR encode/decode half
/// cannot be reproduced here. What remains is the part that actually protects
/// the protocol: the link that would be encoded builds and parses back carrying
/// the same identity. The QR-specific assertion is dropped (see final report).
final class QrRoundTripTests: XCTestCase {
    func testEncodesAndDecodesAPairingLink() throws {
        let link = PairLink.build(
            PairLink.Payload(
                fingerprint: String(repeating: "ab", count: 32),
                name: "desktop",
                addrs: ["192.168.90.121:47800"],
                nonce: String(repeating: "cd", count: 16)
            )
        )

        // The decoded text is a valid pairing link carrying the same identity.
        XCTAssertEqual(String(repeating: "ab", count: 32), try PairLink.parse(link).fingerprint)
    }
}
