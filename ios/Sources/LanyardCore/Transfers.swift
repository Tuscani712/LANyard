import Foundation

/// The lifecycle of one transfer row.
///
/// Ported from the Kotlin `TransferState` in `Transfers.kt`. The Kotlin enum has
/// no `Interrupted` case: a receive left `Running` (or `Queued`) when the app was
/// killed is turned into `Failed` with the message `"Interrupted"` by
/// `TransferManager.loadHistory()`.
public enum TransferState: String, Codable, Equatable, CaseIterable {
    case queued = "Queued"
    case running = "Running"
    case done = "Done"
    case failed = "Failed"
    case cancelled = "Cancelled"

    /// Whether the row is still active (shown while it runs, kept by
    /// `clearHistory`). Mirrors `Running || Queued` in the Kotlin.
    public var isActive: Bool {
        self == .running || self == .queued
    }
}

/// One row on the Transfers screen.
///
/// Ported from the Kotlin `TransferRecord` in `Transfers.kt`. `direction` stays a
/// plain `String` (`"send"` / `"receive"`) to match the persisted shape.
public struct TransferRecord: Codable, Equatable, Identifiable {
    public var id: String
    public var direction: String
    public var peerName: String
    public var peerFingerprint: String
    public var label: String
    public var total: Int64
    public var done: Int64
    public var state: TransferState
    public var message: String?
    /// Bytes per second, smoothed (see `TransferManager.sampleSpeed`).
    public var speed: Double
    public var startedAt: Int64

    public init(
        id: String,
        direction: String,
        peerName: String,
        peerFingerprint: String = "",
        label: String,
        total: Int64,
        done: Int64,
        state: TransferState,
        message: String? = nil,
        speed: Double = 0.0,
        startedAt: Int64
    ) {
        self.id = id
        self.direction = direction
        self.peerName = peerName
        self.peerFingerprint = peerFingerprint
        self.label = label
        self.total = total
        self.done = done
        self.state = state
        self.message = message
        self.speed = speed
        self.startedAt = startedAt
    }
}

/// Where `TransferManager` persists its history. Injectable so tests are
/// deterministic and never touch the real file system.
public protocol TransferStore {
    func load() -> [TransferRecord]
    func save(_ records: [TransferRecord])
}

/// A `TransferStore` backed by a single JSON file, written atomically
/// (temp file + rename) so a crash mid-write can never corrupt the history.
/// A missing or unreadable file reads as empty.
public final class JsonFileTransferStore: TransferStore {
    private let url: URL
    private let encoder: JSONEncoder
    private let decoder = JSONDecoder()

    public init(file: URL) {
        self.url = file
        self.encoder = JSONEncoder()
    }

    public func load() -> [TransferRecord] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? decoder.decode([TransferRecord].self, from: data)) ?? []
    }

    public func save(_ records: [TransferRecord]) {
        guard let data = try? encoder.encode(records) else { return }
        try? atomicWrite(data, to: url)
    }
}

/// The single owner of transfers. Both the send and receive paths run through
/// it; the Transfers screen observes `records` and `onChange`.
///
/// Ported from the Kotlin `object TransferManager` in `Transfers.kt`. The
/// Android `Application`/`StateFlow`/coroutine plumbing is replaced by an
/// injected `TransferStore` and a `clock` so the state machine is pure and
/// testable on Linux. Only the push-receive hooks (this phone is the receiver)
/// and the shared row plumbing are in scope here.
public final class TransferManager {
    /// The last N records persist to the store.
    public static let historyCap = 100

    /// The current rows, newest first. Mirrors `TransferManager.state`.
    public private(set) var records: [TransferRecord] = []

    /// Fired after every mutation, in place of the Android `StateFlow`.
    public var onChange: (() -> Void)?

    private let store: TransferStore
    private let clock: () -> Int64
    private let cap: Int
    private let lock = NSRecursiveLock()
    private var pushReceives = Set<String>()
    private var speedSamples: [String: SpeedSample] = [:]

    public init(
        store: TransferStore,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        cap: Int = TransferManager.historyCap
    ) {
        self.store = store
        self.clock = clock
        self.cap = cap
        loadHistory()
    }

    // MARK: - Reads

    /// The active rows (`Running` or `Queued`).
    public func running() -> [TransferRecord] {
        lock.lock(); defer { lock.unlock() }
        return records.filter { $0.state.isActive }
    }

    public func record(_ id: String) -> TransferRecord? {
        lock.lock(); defer { lock.unlock() }
        return records.first { $0.id == id }
    }

    // MARK: - Row plumbing

    /// Loads persisted history, turning a receive left `Running`/`Queued` by a
    /// previous run into `Failed` with the message `"Interrupted"`.
    public func loadHistory() {
        lock.lock(); defer { lock.unlock() }
        records = store.load().map { record in
            guard record.state.isActive else { return record }
            var interrupted = record
            interrupted.state = .failed
            interrupted.message = "Interrupted"
            return interrupted
        }
        onChange?()
    }

    /// Prepends a row, capping the history, and persists.
    public func add(_ record: TransferRecord) {
        lock.lock(); defer { lock.unlock() }
        records = Array(([record] + records).prefix(cap))
        persist()
        onChange?()
    }

