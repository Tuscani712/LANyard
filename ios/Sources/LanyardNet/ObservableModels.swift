// ObservableModels.swift — the app-facing, SwiftUI-observable view models.
//
// WRITTEN, NOT COMPILED. Guarded with `#if canImport(Combine)` so on Linux
// (where Combine does not exist) the file compiles to nothing and the package
// build/test stay green. On a Mac/iOS toolchain it is compiled for real.
//
// These are *thin* wrappers: the state machines (`TransferManager`,
// `InboxDestination`, `ServerLifecycle`) stay plain, testable, platform-free
// types in LanyardCore. Each model subscribes to the core's change callback (or
// polls on a call) and republishes it on the main actor for SwiftUI.
//
// `LanyardNet`/`LanyardCore` reference the REAL public API; there is no assumed
// view-model surface here any more.

#if canImport(Combine)
import Foundation
import Combine
import LanyardCore

// MARK: - Transfers

/// Wraps `TransferManager`. Only `records` is published; every mutation goes
/// back through the manager, which fans out to `onChange`.
@MainActor
public final class TransfersModel: ObservableObject {
    @Published public private(set) var records: [TransferRecord]

    private let manager: TransferManager

    public init(manager: TransferManager) {
        self.manager = manager
        self.records = manager.records
        // Marshal to the main actor: the manager may fire from any thread.
        manager.onChange = { [weak self] in
            Task { @MainActor in self?.refresh() }
        }
    }

    /// True when at least one finished (`Done`/`Failed`/`Cancelled`) row exists,
    /// i.e. "Clear history" would remove something.
    public var hasFinished: Bool {
        records.contains { !$0.state.isActive }
    }

    /// Removes one finished row. The core has no `cancel`, so active rows are
    /// not actionable here.
    public func dismiss(_ id: String) {
        manager.dismiss(id: id)
    }

    public func clearHistory() {
        manager.clearHistory()
    }

    private func refresh() {
        records = manager.records
    }
}

// MARK: - Inbox destination

/// Wraps the core `InboxDestination` plus the iOS `InboxDestinationAdapter` that
/// supplies the actual folder. Republished values are read back from the core so
/// the UI and the receiver always agree on writability.
@MainActor
public final class InboxModel: ObservableObject {
    @Published public private(set) var isWritable: Bool
    @Published public private(set) var displayPath: String
    @Published public private(set) var isUsingOverride: Bool

    /// The core's refusal string, shown when no folder is writable.
    public let refusalMessage: String

    private let destination: InboxDestination
    private let adapter: InboxDestinationAdapter

    public init(destination: InboxDestination, adapter: InboxDestinationAdapter) {
        self.destination = destination
        self.adapter = adapter
        self.refusalMessage = InboxDestination.refusalMessage
        self.isWritable = destination.writableRoot() != nil
        self.displayPath = adapter.displayPath
        self.isUsingOverride = adapter.isUsingOverride
    }

    /// Drops any override and returns to Documents/LANyard.
    public func useDefault() {
        adapter.useDefault()
        refresh()
    }

    /// Persists a security-scoped bookmark for a folder chosen in the picker.
    public func choose(_ url: URL) throws {
        try adapter.setOverride(from: url)
        refresh()
    }

    private func refresh() {
        isWritable = destination.writableRoot() != nil
        displayPath = adapter.displayPath
        isUsingOverride = adapter.isUsingOverride
    }
}

// MARK: - Server

/// Wraps `ServerLifecycle`. Mirrors `state`/`lastError` and forwards
/// `start()`/`stop()`.
@MainActor
public final class ServerModel: ObservableObject {
    @Published public private(set) var state: ServerState
    @Published public private(set) var lastError: String?

    private let lifecycle: ServerLifecycle

    public init(lifecycle: ServerLifecycle) {
        self.lifecycle = lifecycle
        self.state = lifecycle.state
        self.lastError = lifecycle.lastError
        lifecycle.onStateChange = { [weak self] state in
            Task { @MainActor in self?.state = state }
        }
        lifecycle.onError = { [weak self] message in
            Task { @MainActor in self?.lastError = message }
        }
    }

