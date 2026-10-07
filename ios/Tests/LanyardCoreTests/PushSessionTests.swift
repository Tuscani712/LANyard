import XCTest
import Crypto
@testable import LanyardCore

private func drainAll(_ source: ByteSource) -> [UInt8] {
    var out: [UInt8] = []
    var buf = [UInt8](repeating: 0, count: 4096)
    while true {
        let n = source.read(&buf, offset: 0, count: buf.count)
        if n < 0 { break }
        out.append(contentsOf: buf[0..<n])
    }
    return out
}

/// The push state machine, driven through the `PushClient` seam. The original
/// Kotlin `PushSessionTest` runs against a real Go peer (skipped without
/// `LANYARD_BIN`); these tests keep the pure, deterministic paths — offer
/// acceptance, status/error mapping, offsets, progress, cancellation — by
/// substituting a fake client, since TLS/sockets are not ported.
final class PushSessionTests: XCTestCase {

    // MARK: - Helpers

    private struct MsgError: LocalizedError {
        let msg: String
        var errorDescription: String? { msg }
    }

    private func bytes(_ seed: UInt8, _ count: Int) -> [UInt8] {
        (0..<count).map { UInt8(($0 &+ Int(seed)) & 0xFF) }
    }

    private final class FakePushClient: PushClient {
        var offer: PushOffer
        var offerError: Error?
        var streamError: Error?
        var completeFileError: Error?
        var completeAllError: Error?

        var offeredFiles: [PushFileRequest] = []
        var streamCalls: [(pushId: String, relPath: String, offset: Int64, total: Int64)] = []
        var completedFiles: [(pushId: String, relPath: String, sha256: String)] = []
        var completeAllCount = 0
        var onStream: (() -> Void)?

        /// If false, the fake returns a fixed byte count instead of draining the
        /// source (used for resume/offset tests that need no real bytes).
        var drainSource = true

        init(offer: PushOffer = PushOffer(pushId: "push-1", accepted: true, maxBytes: 0, offsets: [:])) {
            self.offer = offer
        }

        func pushOffer(_ files: [PushFileRequest]) throws -> PushOffer {
            offeredFiles = files
            if let e = offerError { throw e }
            return offer
        }

        func pushFileStream(
            pushId: String,
            relPath: String,
            offset: Int64,
            total: Int64,
            source: ByteSource,
            onBytes: (Int64) -> Void,
            isCancelled: () -> Bool,
            throttle: Throttle
        ) throws -> PushFileResult {
            streamCalls.append((pushId, relPath, offset, total))
            onStream?()
            if let e = streamError { throw e }
            let data: [UInt8]
            if drainSource {
                data = drainAll(source)
            } else {
                data = []
            }
            let count = Int64(data.count)
            onBytes(count)
            return PushFileResult(sha256: Hex.encode(SHA256.hash(data: Data(data))), bytes: count)
        }

        func pushCompleteFile(_ pushId: String, _ relPath: String, _ sha256: String) throws {
            if let e = completeFileError { throw e }
            completedFiles.append((pushId, relPath, sha256))
        }

        func pushCompleteAll(_ pushId: String) throws {
            if let e = completeAllError { throw e }
            completeAllCount += 1
        }
    }

    // MARK: - Tests

    func testEmptySourcesFailsWithoutTalkingToPeer() {
        let client = FakePushClient()
        let result = PushSession(client: client).push(sources: [])
        XCTAssertEqual(result, .failed("nothing to send"))
        XCTAssertTrue(client.offeredFiles.isEmpty)
    }

