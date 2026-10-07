import XCTest
import Crypto
@testable import LanyardCore

/// Cross-implementation check: the Device ID (SPKI SHA-256) for a fixed
/// Ed25519 key must equal what Go computes. Vectors come from
/// `ios/fixtures/generate.go`; corresponds to the Kotlin `IdentityTest`.
final class FingerprintTests: XCTestCase {
    func testMatchesGoFixtures() throws {
        XCTAssertFalse(GoFixtures.ed25519.isEmpty)
        for v in GoFixtures.ed25519 {
            let seed = try XCTUnwrap(Hex.decode(v.seedHex), "bad seed hex in fixture")
            let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
            let pub = key.publicKey.rawRepresentation
            XCTAssertEqual(Hex.encode(pub), v.publicKeyHex, "public key mismatch (\(v.name))")

            // Both entry points must agree with Go: the SPKI hash and the
            // prefix-rebuilt-from-raw-key hash.
            XCTAssertEqual(Fingerprint.ofSPKI(Data(Hex.decode(v.spkiHex)!)), v.fingerprint, "SPKI hash (\(v.name))")
            XCTAssertEqual(Fingerprint.ofEd25519PublicKey(pub), v.fingerprint, "raw-key hash (\(v.name))")
        }
    }

    func testSPKIPrefixMatchesBuild() throws {
        // A stable assertion of the prefix claim: SPKI = prefix || raw key.
        let v = GoFixtures.ed25519[0]
        let spki = try XCTUnwrap(Hex.decode(v.spkiHex))
        XCTAssertEqual(Array(spki.prefix(Fingerprint.ed25519SPKIPrefix.count)), Fingerprint.ed25519SPKIPrefix)
        XCTAssertEqual(Hex.encode(spki.suffix(from: Fingerprint.ed25519SPKIPrefix.count)), v.publicKeyHex)
    }
}
