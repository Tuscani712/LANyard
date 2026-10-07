// SendShareModels.swift — the app-facing, SwiftUI-observable view models for
// Phase 5: push send, share/serve, and pull/browse.
//
// WRITTEN, NOT COMPILED. Guarded with `#if canImport(Combine)` so on Linux the
// file compiles to nothing and `swift build`/`swift test` stay green. The
// concrete transport factories at the bottom are additionally guarded with
// `#if canImport(Network) && canImport(Security)`, matching the LanyardNet
// adapters they build (`LanyardPushClient`, `LanyardShareReader`,
// `LanyardBrowseClient`, `AppShareSource`).
//
// These are *thin* wrappers over the REAL core types. The state machines
// (`SendFlow`, `ShareList`, `PullBrowse`) stay plain, testable, platform-free
// types in LanyardCore; each model subscribes to the core's `onChange` (or
// republishes after a call) on the main actor for SwiftUI.
//
// Bound exactly (no assumed surface):
//   SendFlow  { jobs, onChange, destinations(_:onlineFingerprints:),
//               enqueue/cancel/dismiss/clearFinished, package run/resend }
//   ShareList { concurrency, digests, list, cancel, stopAll, isCancelled,
//               gate, consume, manifest, tree, hash, statics }
//   PullBrowse{ shares, tree, package manifest/pick/download }
//   ShareTarget, ShareInfo, ShareLifetimeType, BrowseShare, BrowseEntry,
//   DownloadResult, ManifestFile, DownloadTarget, ByteSink, ByteSource.

#if canImport(Combine)
import Foundation
import Combine
import LanyardCore

// MARK: - Send

/// Runs the blocking `SendFlow.run`/`resend` off the main actor.
///
/// `SendFlow` is not `Sendable` (it is documented as single-queue), so the
/// runner is an explicit `@unchecked Sendable` box that owns it and only ever
/// touches it on one serial queue. The model's `onChange` callback marshals the
/// resulting mutations back to the main actor.
final class SendRunner: @unchecked Sendable {
    private let flow: SendFlow
    private let queue: DispatchQueue
    var didChange: (@MainActor () -> Void)?

    init(flow: SendFlow, queue: DispatchQueue) {
        self.flow = flow
        self.queue = queue
    }

    func run(id: String, peer: PairedPeer) {
        queue.async {
            _ = self.flow.run(id: id, peer: peer)
            Task { @MainActor in self.didChange?() }
        }
    }

    func resend(id: String, peer: PairedPeer) {
        queue.async {
            _ = self.flow.resend(id: id, peer: peer)
            Task { @MainActor in self.didChange?() }
        }
    }
}

/// Maps a queued `SendFile.relPath` back to the security-scoped URL the document
/// picker returned. `SendFlow` only persists descriptors, so the app must own
/// this map; the send runner reads it from a background queue, hence the lock.
public final class SendSourceRegistry: @unchecked Sendable {
    private let lock = NSLock()
    private var urls: [String: URL] = [:]

    public init() {}

    public func register(_ url: URL, for relPath: String) {
        lock.lock(); urls[relPath] = url; lock.unlock()
    }

    public func resolve(_ relPath: String) -> URL? {
        lock.lock(); defer { lock.unlock() }
        return urls[relPath]
    }
}

/// Wraps `SendFlow` for SwiftUI: publishes the queue, the picker targets, and
/// forwards enqueue/cancel/resend/dismiss/clearFinished.
@MainActor
public final class SendModel: ObservableObject {
    @Published public private(set) var jobs: [SendJob] = []
    @Published public private(set) var targets: [ShareTarget] = []

    private let flow: SendFlow
    private let runner: SendRunner
    private let peers: () -> [PairedPeer]
    private let onlineFingerprints: () -> Set<String>
    private var registry: SendSourceRegistry
    /// The paired peer for each queued job, so a Resend can reuse the same
    /// destination after a restart or a peer-list change.
    private var peerByFingerprint: [String: PairedPeer] = [:]

