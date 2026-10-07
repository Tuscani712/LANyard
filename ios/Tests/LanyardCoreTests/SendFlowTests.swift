import XCTest
import Crypto
@testable import LanyardCore

private func drain(_ source: ByteSource) -> [UInt8] {
    var out: [UInt8] = []
    var buf = [UInt8](repeating: 0, count: 4096)
    while true {
        let n = source.read(&buf, offset: 0, count: buf.count)
        if n < 0 { break }
        out.append(contentsOf: buf[0..<n])
    }
    return out
}

/// `SendFlow`, the send view model.
///
/// There is no direct Kotlin test of a send view model (`TransferManager` is an
/// Android `object` wired to a foreground service), so these cases are newly
/// written from the documented behaviour of `TransferManager.enqueuePush` /
/// `runPush` / `finish` / `cancel`, with the `PushClient` seam and a fake store.
/// The `403`/`410`/`HTTP N` classification itself is `PushSession`'s and is
/// already covered by `PushSessionTests`.
final class SendFlowTests: XCTestCase {

    private let peer = PairedPeer(
        fingerprint: String(repeating: "aa", count: 32), name: "Desk",
        host: "127.0.0.1", port: 47800, browse: false, push: true, pairedAt: 0
    )

    // MARK: - Fixtures

    private final class MemoryStore: SendQueueStore {
        var loaded: [SendJob] = []
        private(set) var saved: [SendJob] = []
        func load() -> [SendJob] { loaded }
        func save(_ jobs: [SendJob]) { saved = jobs }
    }

    private func bytes(_ seed: UInt8, _ count: Int) -> [UInt8] {
        (0..<count).map { UInt8(($0 &+ Int(seed)) & 0xFF) }
    }

    private final class FakePushClient: PushClient {
        var offer: PushOffer
        var offerError: Error?
        var streamError: Error?
        var offeredFiles: [PushFileRequest] = []
        var streamCalls: [(pushId: String, relPath: String, offset: Int64, total: Int64)] = []
        var completeAllCount = 0
        var onStream: (() -> Void)?

        init(offer: PushOffer = PushOffer(pushId: "p", accepted: true, maxBytes: 0, offsets: [:])) {
            self.offer = offer
        }

        func pushOffer(_ files: [PushFileRequest]) throws -> PushOffer {
            offeredFiles = files
            if let e = offerError { throw e }
            return offer
        }

        func pushFileStream(
            pushId: String, relPath: String, offset: Int64, total: Int64,
            source: ByteSource, onBytes: (Int64) -> Void,
            isCancelled: () -> Bool, throttle: Throttle
        ) throws -> PushFileResult {
            streamCalls.append((pushId, relPath, offset, total))
            onStream?()
            onStream = nil
            if let e = streamError { throw e }
            let data = drain(source)
            onBytes(Int64(data.count))
            return PushFileResult(sha256: Hex.encode(SHA256.hash(data: Data(data))), bytes: Int64(data.count))
        }

        func pushCompleteFile(_ pushId: String, _ relPath: String, _ sha256: String) throws {}
        func pushCompleteAll(_ pushId: String) throws { completeAllCount += 1 }
    }

    private var store = MemoryStore()
    private var now: Int64 = 1_000_000
    private var client = FakePushClient()

    private func makeFlow(refusal: @escaping () -> String? = { nil }) -> SendFlow {
        SendFlow(
            store: store,
            clock: { self.now },
            refusal: refusal,
            clientFactory: { _ in self.client },
            sourceProvider: { file in
                PushSource(relPath: file.relPath, size: file.size, mtimeMillis: file.mtimeMillis) {
                    DataByteSource(self.bytes(UInt8(file.size & 0xFF), Int(file.size)))
                }
            }
        )
    }

    private func files(_ names: [String], size: Int64 = 4) -> [SendFile] {
        names.map { SendFile(relPath: $0, size: size, mtimeMillis: 7) }
    }

    // MARK: - Destination picking

    func testDestinationsReuseTheSharePickerRule() {
        let offline = PairedPeer(fingerprint: "bb", name: "Phone", host: "h", port: 1, browse: false, push: true, pairedAt: 0)
        let noPush = PairedPeer(fingerprint: "cc", name: "Tab", host: "h", port: 1, browse: false, push: false, pairedAt: 0)
        let targets = makeFlow().destinations([peer, offline, noPush], onlineFingerprints: [peer.fingerprint])
        XCTAssertEqual(targets[0].enabled, true)
        XCTAssertEqual(targets[1].reason, "Offline")
        XCTAssertEqual(targets[2].reason, "Has not allowed files from you")
    }

    // MARK: - Queue / refusal

    func testEnqueueCreatesAQueuedJobThatPersists() {
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin", "b.bin"]), label: "a.bin +1")
        let job = flow.job(id)
        XCTAssertEqual(job?.state, .queued)
        XCTAssertEqual(job?.total, 8)
        XCTAssertEqual(job?.done, 0)
        XCTAssertEqual(job?.label, "a.bin +1")
        XCTAssertEqual(store.saved.first?.id, id)
    }