    func testDeclinedOfferIsRefused() {
        let client = FakePushClient(
            offer: PushOffer(pushId: "p", accepted: false, maxBytes: 0, offsets: [:])
        )
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .refused)
        XCTAssertTrue(client.streamCalls.isEmpty, "a refused offer must not stream files")
    }

    func testForbiddenOfferIsRefused() {
        let client = FakePushClient()
        client.offerError = PeerHttpException(403, "not allowed")
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .refused)
    }

    func testForbiddenDuringStreamIsRefused() {
        let client = FakePushClient()
        client.streamError = PeerHttpException(403, "no")
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .refused)
    }

    func testGoneDuringStreamIsCancelledByReceiver() {
        let client = FakePushClient()
        client.streamError = PeerHttpException(410, "cancelled")
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .cancelledByReceiver)
    }

    func testOfferTransportErrorIsFailedWithMessage() {
        let client = FakePushClient()
        client.offerError = MsgError(msg: "the offer failed")
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .failed("the offer failed"))
    }

    func testPushTransportErrorIsFailedWithMessage() {
        let client = FakePushClient()
        client.streamError = MsgError(msg: "the push failed")
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .failed("the push failed"))
    }

    func testUnknownStatusMapsToHttpCode() {
        let client = FakePushClient()
        client.streamError = PeerHttpException(500, "boom")
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .failed("HTTP 500"))
    }

    func testCompleteFileFailureIsFailed() {
        let client = FakePushClient()
        client.completeFileError = MsgError(msg: "complete failed")
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.txt", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .failed("complete failed"))
    }

    func testSuccessfulPushSumsBytesAndRecordsCompletion() {
        let client = FakePushClient()
        let a = bytes(1, 100)
        let b = bytes(2, 250)
        var progress: [(Int, Int64, Int64)] = []

        let result = PushSession(client: client).push(
            sources: [
                PushSource(relPath: "a.bin", size: Int64(a.count), mtimeMillis: 11) { DataByteSource(a) },
                PushSource(relPath: "b.bin", size: Int64(b.count), mtimeMillis: 22) { DataByteSource(b) },
            ],
            onProgress: { index, sent, total in progress.append((index, sent, total)) }
        )

        XCTAssertEqual(result, .sent(files: 2, bytes: 350))
        XCTAssertEqual(client.offeredFiles.count, 2)
        XCTAssertEqual(client.offeredFiles[0], PushFileRequest(relPath: "a.bin", size: 100, mtimeMillis: 11))
        XCTAssertEqual(client.offeredFiles[1], PushFileRequest(relPath: "b.bin", size: 250, mtimeMillis: 22))
        XCTAssertEqual(client.completedFiles.count, 2)
        XCTAssertEqual(client.completedFiles[0].sha256, Hex.encode(SHA256.hash(data: Data(a))))
        XCTAssertEqual(client.completedFiles[0].relPath, "a.bin")
        XCTAssertEqual(client.completedFiles[1].sha256, Hex.encode(SHA256.hash(data: Data(b))))
        XCTAssertEqual(client.completeAllCount, 1)
        XCTAssertEqual(progress.last?.0, 1)
        XCTAssertEqual(progress.last?.1, 250)
        XCTAssertEqual(progress.last?.2, 250)
    }

    func testResumeOffsetFromOfferIsForwarded() {
        let client = FakePushClient(
            offer: PushOffer(pushId: "p", accepted: true, maxBytes: 0, offsets: ["a.bin": 7])
        )
        client.drainSource = false

        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "a.bin", size: 100, mtimeMillis: 0) { DataByteSource([]) }]
        )

        XCTAssertEqual(result, .sent(files: 1, bytes: 0))
        XCTAssertEqual(client.streamCalls.first?.offset, 7)
        XCTAssertEqual(client.streamCalls.first?.total, 100)
    }

    func testMissingOffsetDefaultsToZero() {
        let client = FakePushClient(
            offer: PushOffer(pushId: "p", accepted: true, maxBytes: 0, offsets: [:])
        )
        client.drainSource = false
        _ = PushSession(client: client).push(
            sources: [PushSource(relPath: "a.bin", size: 100, mtimeMillis: 0) { DataByteSource([]) }]
        )
        XCTAssertEqual(client.streamCalls.first?.offset, 0)
    }

    func testCancelledBeforeSecondFileStops() {
        let client = FakePushClient()
        var streamed = 0
        client.onStream = { streamed += 1 }
        // Cancel once the first file has streamed.
        let result = PushSession(client: client).push(
            sources: [
                PushSource(relPath: "a.bin", size: 10, mtimeMillis: 0) { DataByteSource(self.bytes(1, 10)) },
                PushSource(relPath: "b.bin", size: 10, mtimeMillis: 0) { DataByteSource(self.bytes(2, 10)) },
            ],
            isCancelled: { streamed >= 1 }
        )
        XCTAssertEqual(result, .cancelled)
        XCTAssertEqual(client.streamCalls.count, 1, "the second file must not start")
    }

    func testCancelledExceptionFromStreamIsCancelled() {
        let client = FakePushClient()
        client.streamError = PushCancelledException()
        let result = PushSession(client: client).push(
            sources: [PushSource(relPath: "x.bin", size: 5, mtimeMillis: 0) { DataByteSource(self.bytes(0, 5)) }]
        )
        XCTAssertEqual(result, .cancelled)
    }
}