    public init(
        flow: SendFlow,
        peers: @escaping () -> [PairedPeer],
        onlineFingerprints: @escaping () -> Set<String>,
        workQueue: DispatchQueue = DispatchQueue(label: "io.github.tuscani712.lanyard.send")
    ) {
        self.flow = flow
        self.registry = SendSourceRegistry()
        self.runner = SendRunner(flow: flow, queue: workQueue)
        self.peers = peers
        self.onlineFingerprints = onlineFingerprints
        self.jobs = flow.jobs
        flow.onChange = { [weak self] in
            Task { @MainActor in self?.refresh() }
        }
        self.runner.didChange = { [weak self] in self?.refresh() }
    }

    /// Registers the security-scoped URL for a picked file before enqueueing it.
    public func registerSource(_ url: URL, for relPath: String) {
        registry.register(url, for: relPath)
    }

    /// True when at least one finished (`Done`/`Failed`/`Cancelled`) row exists,
    /// i.e. "Clear history" would remove something.
    public var hasFinished: Bool {
        jobs.contains { !$0.state.isActive }
    }

    /// Recomputes the destination picker rows (paired peers, online + permitted).
    public func refreshTargets() {
        targets = flow.destinations(peers(), onlineFingerprints: onlineFingerprints())
    }

    /// Enqueues a push and starts it. Returns the new job id.
    @discardableResult
    public func enqueue(peer: PairedPeer, files: [SendFile], label: String) -> String {
        peerByFingerprint[peer.fingerprint.lowercased()] = peer
        let id = flow.enqueue(peer: peer, files: files, label: label)
        refresh()
        runner.run(id: id, peer: peer)
        return id
    }

    /// Cancels a running (or still-queued) send.
    public func cancel(_ id: String) {
        flow.cancel(id: id)
        refresh()
    }

    /// Resends a `Failed`/`Cancelled` send to the same destination.
    public func resend(_ id: String) {
        guard let job = flow.job(id),
              let peer = peerByFingerprint[job.peerFingerprint.lowercased()] else { return }
        runner.resend(id: id, peer: peer)
    }

    public func dismiss(_ id: String) {
        flow.dismiss(id: id)
        refresh()
    }

    public func clearFinished() {
        flow.clearFinished()
        refresh()
    }

    public func job(_ id: String) -> SendJob? { flow.job(id) }

    private func refresh() {
        jobs = flow.jobs
        targets = flow.destinations(peers(), onlineFingerprints: onlineFingerprints())
    }
}

// MARK: - Send file helpers (UIDocumentPicker output -> SendFile)

/// Turns picked `URL`s into the persistable `SendFile` descriptors the core
/// stores. Existence/size/mtime only: the bytes are resolved lazily by the
/// factory's `sourceProvider`, so nothing is buffered at pick time.
public enum SendFileScanner {
    /// One file descriptor plus the URL it came from (to register as a source).
    public typealias Picked = (file: SendFile, url: URL)

    /// One descriptor for a picked file URL.
    public static func pickedFile(at url: URL, relativeTo base: URL? = nil) -> Picked? {
        let values = try? url.resourceValues(forKeys: [.fileSizeKey, .contentModificationDateKey, .isDirectoryKey])
        if values?.isDirectory == true { return nil }
        let size = Int64(values?.fileSize ?? 0)
        let mtime = Int64((values?.contentModificationDate ?? Date()).timeIntervalSince1970 * 1000)
        let file = SendFile(relPath: relativePath(url, base: base), size: size, mtimeMillis: mtime)
        return (file, url)
    }

    /// Every file under a picked folder, recursively, with the URL to source it.
    ///
    /// The folder must already be security-scoped by the picker; this only
    /// enumerates metadata.
    public static func pickedFolder(at url: URL, label: String) -> [Picked] {
        let keys: [URLResourceKey] = [.fileSizeKey, .contentModificationDateKey, .isDirectoryKey]
        guard let enumerator = FileManager.default.enumerator(
            at: url,
            includingPropertiesForKeys: keys,
            options: [.skipsHiddenFiles]
        ) else { return [] }

        var picks: [Picked] = []
        for case let child as URL in enumerator {
            let values = try? child.resourceValues(forKeys: Set(keys))
            if values?.isDirectory == true { continue }
            let size = Int64(values?.fileSize ?? 0)
            let mtime = Int64((values?.contentModificationDate ?? Date()).timeIntervalSince1970 * 1000)
            let rel = "\(label)/\(relativePath(child, base: url))"
            picks.append((SendFile(relPath: rel, size: size, mtimeMillis: mtime), child))
        }
        return picks
    }

