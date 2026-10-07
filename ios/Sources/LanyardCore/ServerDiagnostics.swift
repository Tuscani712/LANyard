import Foundation

/// A small in-memory ring buffer of server events, so a person can paste what
/// the phone saw without adb (Task 30). It keeps the last `capacity` events,
/// oldest first. Events are deliberately low-detail: no file contents, no full
/// fingerprints (only a short prefix), no tokens, no secrets. The report is
/// redacted again on the way out by `Redaction`.
///
/// Not persisted: it is diagnostic breadcrumbs for the current app run only.
final class ServerDiagnostics {
    private let capacity: Int
    private let clock: () -> Int64
    private var events: [String] = []
    private let lock = NSLock()

    /// An optional sink (used by the app to mirror events to logcat). Never a secret.
    var onRecord: ((String) -> Void)?

    init(
        capacity: Int = 200,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.capacity = capacity
        self.clock = clock
    }

    func record(_ event: String) {
        let line = timestamp() + " " + event
        lock.lock()
        if events.count >= capacity { events.removeFirst() }
        events.append(line)
        lock.unlock()
        onRecord?(line)
    }

    /// The events oldest-first.
    func snapshot() -> [String] {
        lock.lock()
        defer { lock.unlock() }
        return events
    }

    func clear() {
        lock.lock()
        events.removeAll()
        lock.unlock()
    }

    private func timestamp() -> String {
        let ms = Int(clock())
        let s = (ms / 1000) % 86400
        let h = s / 3600
        let m = (s % 3600) / 60
        let sec = s % 60
        return String(format: "%02d:%02d:%02d.%03d", h, m, sec, ms % 1000)
    }
}
