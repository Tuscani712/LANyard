import XCTest
import Crypto
@testable import LanyardCore

private func sha(_ bytes: [UInt8]) -> String {
    Hex.encode(SHA256.hash(data: Data(bytes)))
}

/// The download state machine, driven through the `ShareReader`/`ByteSink`
/// seams. The original Kotlin `DownloadSessionTest` pairs a real Go peer with
/// fakes; the live-peer tests are dropped (TLS/sockets are not ported), while
/// the fake-reader paths — traversal rejection, hash mismatch, resume, cancel,
/// 410/error mapping — are reproduced here with an in-memory reader.
final class DownloadSessionTests: XCTestCase {

    // MARK: - Helpers

    private struct MsgError: LocalizedError {
        let msg: String
        var errorDescription: String? { msg }
    }

    private func pattern(_ seed: UInt8, _ count: Int) -> [UInt8] {
        (0..<count).map { UInt8(($0 &+ Int(seed)) & 0xFF) }
    }

    private func manifest(_ files: [(path: String, name: String, size: Int64)]) -> Data {
        let arr: [[String: Any]] = files.map {
            ["path": $0.path, "name": $0.name, "size": $0.size, "etag": ""]
        }
        let obj: [String: Any] = [
            "files": arr,
            "total_bytes": files.reduce(Int64(0)) { $0 + $1.size },
            "count": files.count,
        ]
        return try! JSONSerialization.data(withJSONObject: obj)
    }

    private final class ClosureByteSink: ByteSink {
        private let body: ([UInt8]) throws -> Void
        init(_ body: @escaping ([UInt8]) throws -> Void) { self.body = body }
        func write(_ bytes: [UInt8], offset: Int, count: Int) throws {
            if count > 0 { try body(Array(bytes[offset..<offset + count])) }
        }
        func close() throws {}
    }

    /// In-memory stand-in for the caller's folder; `openAt(0)` truncates, a
    /// non-zero offset appends (a resume).
    private final class Store {
        var files: [String: [UInt8]] = [:]
    }

    private func targetFor(_ store: Store, openExisting: Bool = true) -> (ManifestFile) -> DownloadTarget {
        { file in
            let key = file.path
            let existing = store.files[key] ?? []
            return DownloadTarget(
                existingSize: Int64(existing.count),
                openAt: { offset in
                    if offset == 0 { store.files[key] = [] }
                    return ClosureByteSink { chunk in
                        store.files[key, default: []].append(contentsOf: chunk)
                    }
                },
                openExisting: openExisting ? { DataByteSource(store.files[key] ?? []) } : nil
            )
        }
    }

    private final class FakeShareReader: ShareReader {
        var manifestData: Data
        var manifestError: Error?
        var openError: Error?
        var hashError: Error?
        var reportError: Error?
        var fileBytes: [String: [UInt8]]
        var corruptPath: String?
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
            if corruptPath == path, !bytes.isEmpty { bytes[0] ^= 0xFF }
            return DataByteSource(bytes)
        }

        func wholeFileHash(shareId: String, path: String) throws -> String {
            if let e = hashError { throw e }
            return sha(fileBytes[path] ?? [])
        }

