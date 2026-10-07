import Foundation

/// A small in-memory ring buffer of server events, so a person can paste what
/// the phone saw without adb (Task 30). It keeps the last `capacity` events,
/// oldest first. Events are deliberately low-detail: no file contents, no full
/// fingerprints (only a short prefix), no tokens, no secrets. The report is
/// redacted again on the way out by `Redaction`.
///
/// Not persisted: it is diagnostic breadcrumbs for the current app run only.
public final class ServerDiagnostics {
    /// One breadcrumb: a millisecond timestamp and the event text.
    public struct Event: Equatable, Sendable {
        /// Epoch milliseconds, matching `Date.timeIntervalSince1970 * 1000`.
        public let timestampMillis: Int64
        /// The event message. Never a secret.
        public let message: String

        public init(timestampMillis: Int64, message: String) {
            self.timestampMillis = timestampMillis
            self.message = message
        }

        /// The `HH:MM:SS.mmm message` line the buffer stores as text.
        public var formatted: String {
            let ms = Int(timestampMillis)
            let secondsOfDay = (ms / 1000) % 86400
            let h = secondsOfDay / 3600
            let m = (secondsOfDay % 3600) / 60
            let s = secondsOfDay % 60
            return String(format: "%02d:%02d:%02d.%03d %@", h, m, s, ms % 1000, message)
        }
    }

    private let capacity: Int
    private let clock: () -> Int64
    private var events: [Event] = []
    private let lock = NSLock()

    /// An optional sink (used by the app to mirror events to logcat). Never a secret.
    public var onRecord: ((String) -> Void)?

    public init(
        capacity: Int = 200,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.capacity = capacity
        self.clock = clock
    }

    public func record(_ event: String) {
        let entry = Event(timestampMillis: clock(), message: event)
        lock.lock()
        if events.count >= capacity { events.removeFirst() }
        events.append(entry)
        lock.unlock()
        onRecord?(entry.formatted)
    }

    /// The events oldest-first, as the formatted `HH:MM:SS.mmm message` lines.
    public func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return events.map { $0.formatted }
    }

    /// The events oldest-first, as structured `Event` values.
    public func snapshotEvents() -> [Event] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }

    public func clear() {
        lock.lock()
        events.removeAll()
        lock.unlock()
    }
}
