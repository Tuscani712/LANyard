import XCTest
@testable import LanyardCore

final class TrustStoreTests: XCTestCase {
    private func store() -> (JsonFileTrustStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("trust-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("peers.json")
        return (JsonFileTrustStore(file: file), file)
    }

    private func peer(_ fp: String, _ name: String = "peer") -> PairedPeer {
        PairedPeer(
            fingerprint: fp, name: name, host: "10.0.0.2", port: 47800,
            browse: true, push: false, pairedAt: 1_700_000_000_000
        )
    }

    func testSavesFindsAndLists() {
        let (store, _) = store()
        store.save(peer(String(repeating: "AA", count: 32), "alpha"))
        store.save(peer(String(repeating: "bb", count: 32), "beta"))

        let found = store.find(String(repeating: "AA", count: 32))
        XCTAssertEqual("alpha", found?.name)
        // Fingerprints are normalized to lowercase on save and lookup.
        XCTAssertEqual(String(repeating: "bb", count: 32), store.find(String(repeating: "BB", count: 32))?.fingerprint)
        XCTAssertEqual(2, store.list().count)
    }

    func testSaveReplacesSameFingerprint() {
        let (store, _) = store()
        store.save(peer(String(repeating: "cc", count: 32), "first"))
        store.save(peer(String(repeating: "CC", count: 32), "second"))
        XCTAssertEqual(1, store.list().count)
        XCTAssertEqual("second", store.find(String(repeating: "cc", count: 32))?.name)
    }

    func testRemoveDeletes() {
        let (store, _) = store()
        store.save(peer(String(repeating: "dd", count: 32)))
        store.remove(String(repeating: "DD", count: 32))
        XCTAssertNil(store.find(String(repeating: "dd", count: 32)))
        XCTAssertTrue(store.list().isEmpty)
    }

    func testMissingFileReadsEmpty() {
        let (store, _) = store()
        XCTAssertTrue(store.list().isEmpty)
        XCTAssertNil(store.find(String(repeating: "ee", count: 32)))
    }

    func testCorruptFileReadsEmpty() {
        let (store, file) = store()
        try? "{ this is not json".write(to: file, atomically: true, encoding: .utf8)
        XCTAssertTrue(store.list().isEmpty)
    }

    func testWritesAtomicallyLeavingNoTempFile() {
        let (store, file) = store()
        store.save(peer(String(repeating: "ff", count: 32)))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        let leftovers = ((try? FileManager.default.contentsOfDirectory(atPath: file.deletingLastPathComponent().path)) ?? [])
            .filter { $0.hasSuffix(".tmp") }
        XCTAssertTrue(leftovers.isEmpty, "temp file(s) left behind: \(leftovers)")
    }

    func testRoundTripsAcrossInstances() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("trust2-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("peers.json")
        JsonFileTrustStore(file: file).save(peer(String(repeating: "11", count: 32), "persisted"))

        let reopened = JsonFileTrustStore(file: file)
        XCTAssertEqual("persisted", reopened.find(String(repeating: "11", count: 32))?.name)
        XCTAssertFalse(reopened.list().isEmpty)
    }
}
