import Foundation

/// iOS's Local Network permission outcome, as far as the core can describe it.
/// The app maps its `LocalNetworkPermissionState` (TN3179) onto this.
public enum LocalNetworkAccess: String, Equatable, CaseIterable, Sendable {
    /// No browse has run yet; the system prompt has not been seen.
    case unknown
    /// Local Network access is allowed.
    case granted
    /// Local Network access was denied; only Settings can undo it.
    case denied
}

/// The receive listener's user-visible state. The app supplies this from its
/// `ServerLifecycle` plus the scene phase.
public enum ListenerState: String, Equatable, CaseIterable, Sendable {
    /// Listening and reachable.
    case running
    /// Stopped because the app is in the background (iOS cannot listen there).
    case backgrounded
    /// Not listening.
    case stopped
}

/// The iOS-specific diagnostics the app injects. Core cannot observe these
/// itself; the app owns the permission API and the scene/lifecycle state.
public struct IOSDiagState: Equatable, Sendable {
    public var localNetworkAccess: LocalNetworkAccess
    public var listener: ListenerState

    public init(
        localNetworkAccess: LocalNetworkAccess = .unknown,
        listener: ListenerState = .stopped
    ) {
        self.localNetworkAccess = localNetworkAccess
        self.listener = listener
    }
}

/// The troubleshoot checks plus the copyable report. Reuses the platform-
/// independent `Diagnostics`, `Redaction` and `ServerDiagnostics`, and adds the
/// iOS-specific rows the app supplies through `IOSDiagState`. The report is pure
/// and testable: given the same inputs it always produces the same text.
public enum Troubleshoot {
    /// Only the most recent events are included in a report.
    public static let maxEvents = 200

    /// The platform-independent checks, with the iOS rows appended when the app
    /// supplies them. The iOS rows keep their ids (`ios-local-network`,
    /// `ios-listener`) so a view can find them.
    public static func checks(env: DiagEnv, ios: IOSDiagState? = nil) -> [CheckResult] {
        var out = Diagnostics.run(env)
        if let ios {
            out.append(contentsOf: iosChecks(ios))
        }
        return out
    }

    /// The two iOS-specific rows, in report order.
    public static func iosChecks(_ state: IOSDiagState) -> [CheckResult] {
        [localNetworkCheck(state.localNetworkAccess), listenerCheck(state.listener)]
    }

    /// A plain-text report with a UTC header, every check row, and the recent
    /// server events, with secrets redacted. Takes at most the last `maxEvents`
    /// events.
    public static func report(
        checks: [CheckResult],
        events: [ServerDiagnostics.Event],
        generatedAt: Int64
    ) -> String {
        var text = "LANyard diagnostics\nGenerated: \(utcStamp(generatedAt))\n\n"
        text += checks.map(row).joined(separator: "\n\n")
        if !events.isEmpty {
            let recent = events.count > maxEvents ? Array(events.suffix(maxEvents)) : events
            let log = recent.map { $0.formatted }.joined(separator: "\n")
            text += "\n\nServer events (most recent last):\n" + log
        }
        return Redaction.redact(text)
    }

    // MARK: - Rows

    private static func localNetworkCheck(_ access: LocalNetworkAccess) -> CheckResult {
        switch access {
        case .granted:
            return CheckResult(
                "ios-local-network", "Local Network access", .ok,
                "Local Network access is granted."
            )
        case .denied:
            return CheckResult(
                "ios-local-network", "Local Network access", .failed,
                "Local Network access is off.",
                "Open Settings → LANyard and turn on Local Network."
            )
        case .unknown:
            return CheckResult(
                "ios-local-network", "Local Network access", .warning,
                "Local Network access has not been requested yet.",
                "Open LANyard and let it scan the local network to trigger the system prompt."
            )
        }
    }

    private static func listenerCheck(_ state: ListenerState) -> CheckResult {
        switch state {
        case .running:
            return CheckResult(
                "ios-listener", "Receive listener", .ok,
                "The receiver is listening for transfers."
            )
        case .backgrounded:
            return CheckResult(
                "ios-listener", "Receive listener", .warning,
                "The receiver is paused while LANyard is in the background.",
                "Keep LANyard open to receive; iOS cannot listen in the background."
            )
        case .stopped:
            return CheckResult(
                "ios-listener", "Receive listener", .warning,
                "The receiver is stopped.",
                "Open LANyard to start receiving transfers."
            )
        }
    }

    // MARK: - Internals

    private static func row(_ r: CheckResult) -> String {
        let fix = r.fix.isEmpty ? "" : "\nFix: \(r.fix)"
        return "[\(r.status.rawValue.uppercased())] \(r.title)\n\(r.detail)\(fix)"
    }

    /// `2024-01-02T03:04:05Z` from epoch milliseconds, in UTC. Deterministic.
    private static func utcStamp(_ epochMillis: Int64) -> String {
        let date = Date(timeIntervalSince1970: Double(epochMillis) / 1000.0)
        let formatter = ISO8601DateFormatter()
        formatter.timeZone = TimeZone(identifier: "UTC")
        formatter.formatOptions = [.withInternetDateTime]
        return formatter.string(from: date)
    }
}