    public var running: Bool { state.isRunning }

    public func start() { lifecycle.start() }
    public func stop() { lifecycle.stop() }
}

// MARK: - Receive approval

/// One pending receive offer as the prompt shows it.
public struct ReceiveOffer: Identifiable, Equatable {
    public let id: String
    public let peerName: String
    public let files: Int
    public let total: Int64
    public let names: [String]

    public init(id: String, peerName: String, files: Int, total: Int64, names: [String]) {
        self.id = id
        self.peerName = peerName
        self.files = files
        self.total = total
        self.names = names
    }
}

/// The source of the "Receive files?" prompt. `pending` drives the sheet; the
/// request side parks on `resolve` until `answer(_:)` is called.
///
/// MAC-SPIKE: this is the UI half of the approval bridge only. The wiring to the
/// receive path is not done yet. In the intended wiring:
///   * `InboxReceiver.onOffer(pushId, peerFp, files, total)` announces an offer;
///     the router turns it into a `ReceiveOffer` (with the offered names) and
///     calls `present(_:resolve:)`.
///   * `PeerServerConfig.approval` runs on the listener thread and must block
///     until the person answers; it would wait on a continuation/semaphore that
///     `answer(_:)` resumes, then return `accepted`.
/// Both callbacks are currently nil (see `AppServices`), so no offer is ever
/// presented until that bridge is built.
@MainActor
public final class ReceiveApprovalModel: ObservableObject {
    @Published public var pending: ReceiveOffer?

    private var resolve: ((Bool) -> Void)?

    public init() {}

    /// Presents `offer` and stores the callback that `answer(_:)` resolves. A
    /// second offer replaces the first; the replaced callback is resolved `false`.
    public func present(_ offer: ReceiveOffer, resolve: @escaping (Bool) -> Void) {
        self.resolve?(false)
        self.pending = offer
        self.resolve = resolve
    }

    /// Answers the pending offer. No-op when nothing is pending.
    public func answer(_ accepted: Bool) {
        let resolve = self.resolve
        self.resolve = nil
        pending = nil
        resolve?(accepted)
    }
}

// MARK: - Server transport

#if canImport(Network) && canImport(Security)
import Network
import Security

/// The `LanyardNet` concrete `ServerTransport`: owns a `PeerListener` for the
/// lifetime of a start/stop cycle.
///
/// MAC-SPIKE: `PeerListener`'s API is `init(identity:port:handler:)` +
/// `start() -> UInt16` / `stop()`, so the conformance below is real. The
/// *handler* is a stub: the real one is `PeerServerAdapter.handler()` fed by a
/// `PeerServerConfig` (sessions, `InboxReceiver`, `PairInvites`, the trust
/// lookup, and the `approval` bridge to `ReceiveApprovalModel`). That wiring is
/// the same MAC-SPIKE documented on `ReceiveApprovalModel` and `AppServices`.
public final class AppServerTransport: ServerTransport {
    private let port: UInt16
    private var listener: PeerListener?

    public init(port: UInt16 = 0) {
        self.port = port
    }

    public func startListening() throws -> Int {
        let material = try SecIdentityFactory.loadOrCreate(deviceName: ProcessInfo.processInfo.hostName)
        let listener = PeerListener(identity: material.identity, port: port, handler: Self.stubHandler)
        self.listener = listener
        return Int(try listener.start())
    }

    public func stopListening() {
        listener?.stop()
        listener = nil
    }

    // MAC-SPIKE: placeholder router. Wire to `PeerServerAdapter` (which needs the
    // core state machines) when the receive path is assembled.
    private static func stubHandler(_ request: PeerRequest) -> PeerResponse {
        PeerResponse(status: 503, text: #"{"error":"receive server not wired yet"}"#)
    }
}
#endif

#endif
