import XCTest
import Crypto
@testable import LanyardCore

/// The phone-side receive path: validation, spooling, verification, placement.
/// Ported from the Kotlin `InboxReceiverTest`.
final class InboxReceiverTests: XCTestCase {
    private var spool: URL!
    private var placed: [String: Int64] = [:]

    override func setUp() {
        super.setUp()
        spool = FileManager.default.temporaryDirectory
            .appendingPathComponent("lanyard-spool-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: spool, withIntermediateDirectories: true)
        placed = [:]
    }

    override func tearDown() {
        try? FileManager.default.removeItem(at: spool)
        super.tearDown()
    }

    // MARK: - Helpers

    private final class ClosureDestination: PushDestination {
        private let body: (String, URL, Int64) throws -> String

        init(_ body: @escaping (String, URL, Int64) throws -> String) {
            self.body = body
        }

        func place(relPath: String, spool: URL, size: Int64) throws -> String {
            try body(relPath, spool, size)
        }
    }

    private func receiver(free: Int64 = 1 << 40) -> InboxReceiver {
        InboxReceiver(
            spoolRoot: spool,
            destination: ClosureDestination { [weak self] rel, f, _ in
                guard let self else { return rel }
                self.placed[rel] = self.fileSize(f)
                return rel.split(separator: "/").last.map(String.init) ?? rel
            },
            freeBytes: { free }
        )
    }

    private func req(_ rel: String, _ size: Int64) -> PushFileRequest {
        PushFileRequest(relPath: rel, size: size, mtimeMillis: 0)
    }

    private func sha(_ bytes: [UInt8]) -> String {
        Hex.encode(SHA256.hash(data: Data(bytes)))
    }

    private func fileSize(_ url: URL) -> Int64 {
        (try? url.resourceValues(forKeys: [.fileSizeKey]).fileSize).map(Int64.init) ?? 0
    }

    private func walk(_ root: URL) -> [URL] {
        guard let enumerator = FileManager.default.enumerator(
            at: root,
            includingPropertiesForKeys: [.isRegularFileKey],
            options: [],
            errorHandler: { _, _ in true }
        ) else { return [] }
        var out: [URL] = []
        for case let url as URL in enumerator {
            if (try? url.resourceValues(forKeys: [.isRegularFileKey]).isRegularFile) == true {
                out.append(url)
            }
        }
        return out
    }

    /// Deterministic byte source with no backing storage.
    private final class GenInput: ByteSource {
        private var left: Int64

        init(_ left: Int64) {
            self.left = left
        }

        func read(_ buffer: inout [UInt8], offset: Int, count: Int) -> Int {
            if left <= 0 { return -1 }
            let n = Int(min(Int64(count), left))
            for i in 0..<n {
                buffer[offset + i] = UInt8(truncatingIfNeeded: (left - Int64(i)) % 251)
            }
            left -= Int64(n)
            return n
        }
    }

    private func genSha(_ total: Int64) -> String {
        var digest = SHA256()
        let input = GenInput(total)
        var buf = [UInt8](repeating: 0, count: 256 * 1024)
        while true {
            let n = input.read(&buf, offset: 0, count: buf.count)
            if n < 0 { break }
            digest.update(data: buf[0..<n])
        }
        return Hex.encode(digest.finalize())
    }

    private func code(_ error: Error) -> Int? {
        (error as? PeerHttpException)?.code
    }

    // MARK: - Tests

    func testAcceptsAndReportsOffsets() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "peer", peerName: "Peer", reqs: [req("a.bin", 3), req("dir/b.bin", 5)], totalBytes: 0, maxBytes: 0)
        XCTAssertTrue(o.accepted)
        XCTAssertEqual(o.offsets, ["a.bin": 0, "dir/b.bin": 0])
    }

    func testRejectsBadNamesAndDuplicates() {
        let r = receiver()
        XCTAssertThrowsError(try r.offer(peerFp: "p", peerName: "P", reqs: [req("../x", 1)], totalBytes: 0, maxBytes: 0))
        XCTAssertThrowsError(try r.offer(peerFp: "p", peerName: "P", reqs: [req("a", 1), req("a", 1)], totalBytes: 0, maxBytes: 0))
    }

    func testRejectsOverSizeAndLowSpace() {
        let r = receiver()
        XCTAssertThrowsError(try r.offer(peerFp: "p", peerName: "P", reqs: [req("a", 100)], totalBytes: 0, maxBytes: 50)) {
            XCTAssertEqual(self.code($0), 413)
        }
        let need = PushProtocol.requiredFreeSpace(total: 100, largestFile: 100)
        XCTAssertThrowsError(try self.receiver(free: need - 1).offer(peerFp: "p", peerName: "P", reqs: [self.req("a", 100)], totalBytes: 0, maxBytes: 0)) {
            XCTAssertEqual(self.code($0), 507)
        }
    }

    func testWriteChunkThenCompleteVerifiesAndPlaces() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 4)], totalBytes: 0, maxBytes: 0)
        let data: [UInt8] = [1, 2, 3, 4]
        try r.writeChunk(id: o.pushId, peerFp: "p", rel: "a.bin", offset: 0, data: Data(data))
        try r.complete(id: o.pushId, peerFp: "p", rel: "a.bin", sha256: sha(data))
        XCTAssertEqual(placed["a.bin"], Int64(4))
        XCTAssertFalse(walk(spool).contains { $0.lastPathComponent.hasSuffix(".lanpart") }, "spool part should be gone")
    }

    func testWrongChecksumIsRefused() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 4)], totalBytes: 0, maxBytes: 0)
        try r.writeChunk(id: o.pushId, peerFp: "p", rel: "a.bin", offset: 0, data: Data([1, 2, 3, 4]))
        XCTAssertThrowsError(try r.complete(id: o.pushId, peerFp: "p", rel: "a.bin", sha256: String(repeating: "00", count: 32)))
    }

    func testWholeFileFastPath() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 3)], totalBytes: 0, maxBytes: 0)
        let data: [UInt8] = [9, 8, 7]
        try r.receiveWhole(id: o.pushId, peerFp: "p", rel: "a.bin", sha256: sha(data), data: Data(data))
        XCTAssertEqual(placed["a.bin"], Int64(3))
    }

    func testCancelTurnsFurtherWritesInto410() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 10)], totalBytes: 0, maxBytes: 0)
        XCTAssertTrue(r.cancel(id: o.pushId, peerFp: "p"))
        XCTAssertTrue(r.wasCancelled(o.pushId))
        XCTAssertThrowsError(try r.writeChunk(id: o.pushId, peerFp: "p", rel: "a.bin", offset: 0, data: Data([1]))) {
            XCTAssertEqual(self.code($0), 410)
        }
    }

    func testResumeReportsTheBytesAlreadyOnDisk() throws {
        let r = receiver()
        let first = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 10)], totalBytes: 0, maxBytes: 0)
        try r.writeChunk(id: first.pushId, peerFp: "p", rel: "a.bin", offset: 0, data: Data([1, 2, 3]))
        // A fresh receiver (as after an app restart) sees the peer-keyed part and
        // offers it back as resume offset.
        let second = try receiver().offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 10)], totalBytes: 0, maxBytes: 0)
        XCTAssertEqual(second.offsets["a.bin"], Int64(3))
    }

    func testOversizedStreamIsStoppedAtTheCap() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 4)], totalBytes: 0, maxBytes: 0)
        XCTAssertThrowsError(
            // 100 bytes offered as 4: must be caught while reading.
            try r.writeChunk(id: o.pushId, peerFp: "p", rel: "a.bin", offset: 0, data: Data(repeating: 0, count: 100))
        ) {
            XCTAssertEqual(self.code($0), 400)
        }
        XCTAssertFalse(walk(spool).contains { $0.lastPathComponent.hasSuffix(".lanpart") && self.fileSize($0) > 4 })
    }

    func testOffsetPastTheSizeIsRefused() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 4)], totalBytes: 0, maxBytes: 0)
        XCTAssertThrowsError(try r.writeChunk(id: o.pushId, peerFp: "p", rel: "a.bin", offset: 5, data: Data([1]))) {
            XCTAssertEqual(self.code($0), 400)
        }
    }

    func testSameNameFromDifferentPeersUsesSeparateSpool() throws {
        let r = receiver()
        let a = try r.offer(peerFp: "peerA", peerName: "A", reqs: [req("photo.jpg", 3)], totalBytes: 0, maxBytes: 0)
        let b = try r.offer(peerFp: "peerB", peerName: "B", reqs: [req("photo.jpg", 3)], totalBytes: 0, maxBytes: 0)
        try r.writeChunk(id: a.pushId, peerFp: "peerA", rel: "photo.jpg", offset: 0, data: Data([1, 1, 1]))
        try r.writeChunk(id: b.pushId, peerFp: "peerB", rel: "photo.jpg", offset: 0, data: Data([2, 2, 2]))
        try r.complete(id: a.pushId, peerFp: "peerA", rel: "photo.jpg", sha256: sha([1, 1, 1]))
        try r.complete(id: b.pushId, peerFp: "peerB", rel: "photo.jpg", sha256: sha([2, 2, 2]))
        XCTAssertEqual(placed["photo.jpg"], Int64(3))
    }

    func testSecondInFlightSamePeerAndNameIsRefused() throws {
        let r = receiver()
        _ = try r.offer(peerFp: "peerA", peerName: "A", reqs: [req("photo.jpg", 3)], totalBytes: 0, maxBytes: 0)
        XCTAssertThrowsError(try r.offer(peerFp: "peerA", peerName: "A", reqs: [req("photo.jpg", 3)], totalBytes: 0, maxBytes: 0)) {
            XCTAssertEqual(self.code($0), 409)
        }
    }

    func testStreamsALargeFileWithoutBufferingIt() throws {
        let r = receiver()
        let total: Int64 = 256 * 1024 * 1024 // 256 MB, far larger than any test buffer
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("big.bin", total)], totalBytes: 0, maxBytes: 0)
        // A generator stream: the test never materializes the bytes.
        let written = try r.writeChunk(id: o.pushId, peerFp: "p", rel: "big.bin", offset: 0, source: GenInput(total))
        XCTAssertEqual(written, total)
        let part = walk(spool).first { $0.lastPathComponent.hasSuffix(".lanpart") }
        XCTAssertEqual(part.map(fileSize), total)
        try r.complete(id: o.pushId, peerFp: "p", rel: "big.bin", sha256: genSha(total))
        XCTAssertEqual(placed["big.bin"], total)
    }

    func testReceiveCallbacksCarryThePushIdAndProgress() throws {
        var offers = [String]()
        var progress = [Int64]()
        var dones = [String]()
        let r = InboxReceiver(
            spoolRoot: spool,
            destination: ClosureDestination { rel, _, _ in rel },
            freeBytes: { 1 << 40 },
            onOffer: { pushId, _, _, _ in offers.append(pushId) },
            onProgress: { _, done, _ in progress.append(done) },
            onDone: { pushId, _, _, _ in dones.append(pushId) }
        )
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 4)], totalBytes: 0, maxBytes: 0)
        try r.writeChunk(id: o.pushId, peerFp: "p", rel: "a.bin", offset: 0, data: Data([1, 2, 3, 4]))
        _ = r.finish(id: o.pushId, peerFp: "p")
        XCTAssertEqual(offers, [o.pushId])
        XCTAssertEqual(dones, [o.pushId])
        XCTAssertTrue(!progress.isEmpty && progress.last == 4, "progress should report the bytes written: \(progress)")
    }

    func testClearAbandonedSpoolKeepsLivePartsAndDropsLeftovers() throws {
        let r = receiver()
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("live.bin", 10)], totalBytes: 0, maxBytes: 0)
        try r.writeChunk(id: o.pushId, peerFp: "p", rel: "live.bin", offset: 0, data: Data([1, 2, 3]))
        // A leftover from a previous run (no in-memory session points at it).
        let stray = spool.appendingPathComponent("old-peer/dead.bin.lanpart")
        try? FileManager.default.createDirectory(at: stray.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? Data("junk".utf8).write(to: stray)

        let removed = r.clearAbandonedSpool()

        XCTAssertEqual(removed, 1)
        XCTAssertFalse(FileManager.default.fileExists(atPath: stray.path), "an abandoned spool part must be cleared")
        XCTAssertTrue(
            walk(spool).contains { $0.lastPathComponent == "live.bin.lanpart" },
            "a part belonging to a live push must be kept"
        )
    }

    func testCancelNotifiesTheCallerWithThePushId() throws {
        var cancelled = [String]()
        let r = InboxReceiver(
            spoolRoot: spool,
            destination: ClosureDestination { rel, _, _ in rel },
            freeBytes: { 1 << 40 },
            onCancelled: { pushId, _ in cancelled.append(pushId) }
        )
        let o = try r.offer(peerFp: "p", peerName: "P", reqs: [req("a.bin", 4)], totalBytes: 0, maxBytes: 0)
        XCTAssertTrue(r.cancel(id: o.pushId, peerFp: "p"))
        XCTAssertEqual(cancelled, [o.pushId], "a cancelled push must be reported so its row does not stay Running")
    }
}
