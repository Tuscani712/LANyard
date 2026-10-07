import Foundation

/// One file to send, as a persistable descriptor.
///
/// A `PushSource` cannot be persisted (it holds a `open: () -> ByteSource`
/// closure), so a queued send stores only the path/size/mtime and resolves the
/// bytes at run time through `SendFlow.sourceProvider`. Newly written for the
/// Swift port; the Kotlin app spools `PushSource`s and keeps them in memory.
public struct SendFile: Codable, Equatable, Identifiable {
    public var relPath: String
    public var size: Int64
    public var mtimeMillis: Int64

    public var id: String { relPath }

    public init(relPath: String, size: Int64, mtimeMillis: Int64) {
        self.relPath = relPath
        self.size = size
        self.mtimeMillis = mtimeMillis
    }
}

/// One row of the send queue, the persistable half of the Android
/// `TransferRecord` for a `"send"` push.
///
/// Reuses the public `TransferState` so the Transfers screen can render send and
/// receive rows with the same vocabulary. Newly written: Android keeps send
/// sources in memory in `TransferManager`, so only the descriptor is persisted
/// here and `SendFlow.run(id:)` re-resolves the bytes.
public struct SendJob: Codable, Equatable, Identifiable {
    public var id: String
    public var peerName: String
    public var peerFingerprint: String
    public var label: String
    public var files: [SendFile]
    public var total: Int64
    public var done: Int64
    public var state: TransferState
    public var message: String?
    public var startedAt: Int64

    public init(
        id: String,
        peerName: String,
        peerFingerprint: String,
        label: String,
        files: [SendFile],
        total: Int64,
        done: Int64,
        state: TransferState,
        message: String? = nil,
        startedAt: Int64
    ) {
        self.id = id
        self.peerName = peerName
        self.peerFingerprint = peerFingerprint
        self.label = label
        self.files = files
        self.total = total
        self.done = done
        self.state = state
        self.message = message
        self.startedAt = startedAt
    }
}

/// Where `SendFlow` persists its queue. Injectable so tests are deterministic
/// and never touch the real file system.
public protocol SendQueueStore {
    func load() -> [SendJob]
    func save(_ jobs: [SendJob])
}

/// A `SendQueueStore` backed by a single JSON file, written atomically (temp
/// file + rename) so a crash mid-write cannot corrupt the queue. A missing or
/// unreadable file reads as empty. Mirrors `JsonFileTransferStore`.
public final class JsonFileSendQueueStore: SendQueueStore {
    private let url: URL
    private let encoder: JSONEncoder
    private let decoder = JSONDecoder()

    public init(file: URL) {
        self.url = file
        self.encoder = JSONEncoder()
    }

    public func load() -> [SendJob] {
        guard let data = try? Data(contentsOf: url) else { return [] }
        return (try? decoder.decode([SendJob].self, from: data)) ?? []
    }

    public func save(_ jobs: [SendJob]) {
        guard let data = try? encoder.encode(jobs) else { return }
        try? atomicWrite(data, to: url)
    }
}

/// The send view model: pick a destination peer, enqueue a push, run it through
/// `PushSession`, show per-file progress, cancel, and resend a failed send.
///
/// Ported from the send half of the Android `TransferManager` (`enqueuePush`,
/// `runPush`, `finish`, `cancel`) with the coroutine/`StateFlow` plumbing
/// replaced by an injected `SendQueueStore` and `clock`, and the concrete
/// `PeerClient` replaced by a `clientFactory` seam so the model is pure on
/// Linux. `PushSession`/`PushClient`/`PushOffer`/`PushFileRequest` are reused
/// unchanged; the 403/410/`HTTP N` mapping is `PushSession`'s.
///
/// A `SendFlow` is not thread-safe; the app drives it from one queue.
public final class SendFlow {
    /// The last N jobs persist, matching `TransferManager.HISTORY_CAP`.
    public static let historyCap = 100

    /// The current jobs, newest first. Mirrors `TransferManager.state`.
    public private(set) var jobs: [SendJob] = []