    /// The label shown for a picked folder, from its last path component.
    public static func folderLabel(_ url: URL) -> String {
        let name = url.lastPathComponent
        return name.isEmpty ? "folder" : name
    }

    private static func relativePath(_ url: URL, base: URL?) -> String {
        guard let base else { return url.lastPathComponent }
        let basePath = base.standardizedFileURL.path
        let full = url.standardizedFileURL.path
        if full.hasPrefix(basePath + "/") {
            return String(full.dropFirst(basePath.count + 1))
        }
        return url.lastPathComponent
    }
}

// MARK: - Share list (serve side)

/// The app-owned seam for adding/removing a shared folder.
///
/// The core `ShareList`/`ShareSource` are read/serve only: they expose `list`,
/// `children`, `resolve` and `ended`, but no way to add a share. On iOS the
/// share catalog is the app's (a security-scoped bookmark per share), so the
/// mutation lives in the concrete `AppShareSource`; this protocol lets
/// `SharesModel` drive it without assuming a core API that does not exist.
///
/// MAC-SPIKE: if a future core revision grows `ShareSource.add`, replace this
/// seam with it. Until then this is the honest boundary.
public protocol ShareRegistrar: AnyObject {
    @discardableResult
    func addShare(url: URL, label: String, lifetime: ShareLifetimeType) throws -> ShareInfo
    func removeShare(id: String)
}

/// Wraps `ShareList` for SwiftUI: publishes what is shared, adds a picked
/// file/folder, stops one share or all of them, and surfaces the server-side
/// `503` / `Retry-After` state from the concurrency gate.
@MainActor
public final class SharesModel: ObservableObject {
    @Published public private(set) var shares: [ShareInfo] = []
    /// Non-nil presentation of the gate's `Retry-After` once the server is at
    /// its concurrency cap. The share routes answer `503` with this many
    /// seconds; the view shows it in the "peers are being asked to wait" banner.
    @Published public private(set) var retryAfterVisible = false

    private let list: ShareList
    private let registrar: ShareRegistrar

    public init(list: ShareList, registrar: ShareRegistrar) {
        self.list = list
        self.registrar = registrar
        refresh()
    }

    /// True when the serve side is at the per-peer/global cap and is answering
    /// `503` with `Retry-After`.
    public var busy: Bool {
        list.concurrency.totalInFlight >= list.concurrency.maxConcurrent
    }

    /// The `Retry-After` value the adapter writes on a `503`.
    public var retryAfterSeconds: Int { list.concurrency.retryAfterSeconds }

    public var hasShares: Bool { !shares.isEmpty }

    /// Re-reads the live share list from the core (expired/stopped shares drop).
    public func refresh() {
        shares = list.list()
        retryAfterVisible = busy
    }

    /// Adds a picked file/folder with a lifetime choice.
    @discardableResult
    public func add(url: URL, label: String, lifetime: ShareLifetimeType) -> Bool {
        do {
            _ = try registrar.addShare(url: url, label: label, lifetime: lifetime)
            refresh()
            return true
        } catch {
            return false
        }
    }

    /// Stops one share; later requests get `410`.
    public func stop(_ id: String) {
        list.cancel(id)
        refresh()
    }

    /// Stops every known share.
    public func stopAll() {
        list.stopAll()
        refresh()
    }
}

// MARK: - Browse / pull

/// A `@unchecked Sendable` lease over the blocking `PullBrowse`, so its
/// synchronous transport calls never run on the main actor.
final class BrowseRunner: @unchecked Sendable {
    private let queue: DispatchQueue
    private var browse: PullBrowse?

    init(queue: DispatchQueue = DispatchQueue(label: "io.github.tuscani712.lanyard.browse")) {
        self.queue = queue
    }

    func attach(_ browse: PullBrowse?) {
        self.browse = browse
    }