        func reportComplete(shareId: String, verified: [(path: String, sha256: String)]) throws -> Bool {
            if let e = reportError { throw e }
            reported = verified
            return true
        }
    }

    // MARK: - Path safety

    func testRejectsPathTraversalFromManifest() {
        let reader = FakeShareReader(
            manifest: manifest([(path: "../evil.txt", name: "evil.txt", size: 1)])
        )
        let result = DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store()))
        XCTAssertEqual(result, .unsafePath)
    }

    func testRejectsAbsoluteAndEmptySegmentPaths() {
        XCTAssertTrue(DownloadSession.isSafeRelPath(""))
        XCTAssertTrue(DownloadSession.isSafeRelPath("a/b.txt"))
        XCTAssertFalse(DownloadSession.isSafeRelPath("/abs.txt"))
        XCTAssertFalse(DownloadSession.isSafeRelPath("a//b.txt"))
        XCTAssertFalse(DownloadSession.isSafeRelPath("a/../b"))
        XCTAssertFalse(DownloadSession.isSafeRelPath("a/./b"))
        XCTAssertFalse(DownloadSession.isSafeRelPath("a\\b"))
        XCTAssertFalse(DownloadSession.isSafeRelPath("a/C:/b"))
    }

    // MARK: - Happy path

    func testDownloadsAndVerifiesEachFile() {
        let a = pattern(1, 200)
        let b = pattern(2, 333)
        let reader = FakeShareReader(
            manifest: manifest([
                (path: "sub/a.bin", name: "a.bin", size: Int64(a.count)),
                (path: "b.bin", name: "b.bin", size: Int64(b.count)),
            ]),
            files: ["sub/a.bin": a, "b.bin": b]
        )
        let store = Store()
        var progress: [(Int, Int64, Int64)] = []

        let result = DownloadSession(reader: reader).download(
            shareId: "s", path: "",
            targetFor: targetFor(store),
            onProgress: { index, _, received, total in progress.append((index, received, total)) }
        )

        XCTAssertEqual(result, .done(files: 2, bytes: Int64(a.count + b.count)))
        XCTAssertEqual(store.files["sub/a.bin"], a)
        XCTAssertEqual(store.files["b.bin"], b)
        XCTAssertEqual(reader.reported.count, 2)
        XCTAssertEqual(reader.reported[0].sha256, sha(a))
        XCTAssertEqual(progress.last?.2, Int64(b.count))
    }

    func testEmptyManifestIsDoneWithZeroFiles() {
        let reader = FakeShareReader(manifest: manifest([]))
        let result = DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store()))
        XCTAssertEqual(result, .done(files: 0, bytes: 0))
    }

    func testHashMismatchIsDetected() {
        let real = pattern(7, 64)
        let reader = FakeShareReader(
            manifest: manifest([(path: "hello.txt", name: "hello.txt", size: Int64(real.count))]),
            files: ["hello.txt": real]
        )
        reader.corruptPath = "hello.txt"
        let result = DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store()))
        XCTAssertEqual(result, .hashMismatch("hello.txt"))
    }

    // MARK: - Resume

    func testResumesFromExistingHalfHashingPrefix() {
        let full = pattern(9, 5000)
        let half = Array(full[0..<2500])
        let reader = FakeShareReader(
            manifest: manifest([(path: "half.bin", name: "half.bin", size: Int64(full.count))]),
            files: ["half.bin": full]
        )
        let store = Store()
        store.files["half.bin"] = half

        let result = DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(store))

        XCTAssertEqual(result, .done(files: 1, bytes: Int64(full.count - half.count)))
        XCTAssertEqual(store.files["half.bin"], full, "the resumed file must be whole")
        XCTAssertEqual(reader.lastRangeFrom, Int64(half.count), "must request the remainder")
    }

    func testResumeWithoutOpenExistingRestartsFromZero() {
        let full = pattern(3, 400)
        let half = Array(full[0..<200])
        let reader = FakeShareReader(
            manifest: manifest([(path: "half.bin", name: "half.bin", size: Int64(full.count))]),
            files: ["half.bin": full]
        )
        let store = Store()
        store.files["half.bin"] = half

        let result = DownloadSession(reader: reader).download(
            shareId: "s", path: "", targetFor: targetFor(store, openExisting: false)
        )

        XCTAssertEqual(result, .done(files: 1, bytes: Int64(full.count)))
        XCTAssertEqual(store.files["half.bin"], full)
        XCTAssertEqual(reader.lastRangeFrom, 0, "without a prefix hash the offset resets to 0")
    }

    func testResumeOffsetClampedToFileSize() {
        let reader = FakeShareReader(
            manifest: manifest([(path: "a.bin", name: "a.bin", size: 10)]),
            files: ["a.bin": pattern(4, 10)]
        )
        var seenOffset: Int64 = -1
        let target = DownloadTarget(
            existingSize: 999, // larger than the file
            openAt: { offset in
                seenOffset = offset
                return ClosureByteSink { _ in }
            },
            openExisting: { DataByteSource([]) }
        )
        _ = DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: { _ in target })
        XCTAssertEqual(seenOffset, 10)
    }

    // MARK: - Cancellation

    func testCancelMidTransferStops() {
        let big = pattern(5, 4 * 1024 * 1024)
        let reader = FakeShareReader(
            manifest: manifest([(path: "big.bin", name: "big.bin", size: Int64(big.count))]),
            files: ["big.bin": big]
        )
        var cancel = false
        let result = DownloadSession(reader: reader).download(
            shareId: "s", path: "",
            targetFor: targetFor(Store()),
            onProgress: { _, _, _, _ in cancel = true },
            isCancelled: { cancel }
        )
        XCTAssertEqual(result, .cancelled)
    }

    // MARK: - Error mapping

    func testShareEndedWhenManifestGone() {
        let reader = FakeShareReader(manifest: manifest([]))
        reader.manifestError = PeerHttpException(410, "gone")
        XCTAssertEqual(
            DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store())),
            .shareEnded
        )
    }

    func testShareEndedWhenFileGone() {
        let reader = FakeShareReader(
            manifest: manifest([(path: "a.bin", name: "a.bin", size: 5)]),
            files: ["a.bin": pattern(1, 5)]
        )
        reader.openError = PeerHttpException(410, "gone")
        XCTAssertEqual(
            DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store())),
            .shareEnded
        )
    }

    func testShareEndedWhenHashGone() {
        let reader = FakeShareReader(
            manifest: manifest([(path: "a.bin", name: "a.bin", size: 5)]),
            files: ["a.bin": pattern(1, 5)]
        )
        reader.hashError = PeerHttpException(410, "gone")
        XCTAssertEqual(
            DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store())),
            .shareEnded
        )
    }

    func testPeerUnreachableOnManifestTransportError() {
        let reader = FakeShareReader(manifest: manifest([]))
        reader.manifestError = MsgError(msg: "nope")
        XCTAssertEqual(
            DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store())),
            .peerUnreachable
        )
    }

    func testPeerUnreachableOnFileTransportError() {
        let reader = FakeShareReader(
            manifest: manifest([(path: "a.bin", name: "a.bin", size: 5)]),
            files: ["a.bin": pattern(1, 5)]
        )
        reader.openError = MsgError(msg: "nope")
        XCTAssertEqual(
            DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store())),
            .peerUnreachable
        )
    }

    func testReportCompleteFailureStillDone() {
        let data = pattern(6, 50)
        let reader = FakeShareReader(
            manifest: manifest([(path: "a.bin", name: "a.bin", size: Int64(data.count))]),
            files: ["a.bin": data]
        )
        reader.reportError = MsgError(msg: "report failed")
        let result = DownloadSession(reader: reader).download(shareId: "s", path: "", targetFor: targetFor(Store()))
        XCTAssertEqual(result, .done(files: 1, bytes: Int64(data.count)))
    }
}
