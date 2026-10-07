import XCTest
import Crypto
@testable import LanyardCore

/// "Swift verifies a Go-generated certificate" direction: parse the DER that
/// Go's x509 package emitted (`GoCert.generated.swift`) with SwiftASN1 and
/// check that every value matches what Go reported.
final class GoCertCrossCheckTests: XCTestCase {
    func testParsesGoCertificate() throws {
        let der = try XCTUnwrap(Hex.decode(GoCertFixtures.derHex))
        let parsed = try CertificateParser.parse(der)

        XCTAssertEqual(parsed.subjectCN, GoCertFixtures.subjectCN)
        XCTAssertEqual(parsed.extKeyUsage, GoCertFixtures.extKeyUsage)
        XCTAssertEqual(parsed.keyUsageDigitalSignature, GoCertFixtures.keyUsageDigitalSignature)
        XCTAssertEqual(parsed.basicConstraintsCA, GoCertFixtures.basicConstraintsCA)

        XCTAssertEqual(Hex.encode(parsed.publicKey), GoCertFixtures.publicKeyHex)
        XCTAssertEqual(Hex.encode(parsed.spkiDER), GoCertFixtures.spkiHex)
        XCTAssertEqual(Fingerprint.ofSPKI(parsed.spkiDER), GoCertFixtures.fingerprint)
        XCTAssertEqual(Fingerprint.ofEd25519PublicKey(parsed.publicKey), GoCertFixtures.fingerprint)

        let formatter = ISO8601DateFormatter()
        XCTAssertEqual(parsed.notBefore, formatter.date(from: GoCertFixtures.notBefore))
        XCTAssertEqual(parsed.notAfter, formatter.date(from: GoCertFixtures.notAfter))

        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: parsed.publicKey)
        XCTAssertTrue(
            verifier.isValidSignature(Data(parsed.signature), for: Data(parsed.tbsDER)),
            "Go certificate signature did not verify in Swift"
        )
    }
}