    func loadShares(_ done: @escaping @MainActor ([BrowseShare], String?) -> Void) {
        queue.async {
            guard let browse = self.browse else {
                Task { @MainActor in done([], "No device selected.") }
                return
            }
            do {
                let shares = try browse.shares()
                Task { @MainActor in done(shares, nil) }
            } catch {
                Task { @MainActor in done([], "\(error)") }
            }
        }
    }

    func loadTree(shareId: String, path: String, _ done: @escaping @MainActor ([BrowseEntry], String?) -> Void) {
        queue.async {
            guard let browse = self.browse else {
                Task { @MainActor in done([], "No device selected.") }
                return
            }
            do {
                let entries = try browse.tree(shareId: shareId, path: path)
                Task { @MainActor in done(entries, nil) }
            } catch {
                Task { @MainActor in done([], "\(error)") }
            }
        }
    }

    func download(
        shareId: String,
        path: String,
        include: Set<String>?,
        directory: URL,
        cancel: CancelFlag,
        onProgress: @escaping @MainActor (Double) -> Void,
        done: @escaping @MainActor (DownloadResult) -> Void
    ) {
        queue.async {
            guard let browse = self.browse else {
                Task { @MainActor in done(.peerUnreachable) }
                return
            }
            var lastPercent = -1
            let result = browse.download(
                shareId: shareId,
                path: path,
                include: include,
                targetFor: { file in
                    DownloadTarget(
                        existingSize: DownloadFileSupport.existingSize(directory: directory, path: file.path),
                        openAt: { offset in
                            (try? DownloadFileSupport.sink(directory: directory, path: file.path, offset: offset))
                                ?? NullByteSink()
                        },
                        openExisting: {
                            DownloadFileSupport.existingSource(directory: directory, path: file.path)
                        }
                    )
                },
                onProgress: { _, _, received, total in
                    guard total > 0 else { return }
                    let percent = Int(Double(received) / Double(total) * 100)
                    if percent != lastPercent {
                        lastPercent = percent
                        let value = Double(received) / Double(total)
                        Task { @MainActor in onProgress(value) }
                    }
                },
                isCancelled: { cancel.value }
            )
            Task { @MainActor in done(result) }
        }
    }
}

/// A `@unchecked Sendable` mutable cancel flag, the Swift stand-in for the
/// Kotlin `AtomicBoolean` a download polls between buffers.
final class CancelFlag: @unchecked Sendable {
    private let lock = NSLock()
    private var flag = false
    var value: Bool {
        get { lock.lock(); defer { lock.unlock() }; return flag }
        set { lock.lock(); flag = newValue; lock.unlock() }
    }
}

/// Wraps `PullBrowse` for SwiftUI: pick a paired peer, list its shares, walk a
/// share's tree, and pull files with progress and cancel.
@MainActor
public final class BrowseModel: ObservableObject {
    @Published public private(set) var peers: [PairedPeer] = []
    @Published public private(set) var shares: [BrowseShare] = []
    @Published public private(set) var entries: [BrowseEntry] = []
    @Published public private(set) var currentShareId: String?
    @Published public private(set) var currentPath: String = ""
    @Published public private(set) var currentShareLabel: String = ""
    @Published public var error: String?

    @Published public private(set) var isDownloading = false
    @Published public private(set) var downloadProgress: Double = 0
    @Published public private(set) var lastResult: String?

    private let runner: BrowseRunner
    private let peersProvider: () -> [PairedPeer]
    private let browserFactory: (PairedPeer) -> PullBrowse
    private let cancel = CancelFlag()

    public init(
        peers: @escaping () -> [PairedPeer],
        browserFactory: @escaping (PairedPeer) -> PullBrowse,
        workQueue: DispatchQueue = DispatchQueue(label: "io.github.tuscani712.lanyard.browse")
    ) {
        self.peersProvider = peers
        self.browserFactory = browserFactory
        self.runner = BrowseRunner(queue: workQueue)
    }

    public func refreshPeers() {
        peers = peersProvider()
    }

    /// Selects a peer and loads its visible shares.
    public func select(_ peer: PairedPeer) {
        runner.attach(browserFactory(peer))
        currentShareId = nil
        currentPath = ""
        currentShareLabel = ""
        shares = []
        entries = []
        loadShares()
    }

    public func loadShares() {
        runner.loadShares { [weak self] shares, error in
            self?.shares = shares
            self?.error = error
        }
    }

