import XCTest
import Crypto
@testable import LanyardCore

/// Confirms the Linux toolchain and swift-crypto resolve, build and pass. This
/// is the gate before the rest of the port: if this does not run, nothing else
/// in the package can be trusted here.
final class SmokeTests: XCTestCase {
    func testEd25519SignAndVerify() throws {
        let key = Curve25519.Signing.PrivateKey()
        let message = Data("lanyard".utf8)
        let signature = try key.signature(for: message)
        XCTAssertTrue(key.publicKey.isValidSignature(signature, for: message))
    }

    func testSha256MatchesKnownVector() {
        let digest = SHA256.hash(data: Data("abc".utf8))
        XCTAssertEqual(
            Hex.encode(digest),
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
        )
    }

    func testHexRoundTrip() {
        let bytes: [UInt8] = [0x00, 0x0f, 0xff, 0xa5]
        XCTAssertEqual(Hex.encode(bytes), "000fffa5")
        XCTAssertEqual(Hex.decode("000fffa5"), bytes)
        XCTAssertNil(Hex.decode("abc"))
    }
}
