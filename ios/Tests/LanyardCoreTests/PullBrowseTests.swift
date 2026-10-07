import XCTest
import Crypto
@testable import LanyardCore

private func sha(_ bytes: [UInt8]) -> String { Hex.encode(SHA256.hash(data: Data(bytes))) }

/// `PullBrowse`, the browse-and-download model.
///
/// The download half is the existing `DownloadSession` code path, whose cases
/// (resume, hash mismatch, traversal, 410/error mapping, cancel) are already
/// covered by `DownloadSessionTests`; this file covers the browse/list/select
/// rules and that a chosen subset reaches that shared path. The Kotlin
/// `DownloadSessionTest` is mostly a live Go-peer test; its live cases are not
/// ported (TLS/sockets are not), and its fake-manifest cases are reused above.
final class PullBrowseTests: XCTestCase {

    private struct MsgError: LocalizedError {
        let msg: String
        var errorDescription: String? { msg }
    }

    private final class FakeReader: ShareReader {
        var manifestData: Data
        var manifestError: Error?
        var openError: Error?
        var hashError: Error?
        var fileBytes: [String: [UInt8]]
        var reported: [(path: String, sha256: String)] = []
        var lastRangeFrom: Int64 = -1

        init(manifest: Data, files: [String: [UInt8]] = [:]) {
            self.manifestData = manifest
            self.fileBytes = files
        }

        func manifestFiles(shareId: String, path: String) throws -> Data {
            if let e = manifestError { throw e }
            return manifestData
        }

        func openFileStream(shareId: String, path: String, rangeFrom: Int64) throws -> ByteSource {
            lastRangeFrom = rangeFrom
            if let e = openError { throw e }
            var bytes = fileBytes[path] ?? []
            if rangeFrom > 0 { bytes = Array(bytes[min(Int(rangeFrom), bytes.count)...]) }
            return DataByteSource(bytes)
        }

        func wholeFileHash(shareId: String, path: String) throws -> String {
            if let e = hashError { throw e }
            return sha(fileBytes[path] ?? [])
        }

        func reportComplete(shareId: String, verified: [(path: String, sha256: String)]) throws -> Bool {
            reported = verified
            return true
        }
    }

    private final class FakeBrowser: BrowseClient {
        var sharesData: Data
        var treeData: Data
        var treeError: Error?

        init(sharesData: Data = Data("[]".utf8), treeData: Data = Data("[]".utf8)) {
            self.sharesData = sharesData
            self.treeData = treeData
        }

        func listShares() throws -> Data { sharesData }
        func tree(shareId: String, path: String) throws -> Data {
            if let e = treeError { throw e }
            return treeData
        }
    }

    private func pattern(_ seed: UInt8, _ count: Int) -> [UInt8] {
        (0..<count).map { UInt8(($0 &+ Int(seed)) & 0xFF) }
    }

    private func manifest(_ files: [(path: String, name: String, size: Int64)]) -> Data {
        let arr: [[String: Any]] = files.map {
            ["path": $0.path, "name": $0.name, "size": $0.size, "etag": ""]
        }
        return try! JSONSerialization.data(withJSONObject: [
            "files": arr,
            "total_bytes": files.reduce(Int64(0)) { $0 + $1.size },
            "count": files.count,
        ])
    }

    private final class ClosureByteSink: ByteSink {
        private let body: ([UInt8]) throws -> Void
        init(_ body: @escaping ([UInt8]) throws -> Void) { self.body = body }
        func write(_ bytes: [UInt8], offset: Int, count: Int) throws {
            if count > 0 { try body(Array(bytes[offset..<offset + count])) }
        }
        func close() throws {}
    }

    private final class Store { var files: [String: [UInt8]] = [:] }

    private func targetFor(_ store: Store) -> (ManifestFile) -> DownloadTarget {
        { file in
            let key = file.path
            let existing = store.files[key] ?? []
            return DownloadTarget(
                existingSize: Int64(existing.count),
                openAt: { offset in
                    if offset == 0 { store.files[key] = [] }
                    return ClosureByteSink { chunk in store.files[key, default: []].append(contentsOf: chunk) }
                },
                openExisting: { DataByteSource(store.files[key] ?? []) }
            )
        }
    }

    private func browse(_ reader: FakeReader, _ browser: FakeBrowser) -> PullBrowse {
        PullBrowse(reader: reader, browser: browser)
    }

    // MARK: - Browse parsing

    func testParsesTheShareList() throws {
        let json = try JSONSerialization.data(withJSONObject: [
            ["share_id": "s1", "label": "Docs", "kind": "folder", "lifetime": "timed", "size": 42],
            ["share_id": "s2", "label": "Photo"],
        ])
        let shares = try browse(FakeReader(manifest: Data("{}".utf8)), FakeBrowser(sharesData: json)).shares()
        XCTAssertEqual(shares.count, 2)
        XCTAssertEqual(shares[0], BrowseShare(id: "s1", label: "Docs", name: "", kind: "folder", size: 42, lifetime: "timed"))
        XCTAssertEqual(shares[1].lifetime, "until_stopped", "a missing lifetime defaults to until_stopped")
    }