    /// Fired after every mutation, in place of the Android `StateFlow`.
    public var onChange: (() -> Void)?

    private let store: SendQueueStore
    private let clock: () -> Int64
    private let refusal: () -> String?
    private let clientFactory: (PairedPeer) -> PushClient
    private let sourceProvider: (SendFile) -> PushSource?
    private let cap: Int
    private var cancels: [String: CancelFlag] = [:]

    /// A shared mutable cancel flag, the Swift stand-in for Android's
    /// `AtomicBoolean` handed to `PushSession.isCancelled`.
    private final class CancelFlag {
        var value = false
    }

    package init(
        store: SendQueueStore,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) },
        refusal: @escaping () -> String? = { nil },
        clientFactory: @escaping (PairedPeer) -> PushClient,
        sourceProvider: @escaping (SendFile) -> PushSource?,
        cap: Int = SendFlow.historyCap
    ) {
        self.store = store
        self.clock = clock
        self.refusal = refusal
        self.clientFactory = clientFactory
        self.sourceProvider = sourceProvider
        self.cap = cap
        loadQueue()
    }

    // MARK: - Reads

    public func job(_ id: String) -> SendJob? {
        jobs.first { $0.id == id }
    }

    /// The active jobs (`Running` or `Queued`), mirroring `TransferManager.running()`.
    public func running() -> [SendJob] {
        jobs.filter { $0.state.isActive }
    }

    /// The send destinations for the picker: online, push-permitted peers.
    /// Delegates to the shared `ShareValidation.shareTargets` rule so the send
    /// and share pickers cannot disagree.
    public func destinations(_ peers: [PairedPeer], onlineFingerprints: Set<String>) -> [ShareTarget] {
        ShareValidation.shareTargets(peers, onlineFingerprints: onlineFingerprints)
    }

    // MARK: - Queue

    /// Enqueues a push. If a Wi-Fi-only policy refuses it, the row is recorded
    /// as `Failed` at once, exactly as Android's `enqueuePush` does.
    @discardableResult
    public func enqueue(peer: PairedPeer, files: [SendFile], label: String) -> String {
        let id = Self.newId()
        let total = files.reduce(Int64(0)) { $0 + $1.size }
        if let blocked = refusal() {
            insert(SendJob(
                id: id, peerName: peer.name, peerFingerprint: peer.fingerprint,
                label: label, files: files, total: total, done: 0,
                state: .failed, message: blocked, startedAt: clock()
            ))
            return id
        }
        insert(SendJob(
            id: id, peerName: peer.name, peerFingerprint: peer.fingerprint,
            label: label, files: files, total: total, done: 0,
            state: .queued, message: nil, startedAt: clock()
        ))
        return id
    }

    /// Runs a queued job to completion (blocking), updating the persisted row.
    ///
    /// Package because it returns the package `PushResult`; the app dispatches
    /// this off the main thread. Returns the session's result.
    @discardableResult
    package func run(id: String, peer: PairedPeer) -> PushResult {
        guard let job = job(id) else { return .failed("no such send") }
        if !job.state.isActive {
            return .failed("this send is already \(job.state.rawValue.lowercased())")
        }
        if let blocked = refusal() {
            fail(id, blocked)
            return .failed(blocked)
        }

        let sources: [PushSource] = job.files.compactMap { sourceProvider($0) }
        guard sources.count == job.files.count else {
            let reason = "Those files could not be opened."
            fail(id, reason)
            return .failed(reason)
        }

        let flag = cancels[id] ?? {
            let f = CancelFlag()
            cancels[id] = f
            return f
        }()

        update(id) { $0.state = .running; $0.message = nil }

        var sent = [Int64](repeating: 0, count: sources.count)
        let result = PushSession(client: clientFactory(peer)).push(
            sources: sources,
            onProgress: { index, bytes, _ in
                sent[index] = bytes
                let done = sent.reduce(0, +)
                self.update(id) { row in
                    row.done = max(row.done, done)
                    row.total = max(row.total, done)
                }
            },
            isCancelled: { flag.value }
        )
        cancels.removeValue(forKey: id)
        finish(id, result)
        return result
    }

    /// Cancels a running send, or marks a still-queued one cancelled. Matches
    /// Android's `TransferManager.cancel(id)`: the running `PushSession` observes
    /// the flag and returns `.cancelled`, which `finish` records as `Cancelled`.
    public func cancel(id: String) {
        if let flag = cancels[id] {
            flag.value = true
        }
        if let job = job(id), job.state == .queued {
            update(id) { $0.state = .cancelled; $0.message = "Cancelled" }
            persist()
            onChange?()
        }
    }

    /// Resends a `Failed` (or `Cancelled`) send: resets its progress and runs it
    /// again with the same files and destination. Returns the new result, or nil
    /// when the job is missing or not in a resendable state.
    ///
    /// Newly written: Android has no explicit resend; the person re-shares.
    /// Resetting to `queued` and re-running is the documented-equivalent action.
    @discardableResult
    package func resend(id: String, peer: PairedPeer) -> PushResult? {
        guard let job = job(id), job.state == .failed || job.state == .cancelled else { return nil }
        update(id) { row in
            row.done = 0
            row.state = .queued
            row.message = nil
        }
        persist()
        onChange?()
        return run(id: id, peer: peer)
    }

    /// Removes a finished job from the list, mirroring `dismiss`.
    public func dismiss(id: String) {
        jobs.removeAll { $0.id == id }
        persist()
        onChange?()
    }

    /// Drops every finished row, keeping active ones, mirroring `clearFinished`.
    public func clearFinished() {
        jobs = jobs.filter { $0.state.isActive }
        persist()
        onChange?()
    }

    // MARK: - Internals

    /// The `PushResult` -> row mapping, ported from `TransferManager.finish`.
    /// The `403`/`410`/`HTTP N` classification itself lives in `PushSession`.
    private func finish(_ id: String, _ result: PushResult) {
        switch result {
        case let .sent(files, _):
            complete(id, "Sent \(files) file(s)")
        case .cancelled:
            complete(id, "Cancelled", state: .cancelled)
        case .cancelledByReceiver:
            fail(id, "The other device cancelled")
        case .refused:
            fail(id, "The other device is not accepting files")
        case let .failed(message):
            fail(id, message)
        }
    }

    private func complete(_ id: String, _ message: String, state: TransferState = .done) {
        update(id) { row in
            row.state = state
            row.message = message
            if state == .done { row.done = row.total }
        }
        persist()
        onChange?()
    }

    private func fail(_ id: String, _ message: String) {
        update(id) { row in
            row.state = .failed
            row.message = message
        }
        persist()
        onChange?()
    }

    /// Loads the persisted queue, turning a `Running`/`Queued` row left by a
    /// previous run into `Failed` with the message `"Interrupted"`.
    private func loadQueue() {
        jobs = store.load().map { row in
            guard row.state.isActive else { return row }
            var interrupted = row
            interrupted.state = .failed
            interrupted.message = "Interrupted"
            return interrupted
        }
        onChange?()
    }

    private func insert(_ job: SendJob) {
        jobs = Array(([job] + jobs).prefix(cap))
        persist()
        onChange?()
    }

    private func update(_ id: String, _ transform: (inout SendJob) -> Void) {
        guard let index = jobs.firstIndex(where: { $0.id == id }) else { return }
        transform(&jobs[index])
        onChange?()
    }

    private func persist() {
        store.save(Array(jobs.prefix(cap)))
    }

    /// `t_` plus 6 random bytes, matching Android's `newId`.
    private static func newId() -> String {
        var rng = SystemRandomNumberGenerator()
        var bytes = [UInt8]()
        bytes.reserveCapacity(6)
        for _ in 0..<6 { bytes.append(UInt8.random(in: 0...255, using: &rng)) }
        return "t_" + Hex.encode(bytes)
    }
}
