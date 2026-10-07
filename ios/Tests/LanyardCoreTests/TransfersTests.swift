import XCTest
@testable import LanyardCore

// Ports the push-receive half of `Transfers.kt` (TransferManager state, speeds,
// "Interrupted" mapping). No Kotlin unit test exists for this file, so these
// cases are newly written from the documented behaviour and the Kotlin source.

private final class MemoryTransferStore: TransferStore {
    var saved: [TransferRecord]
    private(set) var saveCount = 0

    init(seed: [TransferRecord] = []) {
        self.saved = seed
    }

    func load() -> [TransferRecord] { saved }

    func save(_ records: [TransferRecord]) {
        saved = records
        saveCount += 1
    }
}

private final class TestClock {
    var now: Int64
    init(_ now: Int64) { self.now = now }
}

final class TransfersTests: XCTestCase {
    private func record(
        _ id: String,
        direction: String = "receive",
        state: TransferState = .running,
        total: Int64 = 0,
        done: Int64 = 0,
        message: String? = nil
    ) -> TransferRecord {
        TransferRecord(
            id: id,
            direction: direction,
            peerName: "Desk",
            peerFingerprint: "fp",
            label: "Inbox",
            total: total,
            done: done,
            state: state,
            message: message,
            speed: 0,
            startedAt: 100
        )
    }

