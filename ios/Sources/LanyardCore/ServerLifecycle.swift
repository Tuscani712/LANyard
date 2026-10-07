import Foundation

/// The receive listener's lifecycle state.
///
/// Mirrors the Android `PeerService`'s `server != null` flag, made explicit so
/// the transitions are testable without a real socket.
public enum ServerState: String, Equatable, CaseIterable {
    case stopped
    case starting
    case running
    case stopping

    public var isRunning: Bool { self == .running }
}

/// The transport seam the platform supplies. `startListening` binds the socket
/// and returns the bound port; `stopListening` tears it down. Everything above
/// this line is pure and runs on Linux.
public protocol ServerTransport {
    /// Starts listening and returns the bound port.
    func startListening() throws -> Int
    /// Stops listening. Must be safe to call when not listening.
    func stopListening()
}

/// The receive listener's state machine: `stopped -> starting -> running ->
/// stopping -> stopped`.
///
/// Ported from the Android `PeerService.start`/`stop` lifecycle. On iOS the app
/// cannot listen in the background, so `background()` stops the listener and
/// `foreground()` starts it again; `start()` is a no-op while already running,
/// so it is safe to call on every foreground.
public final class ServerLifecycle {
    /// The current state, after the last completed transition.
    public private(set) var state: ServerState = .stopped
    /// The bound port while running; 0 otherwise.
    public private(set) var port: Int = 0
    /// The last start failure, or nil after a successful start.
    public private(set) var lastError: String?

    /// Fired on every transition, including the transient `starting`/`stopping`.
    public var onStateChange: ((ServerState) -> Void)?
    /// Fired when `start()` fails to bind, with the reason.
    public var onError: ((String) -> Void)?
    /// Fired after the listener has stopped, so the app can fail any pushes that
    /// can no longer continue. Wired to
    /// `TransferManager.failPushReceives(reason: "Interrupted")`.
    public var onStopped: (() -> Void)?

    private let transport: ServerTransport
    private let lock = NSRecursiveLock()

    public init(transport: ServerTransport) {
        self.transport = transport
    }

    /// Starts listening. No-op when already starting or running, so it is safe
    /// to call on every foreground. A failure surfaces through `lastError` and
    /// `onError`, and leaves the machine `stopped`.
    public func start() {
        lock.lock(); defer { lock.unlock() }
        guard state == .stopped else { return }
        transition(to: .starting)
        lastError = nil
        do {
            port = try transport.startListening()
            transition(to: .running)
        } catch {
            port = 0
            let message = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
            lastError = message
            transition(to: .stopped)
            onError?(message)
        }
    }

    /// Stops listening. No-op when already stopped. Also fails any push receives
    /// through `onStopped`, mirroring `PeerService.stop`.
    public func stop() {
        lock.lock(); defer { lock.unlock() }
        guard state == .running || state == .starting else { return }
        transition(to: .stopping)
        transport.stopListening()
        port = 0
        transition(to: .stopped)
        onStopped?()
    }

    /// Called when the app becomes active: start the listener.
    public func foreground() {
        start()
    }

    /// Called when the app is backgrounded: stop the listener. iOS cannot keep a
    /// listener alive in the background.
    public func background() {
        stop()
    }

    private func transition(to next: ServerState) {
        state = next
        onStateChange?(next)
    }
}
