import XCTest
@testable import LanyardCore

/// Cross-implementation check: the SAS must equal what the Go `identity.SAS`
/// produces for the same inputs. The expected codes live in
/// `GoFixtures.generated.swift`, emitted by `ios/fixtures/generate.go`, so they
/// are never hand-typed. Corresponds to the Kotlin `SasTest.matchesGoVectors`.
final class SasTests: XCTestCase {
    func testMatchesGoFixtures() {
        XCTAssertFalse(GoFixtures.sas.isEmpty)
        for v in GoFixtures.sas {
            XCTAssertEqual(
                Sas.code(fpA: v.fpA, fpB: v.fpB, nonceA: v.nonceA, nonceB: v.nonceB),
                v.code,
                "SAS mismatch for \(v.fpA) / \(v.fpB)"
            )
        }
    }

    func testIsOrderIndependent() {
        XCTAssertEqual(
            Sas.code(fpA: "aaaa", fpB: "bbbb", nonceA: "11", nonceB: "22"),
            Sas.code(fpA: "bbbb", fpB: "aaaa", nonceA: "22", nonceB: "11")
        )
    }

    func testIsSixDigits() {
        let code = Sas.code(
            fpA: String(repeating: "a", count: 64),
            fpB: String(repeating: "b", count: 64),
            nonceA: String(repeating: "1", count: 32),
            nonceB: String(repeating: "2", count: 32)
        )
        XCTAssertEqual(code.count, 6)
    }
}
