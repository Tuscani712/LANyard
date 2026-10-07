import XCTest
import Crypto
@testable import LanyardCore

/// Builds a certificate with Swift, parses it back, and checks every field.
/// All expected values come from the Go fixtures or from the key itself.
final class CertificateBuilderTests: XCTestCase {
    private func utcDate(_ year: Int, _ month: Int, _ day: Int, _ hour: Int, _ minute: Int, _ second: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "UTC")!
        var components = DateComponents()
        components.year = year
        components.month = month
        components.day = day
        components.hour = hour
        components.minute = minute
        components.second = second
        return calendar.date(from: components)!
    }

    func testBuildParseRoundTrip() throws {
        let seed = try XCTUnwrap(Hex.decode(GoCertFixtures.seedHex))
        let key = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
        let publicKey = Array(key.publicKey.rawRepresentation)

        let notBefore = utcDate(2020, 1, 2, 3, 4, 5)
        let notAfter = utcDate(2030, 1, 2, 3, 4, 5)
        let serial: [UInt8] = [0x01, 0x02, 0x03, 0x04]

        let der = try CertificateBuilder.build(
            deviceName: GoCertFixtures.deviceName,
            privateKey: key,
            serial: serial,
            notBefore: notBefore,
            notAfter: notAfter
        )
        let parsed = try CertificateParser.parse(der)

        XCTAssertEqual(parsed.subjectCN, GoCertFixtures.subjectCN)
        XCTAssertEqual(parsed.notBefore, notBefore)
        XCTAssertEqual(parsed.notAfter, notAfter)
        XCTAssertEqual(parsed.serial, serial)
        XCTAssertEqual(parsed.extKeyUsage, GoCertFixtures.extKeyUsage)
        XCTAssertEqual(parsed.keyUsageDigitalSignature, GoCertFixtures.keyUsageDigitalSignature)
        XCTAssertEqual(parsed.basicConstraintsCA, GoCertFixtures.basicConstraintsCA)
        XCTAssertEqual(parsed.signatureAlgorithmOID, CertificateBuilder.signatureAlgorithmOID.description)

        // The key itself is the source of truth for the SPKI.
        XCTAssertEqual(parsed.publicKey, publicKey)
        XCTAssertEqual(Hex.encode(parsed.publicKey), GoCertFixtures.publicKeyHex)
        XCTAssertEqual(Hex.encode(parsed.spkiDER), GoCertFixtures.spkiHex)
        XCTAssertEqual(parsed.spkiDER, Fingerprint.ed25519SPKIPrefix + publicKey)

        // Both fingerprint entry points agree with Go.
        XCTAssertEqual(Fingerprint.ofEd25519PublicKey(publicKey), GoCertFixtures.fingerprint)
        XCTAssertEqual(Fingerprint.ofSPKI(parsed.spkiDER), GoCertFixtures.fingerprint)
        XCTAssertEqual(Fingerprint.ofSPKI(parsed.spkiDER), GoFixtures.ed25519[1].fingerprint)

        // The Ed25519 signature over the parsed TBS verifies with the parsed key.
        let verifier = try Curve25519.Signing.PublicKey(rawRepresentation: parsed.publicKey)
        XCTAssertTrue(
            verifier.isValidSignature(Data(parsed.signature), for: Data(parsed.tbsDER)),
            "signature did not verify"
        )
        XCTAssertEqual(parsed.signature.count, 64)
    }

    func testHighBitSerialIsEncodedPositive() throws {
        let seed = try XCTUnwrap(Hex.decode(GoCertFixtures.seedHex))
        let serialMagnitude = try XCTUnwrap(Hex.decode(GoCertFixtures.serialHex))
        XCTAssertEqual(serialMagnitude.first! & 0x80, 0x80, "fixture serial must have its high bit set")

        let der = try CertificateBuilder.build(
            deviceName: GoCertFixtures.deviceName,
            seed: seed,
            serial: serialMagnitude,
            notBefore: utcDate(2020, 1, 2, 3, 4, 5),
            notAfter: utcDate(2030, 1, 2, 3, 4, 5)
        )
        let parsed = try CertificateParser.parse(der)

        // The value round-trips without its sign padding...
        XCTAssertEqual(parsed.serial, serialMagnitude)
        // ...while the DER INTEGER carries the leading 0x00 that keeps it positive.
        XCTAssertEqual(parsed.serialDER[0], 0x02)
        XCTAssertEqual(parsed.serialDER[2], 0x00)
        XCTAssertEqual(Array(parsed.serialDER.dropFirst(3)), serialMagnitude)
        XCTAssertEqual(parsed.serialDER.count, serialMagnitude.count + 3)
    }

    func testGeneralizedTimeForDistantDates() throws {
        let seed = try XCTUnwrap(Hex.decode(GoCertFixtures.seedHex))
        let notBefore = utcDate(2060, 6, 7, 8, 9, 10)
        let notAfter = utcDate(2070, 6, 7, 8, 9, 10)

        let der = try CertificateBuilder.build(
            deviceName: GoCertFixtures.deviceName,
            seed: seed,
            serial: [0x2a],
            notBefore: notBefore,
            notAfter: notAfter
        )
        let parsed = try CertificateParser.parse(der)
        XCTAssertEqual(parsed.notBefore, notBefore)
        XCTAssertEqual(parsed.notAfter, notAfter)
    }
}