    /// Applies `transform` to the row with `id` (no-op if absent). Does not
    /// persist, matching the Kotlin `update`.
    public func update(_ id: String, _ transform: (TransferRecord) -> TransferRecord) {
        lock.lock(); defer { lock.unlock() }
        var changed = false
        records = records.map { record in
            guard record.id == id else { return record }
            changed = true
            return transform(record)
        }
        if changed { onChange?() }
    }

    /// Marks a row done (or `Cancelled`), zeroing speed and, for a real `Done`,
    /// setting `done` to `total` and persisting.
    public func complete(_ id: String, message: String, state: TransferState = .done) {
        lock.lock(); defer { lock.unlock() }
        update(id) { record in
            var next = record
            next.state = state
            next.message = message
            next.speed = 0.0
            if state == .done { next.done = next.total }
            return next
        }
        persist()
        speedSamples.removeValue(forKey: id)
        onChange?()
    }

    /// Marks a row failed, zeroing speed and persisting.
    public func fail(_ id: String, message: String) {
        lock.lock(); defer { lock.unlock() }
        update(id) { record in
            var next = record
            next.state = .failed
            next.message = message
            next.speed = 0.0
            return next
        }
        persist()
        speedSamples.removeValue(forKey: id)
        onChange?()
    }

    // MARK: - Received pushes (this phone is the receiver)
    // A push from a peer is recorded here so it shows on the Transfers screen
    // like any other transfer. A receive left Running when the app restarts is
    // turned into a Failed "Interrupted" row by loadHistory().

    public func noteReceiveStarted(id: String, peerName: String, peerFp: String, label: String, total: Int64) {
        lock.lock(); defer { lock.unlock() }
        if records.contains(where: { $0.id == id }) { return }
        pushReceives.insert(id)
        add(
            TransferRecord(
                id: id,
                direction: "receive",
                peerName: peerName,
                peerFingerprint: peerFp,
                label: label,
                total: total,
                done: 0,
                state: .running,
                message: nil,
                speed: 0.0,
                startedAt: clock()
            )
        )
    }

    public func noteReceiveProgress(id: String, done: Int64, total: Int64) {
        lock.lock(); defer { lock.unlock() }
        if !records.contains(where: { $0.id == id }) { return }
        update(id) { record in
            var next = record
            next.done = max(done, next.done)
            next.total = max(total, next.total)
            next.speed = sampleSpeed(id, done)
            return next
        }
    }

    public func noteReceiveDone(id: String, message: String) {
        lock.lock(); defer { lock.unlock() }
        pushReceives.remove(id)
        if !records.contains(where: { $0.id == id }) { return }
        complete(id, message: message)
    }

    /// Marks a push this phone abandoned (declined or cut off) as failed.
    public func noteReceiveFailed(id: String, reason: String) {
        lock.lock(); defer { lock.unlock() }
        pushReceives.remove(id)
        if !records.contains(where: { $0.id == id }) { return }
        fail(id, message: reason)
    }

    /// Fails every push still being received. Called when the peer server stops
    /// (the app was backgrounded): a push cannot continue without it, so the row
    /// should not sit at Running until the next restart. Pull downloads are not
    /// touched, since those keep running in the background.
    public func failPushReceives(reason: String) {
        lock.lock(); defer { lock.unlock() }
        for id in pushReceives.sorted() {
            if records.contains(where: { $0.id == id }) { fail(id, message: reason) }
        }
        pushReceives.removeAll()
    }

    /// Removes a finished (`Done`/`Failed`/`Cancelled`) row from the list.
    public func dismiss(id: String) {
        lock.lock(); defer { lock.unlock() }
        pushReceives.remove(id)
        records.removeAll { $0.id == id }
        persist()
        onChange?()
    }

    /// Drops every finished row, keeping active ones. Mirrors the Kotlin
    /// `clearFinished` (the task names it `clearHistory`).
    public func clearHistory() {
        lock.lock(); defer { lock.unlock() }
        records = records.filter { $0.state.isActive }
        persist()
        onChange?()
    }

    // MARK: - Internals

    private struct SpeedSample {
        var bytes: Int64
        var at: Int64
        var ema: Double
    }

    /// The smoothed bytes-per-second rate. Ported exactly from the Kotlin
    /// `sampleSpeed`: a sample is only folded in once 0.4 s has elapsed, and the
    /// EMA is 60% old / 40% instantaneous.
    private func sampleSpeed(_ id: String, _ done: Int64) -> Double {
        let at = clock()
        var sample = speedSamples[id] ?? SpeedSample(bytes: done, at: at, ema: 0.0)
        let dt = Double(at - sample.at) / 1000.0
        if dt >= 0.4 {
            let instant = Double(max(0, done - sample.bytes)) / dt
            sample.ema = sample.ema <= 0.0 ? instant : sample.ema * 0.6 + instant * 0.4
            sample.bytes = done
            sample.at = at
        }
        speedSamples[id] = sample
        return sample.ema
    }

    private func persist() {
        store.save(Array(records.prefix(cap)))
    }
}