    func testAddPrependsNewestFirstAndPersists() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 1000 })
        manager.add(record("a"))
        manager.add(record("b"))
        XCTAssertEqual(["b", "a"], manager.records.map(\.id))
        XCTAssertEqual(["b", "a"], store.saved.map(\.id))
    }

    func testHistoryCapIsOneHundredAndAddTrims() {
        XCTAssertEqual(100, TransferManager.historyCap)
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 1000 }, cap: 3)
        for id in ["a", "b", "c", "d", "e"] { manager.add(record(id)) }
        XCTAssertEqual(["e", "d", "c"], manager.records.map(\.id))
    }

    func testNoteReceiveStartedRecordsRunningReceive() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 500 })
        manager.noteReceiveStarted(id: "p_1", peerName: "Pixel", peerFp: "AB", label: "Inbox", total: 10)
        let row = manager.record("p_1")
        XCTAssertEqual("p_1", row?.id)
        XCTAssertEqual("receive", row?.direction)
        XCTAssertEqual("Pixel", row?.peerName)
        XCTAssertEqual("AB", row?.peerFingerprint)
        XCTAssertEqual("Inbox", row?.label)
        XCTAssertEqual(10, row?.total)
        XCTAssertEqual(0, row?.done)
        XCTAssertEqual(.running, row?.state)
        XCTAssertEqual(500, row?.startedAt)
    }

    func testNoteReceiveStartedIsIdempotentForSameId() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 1)
        manager.noteReceiveStarted(id: "p_1", peerName: "B", peerFp: "CD", label: "Inbox", total: 99)
        XCTAssertEqual(1, manager.records.count)
        XCTAssertEqual("A", manager.record("p_1")?.peerName)
        XCTAssertEqual(1, manager.record("p_1")?.total)
    }

    func testNoteReceiveProgressIsMonotonic() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 100)
        manager.noteReceiveProgress(id: "p_1", done: 40, total: 90)
        manager.noteReceiveProgress(id: "p_1", done: 10, total: 150)
        XCTAssertEqual(40, manager.record("p_1")?.done)
        XCTAssertEqual(150, manager.record("p_1")?.total)
    }

    func testNoteReceiveProgressIgnoresUnknownId() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveProgress(id: "nope", done: 5, total: 5)
        XCTAssertTrue(manager.records.isEmpty)
    }

    func testNoteReceiveProgressComputesSmoothedSpeed() {
        let store = MemoryTransferStore()
        let clock = TestClock(0)
        let manager = TransferManager(store: store, clock: { clock.now })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 10_000)

        // The first sample anchors the rate; dt is 0 so the speed stays 0.
        manager.noteReceiveProgress(id: "p_1", done: 1_000, total: 10_000)
        XCTAssertEqual(0.0, manager.record("p_1")?.speed)

        // 500 bytes over 1.0 s -> 500 B/s (first non-zero sample seeds the EMA).
        clock.now = 1_000
        manager.noteReceiveProgress(id: "p_1", done: 1_500, total: 10_000)
        XCTAssertEqual(500.0, manager.record("p_1")!.speed, accuracy: 0.0001)

        // 1000 B over the next 1.0 s -> instant 1000; EMA = 0.6*500 + 0.4*1000.
        clock.now = 2_000
        manager.noteReceiveProgress(id: "p_1", done: 2_500, total: 10_000)
        XCTAssertEqual(700.0, manager.record("p_1")!.speed, accuracy: 0.0001)
    }

    func testNoteReceiveDoneCompletesAtTotalAndClearsSpeed() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 10)
        manager.noteReceiveProgress(id: "p_1", done: 4, total: 10)
        manager.noteReceiveDone(id: "p_1", message: "Received 1 file(s)")
        let row = manager.record("p_1")
        XCTAssertEqual(.done, row?.state)
        XCTAssertEqual("Received 1 file(s)", row?.message)
        XCTAssertEqual(10, row?.done)
        XCTAssertEqual(0.0, row?.speed)
    }

    func testNoteReceiveFailedMarksFailed() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 10)
        manager.noteReceiveFailed(id: "p_1", reason: "The transfer was cancelled")
        XCTAssertEqual(.failed, manager.record("p_1")?.state)
        XCTAssertEqual("The transfer was cancelled", manager.record("p_1")?.message)
    }

    func testFailPushReceivesOnlyTouchesPushReceives() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        // A pull download (not a push receive) is added directly and must survive.
        manager.add(record("pull_1", state: .running))
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 5)

        manager.failPushReceives(reason: "Interrupted")

        XCTAssertEqual(.running, manager.record("pull_1")?.state)
        XCTAssertNil(manager.record("pull_1")?.message)
        XCTAssertEqual(.failed, manager.record("p_1")?.state)
        XCTAssertEqual("Interrupted", manager.record("p_1")?.message)
    }

    func testFailPushReceivesClearsTheTrackedSet() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 5)
        manager.failPushReceives(reason: "First")
        manager.failPushReceives(reason: "Second")
        XCTAssertEqual("First", manager.record("p_1")?.message, "a cleared push must not be failed again")
    }

    func testDismissRemovesRowAndPersists() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 5)
        manager.dismiss(id: "p_1")
        XCTAssertNil(manager.record("p_1"))
        XCTAssertTrue(store.saved.isEmpty)
    }

    func testClearHistoryKeepsActiveRows() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.add(record("active", state: .running))
        manager.add(record("queued", state: .queued))
        manager.add(record("done", state: .done))
        manager.add(record("failed", state: .failed))
        manager.add(record("cancelled", state: .cancelled))
        manager.clearHistory()
        XCTAssertEqual(2, manager.records.count)
        XCTAssertEqual(Set(["active", "queued"]), Set(manager.records.map(\.id)))
    }

    func testLoadHistoryTurnsRunningAndQueuedIntoInterrupted() {
        let store = MemoryTransferStore(seed: [
            record("r", direction: "receive", state: .running),
            record("q", direction: "send", state: .queued),
            record("d", state: .done, message: "Received 1 file(s)"),
        ])
        let manager = TransferManager(store: store, clock: { 0 })
        let byId = Dictionary(uniqueKeysWithValues: manager.records.map { ($0.id, $0) })
        XCTAssertEqual(.failed, byId["r"]?.state)
        XCTAssertEqual("Interrupted", byId["r"]?.message)
        XCTAssertEqual(.failed, byId["q"]?.state)
        XCTAssertEqual("Interrupted", byId["q"]?.message)
        XCTAssertEqual(.done, byId["d"]?.state)
        XCTAssertEqual("Received 1 file(s)", byId["d"]?.message)
    }

    func testLoadHistoryIsReRunnable() {
        let store = MemoryTransferStore(seed: [record("r", state: .running)])
        let manager = TransferManager(store: store, clock: { 0 })
        manager.loadHistory()
        XCTAssertEqual(.failed, manager.record("r")?.state)
        XCTAssertEqual("Interrupted", manager.record("r")?.message)
    }

    func testHistoryRoundTripsAcrossInstances() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.add(record("done", direction: "send", state: .done, total: 7, done: 7, message: "Sent 1 file(s)"))
        manager.add(record("cancelled", state: .cancelled, message: "Cancelled"))

        let reopened = TransferManager(store: store, clock: { 0 })
        XCTAssertEqual(manager.records, reopened.records)
        XCTAssertEqual(.done, reopened.record("done")?.state)
        XCTAssertEqual("Sent 1 file(s)", reopened.record("done")?.message)
    }

    func testCompleteCancelledKeepsDone() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.noteReceiveStarted(id: "p_1", peerName: "A", peerFp: "AB", label: "Inbox", total: 10)
        manager.noteReceiveProgress(id: "p_1", done: 4, total: 10)
        manager.complete("p_1", message: "Cancelled", state: .cancelled)
        let row = manager.record("p_1")
        XCTAssertEqual(.cancelled, row?.state)
        XCTAssertEqual(4, row?.done)
        XCTAssertEqual("Cancelled", row?.message)
        XCTAssertEqual(0.0, row?.speed)
    }

    func testRunningReturnsActiveRows() {
        let store = MemoryTransferStore()
        let manager = TransferManager(store: store, clock: { 0 })
        manager.add(record("r", state: .running))
        manager.add(record("q", state: .queued))
        manager.add(record("d", state: .done))
        XCTAssertEqual(Set(["r", "q"]), Set(manager.running().map(\.id)))
    }

    func testJsonFileStoreRoundTripsWithoutHandTypedJSON() {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("transfers-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: dir) }
        let file = dir.appendingPathComponent("transfers.json")

        let rows = [
            record("a", state: .done, total: 3, done: 3, message: "Sent 1 file(s)"),
            record("b", state: .failed, message: "Interrupted"),
        ]
        JsonFileTransferStore(file: file).save(rows)

        let loaded = JsonFileTransferStore(file: file).load()
        XCTAssertEqual(rows, loaded)
    }

    func testJsonFileStoreMissingFileReadsEmpty() {
        let file = FileManager.default.temporaryDirectory.appendingPathComponent("missing-\(UUID().uuidString).json")
        XCTAssertTrue(JsonFileTransferStore(file: file).load().isEmpty)
    }
}