    /// Opens a share (or a folder inside one), replacing the tree view.
    public func open(share: BrowseShare, path: String = "") {
        currentShareId = share.id
        currentShareLabel = share.label.isEmpty ? share.name : share.label
        loadTree(shareId: share.id, path: path)
    }

    public func openFolder(_ entry: BrowseEntry) {
        guard entry.isDir, let shareId = currentShareId else { return }
        loadTree(shareId: shareId, path: entry.path)
    }

    public func loadTree(shareId: String, path: String) {
        runner.loadTree(shareId: shareId, path: path) { [weak self] entries, error in
            self?.entries = entries
            self?.currentPath = path
            self?.error = error
        }
    }

    /// Pulls `path` (or the chosen subset) into `directory`, resuming partial
    /// files already present (`DownloadTarget.existingSize`) and verifying each
    /// SHA-256 via `DownloadSession`.
    public func download(path: String, include: Set<String>? = nil, to directory: URL) {
        guard let shareId = currentShareId else { return }
        isDownloading = true
        downloadProgress = 0
        lastResult = nil
        cancel.value = false
        runner.download(
            shareId: shareId,
            path: path,
            include: include,
            directory: directory,
            cancel: cancel
        ) { [weak self] progress in
            self?.downloadProgress = progress
        } done: { [weak self] result in
            guard let self else { return }
            self.isDownloading = false
            self.downloadProgress = 1
            self.lastResult = Self.describe(result)
        }
    }

    /// Cancels the in-flight pull; `DownloadSession` returns `.cancelled`.
    public func cancelDownload() {
        cancel.value = true
    }

    static func describe(_ result: DownloadResult) -> String {
        switch result {
        case let .done(files, bytes):
            return "Downloaded \(files) file(s), \(bytes) bytes"
        case .cancelled:
            return "Cancelled"
        case .shareEnded:
            return "The sender stopped this share."
        case .peerUnreachable:
            return "The device could not be reached."
        case let .hashMismatch(path):
            return "A file failed verification: \(path)"
        case .unsafePath:
            return "The share contained an unsafe path."
        case let .failed(message):
            return message
        }
    }
}

// MARK: - File download support (ByteSink/DownloadTarget over the file system)

/// File-system glue for `DownloadTarget`/`ByteSink`: resume size, an appending
/// sink, and a source over the bytes already present so a resumed file's digest
/// still covers the whole file.
enum DownloadFileSupport {
    static func destination(directory: URL, path: String) -> URL {
        directory.appendingPathComponent(path, isDirectory: false)
    }

    static func existingSize(directory: URL, path: String) -> Int64 {
        let url = destination(directory: directory, path: path)
        let values = try? url.resourceValues(forKeys: [.fileSizeKey])
        return Int64(values?.fileSize ?? 0)
    }

    static func existingSource(directory: URL, path: String) -> ByteSource {
        SecurityScopedByteSource(url: destination(directory: directory, path: path), offset: 0)
    }

    static func sink(directory: URL, path: String, offset: Int64) throws -> ByteSink {
        let url = destination(directory: directory, path: path)
        let parent = url.deletingLastPathComponent()
        try? FileManager.default.createDirectory(at: parent, withIntermediateDirectories: true)
        return try FileByteSink(url: url, offset: offset)
    }
}

/// A `ByteSink` that appends to a file, resuming at `offset` (existing bytes are
/// left in place).
final class FileByteSink: ByteSink {
    private let handle: FileHandle
    private let scoped: Bool

    init(url: URL, offset: Int64) throws {
        self.scoped = url.startAccessingSecurityScopedResource()
        if !FileManager.default.fileExists(atPath: url.path) {
            FileManager.default.createFile(atPath: url.path, contents: nil)
        }
        self.handle = try FileHandle(forWritingTo: url)
        try handle.seek(toOffset: UInt64(max(0, offset)))
    }

    func write(_ bytes: [UInt8], offset: Int, count: Int) throws {
        try handle.write(contentsOf: Data(bytes[offset..<(offset + count)]))
    }

    func close() throws {
        try handle.close()
    }
}