    func testParsesTheTree() throws {
        let json = try JSONSerialization.data(withJSONObject: [
            ["name": "sub", "path": "sub", "is_dir": true, "size": 0],
            ["name": "a.txt", "path": "sub/a.txt", "is_dir": false, "size": 12],
        ])
        let entries = try browse(FakeReader(manifest: Data("{}".utf8)), FakeBrowser(treeData: json)).tree(shareId: "s1", path: "")
        XCTAssertEqual(entries[0], BrowseEntry(name: "sub", path: "sub", isDir: true, size: 0))
        XCTAssertEqual(entries[1], BrowseEntry(name: "a.txt", path: "sub/a.txt", isDir: false, size: 12))
    }

    func testTreeErrorPropagates() {
        let browser = FakeBrowser()
        browser.treeError = MsgError(msg: "no")
        XCTAssertThrowsError(try browse(FakeReader(manifest: Data("{}".utf8)), browser).tree(shareId: "s1", path: ""))
    }

    // MARK: - Selection

    func testPickKeepsEverythingWithoutAFilter() {
        let files = [
            ManifestFile(path: "a", name: "a", size: 1, etag: ""),
            ManifestFile(path: "b", name: "b", size: 1, etag: ""),
        ]
        let browse = browse(FakeReader(manifest: Data("{}".utf8)), FakeBrowser())
        XCTAssertEqual(browse.pick(files, include: nil).map(\.path), ["a", "b"])
        XCTAssertEqual(browse.pick(files, include: ["b"]).map(\.path), ["b"])
        XCTAssertTrue(browse.pick(files, include: []).isEmpty)
        XCTAssertTrue(browse.pick(files, include: ["missing"]).isEmpty)
    }

    // MARK: - Download delegation

    func testDownloadOnlyFetchesTheSelectedFiles() {
        let a = pattern(1, 100)
        let b = pattern(2, 200)
        let reader = FakeReader(
            manifest: manifest([
                (path: "a.bin", name: "a.bin", size: Int64(a.count)),
                (path: "b.bin", name: "b.bin", size: Int64(b.count)),
            ]),
            files: ["a.bin": a, "b.bin": b]
        )
        let store = Store()
        let result = browse(reader, FakeBrowser()).download(
            shareId: "s1", path: "", include: ["a.bin"], targetFor: targetFor(store)
        )
        XCTAssertEqual(result, .done(files: 1, bytes: Int64(a.count)))
        XCTAssertEqual(store.files["a.bin"], a)
        XCTAssertNil(store.files["b.bin"], "an unselected file must not be downloaded")
        XCTAssertEqual(reader.reported.map(\.path), ["a.bin"])
    }

    func testDownloadWithoutAFilterFetchesEveryFile() {
        let a = pattern(1, 10)
        let b = pattern(2, 20)
        let reader = FakeReader(
            manifest: manifest([
                (path: "a.bin", name: "a.bin", size: 10),
                (path: "b.bin", name: "b.bin", size: 20),
            ]),
            files: ["a.bin": a, "b.bin": b]
        )
        let store = Store()
        let result = browse(reader, FakeBrowser()).download(shareId: "s1", path: "", targetFor: targetFor(store))
        XCTAssertEqual(result, .done(files: 2, bytes: 30))
        XCTAssertEqual(store.files["a.bin"], a)
        XCTAssertEqual(store.files["b.bin"], b)
    }

    func testDownloadRejectsATraversingManifest() {
        let reader = FakeReader(manifest: manifest([(path: "../evil", name: "evil", size: 1)]))
        let result = browse(reader, FakeBrowser()).download(shareId: "s1", path: "", targetFor: targetFor(Store()))
        XCTAssertEqual(result, .unsafePath)
    }

    func testManifestGoneIsShareEnded() {
        let reader = FakeReader(manifest: Data("[]".utf8))
        reader.manifestError = PeerHttpException(410, "gone")
        XCTAssertEqual(
            browse(reader, FakeBrowser()).download(shareId: "s1", path: "", targetFor: targetFor(Store())),
            .shareEnded
        )
    }

    func testTransportErrorIsPeerUnreachable() {
        let reader = FakeReader(manifest: Data("[]".utf8))
        reader.manifestError = MsgError(msg: "nope")
        XCTAssertEqual(
            browse(reader, FakeBrowser()).download(shareId: "s1", path: "", targetFor: targetFor(Store())),
            .peerUnreachable
        )
    }

    func testCancelStopsTheDownload() {
        let big = pattern(5, 4 * 1024 * 1024)
        let reader = FakeReader(
            manifest: manifest([(path: "big.bin", name: "big.bin", size: Int64(big.count))]),
            files: ["big.bin": big]
        )
        var cancel = false
        let result = browse(reader, FakeBrowser()).download(
            shareId: "s1", path: "", targetFor: targetFor(Store()),
            onProgress: { _, _, _, _ in cancel = true },
            isCancelled: { cancel }
        )
        XCTAssertEqual(result, .cancelled)
    }

    func testManifestParsesWithResumeRangeForwarded() {
        let full = pattern(3, 400)
        let half = Array(full[0..<200])
        let reader = FakeReader(
            manifest: manifest([(path: "half.bin", name: "half.bin", size: Int64(full.count))]),
            files: ["half.bin": full]
        )
        let store = Store()
        store.files["half.bin"] = half
        let result = browse(reader, FakeBrowser()).download(shareId: "s1", path: "", targetFor: targetFor(store))
        XCTAssertEqual(result, .done(files: 1, bytes: Int64(full.count - half.count)))
        XCTAssertEqual(store.files["half.bin"], full)
        XCTAssertEqual(reader.lastRangeFrom, Int64(half.count), "resume requests the remainder")
    }
}