    func testWifiOnlyRefusalRecordsAFailedRowAtOnce() {
        let flow = makeFlow(refusal: { "Waiting for Wi-Fi" })
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a.bin")
        XCTAssertEqual(flow.job(id)?.state, .failed)
        XCTAssertEqual(flow.job(id)?.message, "Waiting for Wi-Fi")
        XCTAssertTrue(client.offeredFiles.isEmpty, "a refused enqueue must not talk to the peer")
    }

    // MARK: - Run + status mapping

    func testSuccessfulRunCompletesTheJob() {
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin", "b.bin"]), label: "two")
        let result = flow.run(id: id, peer: peer)
        XCTAssertEqual(result, .sent(files: 2, bytes: 8))
        XCTAssertEqual(flow.job(id)?.state, .done)
        XCTAssertEqual(flow.job(id)?.message, "Sent 2 file(s)")
        XCTAssertEqual(flow.job(id)?.done, 8)
        XCTAssertEqual(client.completeAllCount, 1)
    }

    func testPerFileProgressIsAggregated() {
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin", "b.bin"], size: 10), label: "two")
        var samples: [Int64] = []
        flow.onChange = { if let job = flow.job(id) { samples.append(job.done) } }
        _ = flow.run(id: id, peer: peer)
        XCTAssertEqual(samples.max(), 20, "progress must reach the summed total")
    }

    func testRefusedOfferFailsTheRow() {
        client.offerError = PeerHttpException(403, "not allowed")
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a")
        XCTAssertEqual(flow.run(id: id, peer: peer), .refused)
        XCTAssertEqual(flow.job(id)?.state, .failed)
        XCTAssertEqual(flow.job(id)?.message, "The other device is not accepting files")
    }

    func testReceiverCancelledFailsTheRow() {
        client.streamError = PeerHttpException(410, "cancelled")
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a")
        XCTAssertEqual(flow.run(id: id, peer: peer), .cancelledByReceiver)
        XCTAssertEqual(flow.job(id)?.message, "The other device cancelled")
    }

    func testUnknownStatusFailsWithTheHttpCode() {
        client.streamError = PeerHttpException(500, "boom")
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a")
        XCTAssertEqual(flow.run(id: id, peer: peer), .failed("HTTP 500"))
        XCTAssertEqual(flow.job(id)?.state, .failed)
        XCTAssertEqual(flow.job(id)?.message, "HTTP 500")
    }

    func testMissingSourceFailsBeforeTalkingToThePeer() {
        let flow = SendFlow(
            store: store, clock: { self.now }, refusal: { nil },
            clientFactory: { _ in self.client }, sourceProvider: { _ in nil }
        )
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a")
        XCTAssertEqual(flow.run(id: id, peer: peer), .failed("Those files could not be opened."))
        XCTAssertEqual(flow.job(id)?.message, "Those files could not be opened.")
        XCTAssertTrue(client.offeredFiles.isEmpty)
    }

    // MARK: - Cancel

    func testCancelDuringRunMarksTheJobCancelled() {
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin", "b.bin"]), label: "two")
        // Cancel while the first file streams; PushSession observes it before the
        // second file and returns .cancelled (Kotlin's AtomicBoolean semantics).
        client.onStream = { flow.cancel(id: id) }
        XCTAssertEqual(flow.run(id: id, peer: peer), .cancelled)
        XCTAssertEqual(flow.job(id)?.state, .cancelled)
        XCTAssertEqual(flow.job(id)?.message, "Cancelled")
        XCTAssertEqual(client.streamCalls.count, 1, "the second file must not start")
    }

    func testCancelOfAQueuedJobMarksItCancelled() {
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a")
        flow.cancel(id: id)
        XCTAssertEqual(flow.job(id)?.state, .cancelled)
        XCTAssertEqual(flow.job(id)?.message, "Cancelled")
    }

    // MARK: - Resend

    func testResendARefusedSendCanSucceed() {
        client.offerError = PeerHttpException(403, "not allowed")
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a")
        _ = flow.run(id: id, peer: peer)
        XCTAssertEqual(flow.job(id)?.state, .failed)

        client.offerError = nil
        let result = flow.resend(id: id, peer: peer)
        XCTAssertEqual(result, .sent(files: 1, bytes: 4))
        XCTAssertEqual(flow.job(id)?.state, .done)
        XCTAssertEqual(flow.job(id)?.done, 4)
    }

    func testResendIsRejectedUnlessTheJobFailedOrWasCancelled() {
        let flow = makeFlow()
        let id = flow.enqueue(peer: peer, files: files(["a.bin"]), label: "a")
        XCTAssertNil(flow.resend(id: id, peer: peer), "a queued job is not resendable")
        XCTAssertNil(flow.resend(id: "nope", peer: peer))
    }

    // MARK: - Load

    func testLoadTurnsAnInterruptedRowIntoFailed() {
        let running = SendJob(
            id: "t_1", peerName: "Desk", peerFingerprint: peer.fingerprint, label: "a",
            files: files(["a.bin"]), total: 4, done: 1, state: .running, message: nil, startedAt: 0
        )
        store.loaded = [running]
        let flow = makeFlow()
        XCTAssertEqual(flow.job("t_1")?.state, .failed)
        XCTAssertEqual(flow.job("t_1")?.message, "Interrupted")
    }
}
