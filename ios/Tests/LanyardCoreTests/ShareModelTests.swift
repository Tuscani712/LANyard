import XCTest
@testable import LanyardCore

/// Ported from the Kotlin `ShareModelTest`.
final class ShareModelTests: XCTestCase {
    private func peer(_ fp: String, push: Bool = true) -> PairedPeer {
        PairedPeer(
            fingerprint: fp, name: "peer", host: "10.0.0.2", port: 47800,
            browse: true, push: push, pairedAt: 0
        )
    }

    func testAcceptsContentUrisWithAProvider() {
        XCTAssertTrue(ShareValidation.validateUri(scheme: "content", authority: "com.android.providers.media.documents", readable: true).accepted)
    }

    func testRejectsContentUrisWithoutAProvider() {
        XCTAssertFalse(ShareValidation.validateUri(scheme: "content", authority: nil, readable: true).accepted)
        XCTAssertFalse(ShareValidation.validateUri(scheme: "content", authority: "", readable: true).accepted)
    }

    func testRejectsContentUrisThatCannotBeOpened() {
        XCTAssertFalse(ShareValidation.validateUri(scheme: "content", authority: "com.example.provider", readable: false).accepted)
    }

    func testRejectsEveryFileUri() {
        // `file:` shares are hostile by default: even a path that resolves into
        // our own storage via the /data/data alias must be refused. The scheme is
        // the only input, so this covers every file: URI.
        XCTAssertFalse(ShareValidation.validateUri(scheme: "file", authority: nil, readable: true).accepted)
        XCTAssertFalse(ShareValidation.validateUri(scheme: "file", authority: "anything", readable: true).accepted)
        XCTAssertFalse(ShareValidation.validateUri(scheme: "FILE", authority: nil, readable: true).accepted)
    }

    func testRefusesUnknownSchemes() {
        XCTAssertFalse(ShareValidation.validateUri(scheme: "http", authority: "example.com", readable: true).accepted)
        XCTAssertFalse(ShareValidation.validateUri(scheme: "javascript", authority: nil, readable: true).accepted)
        XCTAssertFalse(ShareValidation.validateUri(scheme: nil, authority: nil, readable: true).accepted)
    }

    func testCapsItemCountAt100() {
        XCTAssertEqual(3, ShareValidation.capItemCount(3))
        XCTAssertEqual(100, ShareValidation.capItemCount(100))
        XCTAssertEqual(100, ShareValidation.capItemCount(101))
        XCTAssertEqual(100, ShareValidation.capItemCount(5000))
    }

    func testSnippetCapBoundaryIsExact() {
        let exact = String(repeating: "a", count: 64 * 1024)
        XCTAssertFalse(ShareValidation.textExceedsSnippet(exact))
        XCTAssertTrue(ShareValidation.textExceedsSnippet(exact + "a"))
        // The cap is measured in bytes, not characters.
        XCTAssertTrue(ShareValidation.textExceedsSnippet(String(repeating: "é", count: 64 * 1024)))
    }

    func testSafeNameKeepsTheLastPathSegment() {
        XCTAssertEqual("report.pdf", ShareValidation.safeShareName("report.pdf"))
        XCTAssertEqual("report.pdf", ShareValidation.safeShareName("/path/to/report.pdf"))
    }

    func testSafeNameRejectsDangerousNames() {
        XCTAssertEqual("shared-file", ShareValidation.safeShareName(nil))
        XCTAssertEqual("shared-file", ShareValidation.safeShareName(""))
        XCTAssertEqual("shared-file", ShareValidation.safeShareName("."))
        XCTAssertEqual("shared-file", ShareValidation.safeShareName(".."))
        XCTAssertEqual("shared-file", ShareValidation.safeShareName("a\\b.txt"))
        XCTAssertEqual("shared-file", ShareValidation.safeShareName("a:b.txt"))
        XCTAssertEqual("shared-file", ShareValidation.safeShareName("a\u{0000}b.txt"))
    }

    func testStaleSpoolFilesUsesTheTtl() {
        let now: Int64 = 10_000_000
        let ttl = ShareValidation.SPOOL_TTL_MILLIS
        let entries = [
            SpoolEntry(name: "fresh.tmp", modifiedMillis: now - 1000),
            SpoolEntry(name: "at-edge.tmp", modifiedMillis: now - ttl),
            SpoolEntry(name: "stale.tmp", modifiedMillis: now - ttl - 1),
            SpoolEntry(name: "ancient.tmp", modifiedMillis: 0),
        ]
        XCTAssertEqual(["stale.tmp", "ancient.tmp"], ShareValidation.staleSpoolFiles(entries, now: now, ttlMillis: ttl))
    }

    func testPickerDisablesPeersWithoutPushPermission() {
        let rows = ShareValidation.shareTargets([peer(String(repeating: "aa", count: 32), push: false)], onlineFingerprints: [String(repeating: "aa", count: 32)])
        XCTAssertEqual(1, rows.count)
        XCTAssertFalse(rows[0].enabled)
        XCTAssertEqual("Has not allowed files from you", rows[0].reason)
    }

    func testPickerDisablesOfflinePeers() {
        let rows = ShareValidation.shareTargets([peer(String(repeating: "bb", count: 32))], onlineFingerprints: [])
        XCTAssertFalse(rows[0].enabled)
        XCTAssertEqual("Offline", rows[0].reason)
    }

    func testPickerEnablesOnlinePermittedPeers() {
        let rows = ShareValidation.shareTargets([peer(String(repeating: "CC", count: 32))], onlineFingerprints: [String(repeating: "cc", count: 32)])
        XCTAssertTrue(rows[0].enabled)
        XCTAssertNil(rows[0].reason)
    }

    func testPushPermissionIsCheckedBeforeOnline() {
        let rows = ShareValidation.shareTargets([peer(String(repeating: "dd", count: 32), push: false)], onlineFingerprints: [])
        XCTAssertEqual("Has not allowed files from you", rows[0].reason)
    }
}