/// A no-op sink used only when a target could not be opened; the write then
/// fails and `DownloadSession` reports `.peerUnreachable`.
final class NullByteSink: ByteSink {
    func write(_ bytes: [UInt8], offset: Int, count: Int) throws {
        throw LanyardSendError.destinationUnavailable
    }
    func close() throws {}
}

enum LanyardSendError: LocalizedError {
    case destinationUnavailable
    case sourceUnavailable

    var errorDescription: String? {
        switch self {
        case .destinationUnavailable: return "The download folder is not writable."
        case .sourceUnavailable: return "The file could not be opened."
        }
    }
}

/// A sequential `ByteSource` over a (possibly security-scoped) file URL.
final class SecurityScopedByteSource: ByteSource {
    private let handle: FileHandle
    private let url: URL
    private let scoped: Bool

    init(url: URL, offset: Int64 = 0) {
        self.url = url
        self.scoped = url.startAccessingSecurityScopedResource()
        self.handle = (try? FileHandle(forReadingFrom: url)) ?? FileHandle.nullDevice
        if offset > 0 { try? handle.seek(toOffset: UInt64(offset)) }
    }

    deinit {
        try? handle.close()
        if scoped { url.stopAccessingSecurityScopedResource() }
    }

    func read(_ buffer: inout [UInt8], offset: Int, count: Int) -> Int {
        guard count > 0, let data = try? handle.read(upToCount: count), !data.isEmpty else {
            return -1
        }
        for (index, byte) in data.enumerated() { buffer[offset + index] = byte }
        return data.count
    }
}

// MARK: - Transport factories
//
// The public model inits take already-built core objects; these factories are
// the only place the package-private seams (`SendFlow.init`, `ShareList.init`,
// `PullBrowse.init`, `PushSource`, `ByteSource`) are touched, so the app target
// only ever sees the public models.

#if canImport(Network) && canImport(Security)

extension SendModel {
    /// Builds a `SendModel` over the real `LanyardPushClient`. Picked URLs are
    /// registered with `registerSource(_:for:)`; the source provider resolves a
    /// queue descriptor through the model's own registry.
    public static func make(
        identity: DeviceIdentity,
        queueFile: URL,
        peers: @escaping () -> [PairedPeer],
        onlineFingerprints: @escaping () -> Set<String>
    ) -> SendModel {
        let peerIdentity = PeerIdentity(device: identity, name: ProcessInfo.processInfo.hostName)
        let registry = SendSourceRegistry()
        let store = JsonFileSendQueueStore(file: queueFile)
        let flow = SendFlow(
            store: store,
            clientFactory: { peer in
                LanyardPushClient(
                    host: peer.host,
                    port: peer.port,
                    identity: peerIdentity,
                    expectedFingerprint: peer.fingerprint
                )
            },
            sourceProvider: { file in
                guard let url = registry.resolve(file.relPath) else { return nil }
                return PushSource(
                    relPath: file.relPath,
                    size: file.size,
                    mtimeMillis: file.mtimeMillis,
                    open: { SecurityScopedByteSource(url: url, offset: 0) }
                )
            }
        )
        let model = SendModel(
            flow: flow,
            peers: peers,
            onlineFingerprints: onlineFingerprints,
            workQueue: DispatchQueue(label: "io.github.tuscani712.lanyard.send")
        )
        model.registry = registry
        return model
    }
}

extension BrowseModel {
    /// Builds a `BrowseModel` over the real pinned `LanyardShareReader` and
    /// `LanyardBrowseClient`.
    public static func make(
        identity: DeviceIdentity,
        peers: @escaping () -> [PairedPeer]
    ) -> BrowseModel {
        let peerIdentity = PeerIdentity(device: identity, name: ProcessInfo.processInfo.hostName)
        return BrowseModel(peers: peers, browserFactory: { peer in
            PullBrowse(
                reader: LanyardShareReader(
                    host: peer.host,
                    port: peer.port,
                    identity: peerIdentity,
                    expectedFingerprint: peer.fingerprint
                ),
                browser: LanyardBrowseClient(
                    host: peer.host,
                    port: peer.port,
                    identity: peerIdentity,
                    expectedFingerprint: peer.fingerprint
                )
            )
        })
    }
}

#endif

#endif
