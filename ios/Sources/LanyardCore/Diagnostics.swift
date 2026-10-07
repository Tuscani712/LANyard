import Foundation

/// The state of one diagnostic check.
public enum CheckStatus: String {
    case ok = "Ok"
    case warning = "Warning"
    case failed = "Failed"
    case skipped = "Skipped"
}

/// One row of the troubleshoot report.
public struct CheckResult {
    public let id: String
    public let title: String
    public let status: CheckStatus
    public let detail: String
    public let fix: String

    public init(_ id: String, _ title: String, _ status: CheckStatus, _ detail: String, _ fix: String = "") {
        self.id = id
        self.title = title
        self.status = status
        self.detail = detail
        self.fix = fix
    }
}

/// The paired device chosen for the reachability check.
public struct DiagTarget {
    public let name: String
    public let host: String
    public let port: Int
    public let expectedFingerprint: String
}

/// The result of probing a device: TCP, then TLS, then identity.
public struct Reachability {
    public let tcp: Bool
    public let tls: Bool
    public let presentedFingerprint: String?
    public let match: Bool

    public init(tcp: Bool, tls: Bool, presentedFingerprint: String? = nil, match: Bool = false) {
        self.tcp = tcp
        self.tls = tls
        self.presentedFingerprint = presentedFingerprint
        self.match = match
    }
}

/// Everything the diagnostics need, so tests can inject each state. Implemented
/// on iOS by a real environment; the checks themselves stay pure.
public protocol DiagEnv {
    func networkConnected() -> Bool
    func networkMetered() -> Bool
    func networkRestricted() -> Bool
    func wifiOnly() -> Bool
    func localAddresses() -> [String]
    func multicastLockHeld() -> Bool
    func mdnsDevicesSeen(timeoutMillis: Int64) -> Int
    func reachTarget() -> DiagTarget?
    func probeTarget(host: String, port: Int, expectedFingerprint: String) -> Reachability
    func downloadFolderSelected() -> Bool
    /// Free bytes in the download folder, or a negative value if unreadable.
    func downloadFolderFreeBytes() -> Int64
    func notificationsGranted() -> Bool
    func ignoringBatteryOptimizations() -> Bool
}

/// Runs the troubleshoot checks and builds a redacted report. Pure: all input
/// comes from `DiagEnv`. The desktop's peer-port bind, firewall self-probe and
/// clock checks are intentionally omitted (they are rows on neither platform).
public enum Diagnostics {
    public static let MDNS_TIMEOUT_MS: Int64 = 10_000

    /// Below this, a download folder is called low on space.
    public static let LOW_SPACE_BYTES: Int64 = 50 * 1024 * 1024

    public static func run(_ env: DiagEnv) -> [CheckResult] {
        var out: [CheckResult] = []
        out.append(networkCheck(env))
        out.append(addressesCheck(env))
        out.append(multicastCheck(env))
        out.append(mdnsCheck(env))
        out.append(contentsOf: reachabilityChecks(env))
        out.append(diskCheck(env))
        out.append(notificationsCheck(env))
        out.append(batteryCheck(env))
        return out
    }

    private static func networkCheck(_ env: DiagEnv) -> CheckResult {
        let id = "network"
        let title = "Network connection"
        if !env.networkConnected() {
            return CheckResult(
                id, title, .failed, "No network connection.",
                "Connect to Wi-Fi and try again."
            )
        } else if env.networkMetered() && env.wifiOnly() {
            return CheckResult(
                id, title, .warning,
                "Wi-Fi only is on and you are on mobile data.",
                "Connect to Wi-Fi, or turn off Wi-Fi only in Settings."
            )
        } else if env.networkMetered() {
            return CheckResult(
                id, title, .warning,
                "You are on a metered or mobile network.",
                "Connect to Wi-Fi for faster, unmetered transfers."
            )
        } else if env.networkRestricted() {
            return CheckResult(
                id, title, .warning,
                "The network is restricted.",
                "Some guest or public networks block device-to-device traffic."
            )
        } else {
            return CheckResult(id, title, .ok, "Connected on Wi-Fi.")
        }
    }

    private static func addressesCheck(_ env: DiagEnv) -> CheckResult {
        let count = env.localAddresses().count
        if count == 0 {
            return CheckResult(
                "addresses", "Local addresses", .failed, "No local network address found.",
                "Connect to your Wi-Fi network."
            )
        } else {
            return CheckResult("addresses", "Local addresses", .ok, "Found \(count) local address(es).")
        }
    }

    private static func multicastCheck(_ env: DiagEnv) -> CheckResult {
        if env.multicastLockHeld() {
            return CheckResult("multicast", "Multicast lock", .ok, "Multicast reception is available.")
        } else {
            return CheckResult(
                "multicast", "Multicast lock", .warning, "Could not enable multicast reception.",
                "Reconnect to Wi-Fi, then run this check again."
            )
        }
    }

    private static func mdnsCheck(_ env: DiagEnv) -> CheckResult {
        let seen = env.mdnsDevicesSeen(timeoutMillis: MDNS_TIMEOUT_MS)
        if seen > 0 {
            return CheckResult("mdns", "Device discovery", .ok, "Found \(seen) device(s) nearby.")
        } else {
            return CheckResult(
                "mdns", "Device discovery", .warning, "No devices were found automatically.",
                "Make sure both devices are on the same Wi-Fi network, or add a device by link."
            )
        }
    }

    private static func reachabilityChecks(_ env: DiagEnv) -> [CheckResult] {
        guard let target = env.reachTarget() else {
            return [
                CheckResult("reach-tcp", "Device reachability", .skipped, "No device chosen.", "Choose a paired device to test."),
            ]
        }
        let result = env.probeTarget(host: target.host, port: target.port, expectedFingerprint: target.expectedFingerprint)

        let tcp = CheckResult(
            "reach-tcp", "TCP connection",
            result.tcp ? .ok : .failed,
            result.tcp ? "Connected to the device." : "Could not connect to the device.",
            result.tcp ? "" : "Make sure the device is on and on the same network."
        )
        let tls: CheckResult
        if !result.tcp {
            tls = CheckResult("reach-tls", "Secure handshake", .skipped, "Skipped: no connection.", "")
        } else if result.tls {
            tls = CheckResult("reach-tls", "Secure handshake", .ok, "The secure connection was established.")
        } else {
            tls = CheckResult(
                "reach-tls", "Secure handshake", .failed, "The secure connection failed.",
                "Try again; if it keeps failing, the other device may need restarting."
            )
        }
        let identity: CheckResult
        if !result.tls {
            identity = CheckResult("reach-fp", "Device identity", .skipped, "Skipped: no secure connection.", "")
        } else if result.match {
            identity = CheckResult("reach-fp", "Device identity", .ok, "The device's identity matches what you paired with.")
        } else {
            identity = CheckResult(
                "reach-fp", "Device identity", .failed, "The device's identity changed since you paired.",
                "Do not send anything to it. Remove the pairing and pair again only after verifying the new code out of band."
            )
        }
        return [tcp, tls, identity]
    }

    private static func diskCheck(_ env: DiagEnv) -> CheckResult {
        if !env.downloadFolderSelected() {
            return CheckResult(
                "disk", "Download folder", .skipped, "No default download folder is set.",
                "Set one in Settings so downloads always go to the same place."
            )
        }
        let free = env.downloadFolderFreeBytes()
        if free < 0 {
            return CheckResult(
                "disk", "Download folder", .warning, "Could not read the free space.",
                "Pick the folder again in Settings."
            )
        } else if free < LOW_SPACE_BYTES {
            return CheckResult(
                "disk", "Download folder", .warning, "Low free space (\(free / (1024 * 1024)) MB).",
                "Free up space or choose another folder."
            )
        } else {
            return CheckResult("disk", "Download folder", .ok, "\(free / (1024 * 1024)) MB free.")
        }
    }

    private static func notificationsCheck(_ env: DiagEnv) -> CheckResult {
        if env.notificationsGranted() {
            return CheckResult("notifications", "Notifications", .ok, "Notifications are allowed.")
        } else {
            return CheckResult(
                "notifications", "Notifications", .warning, "Notifications are blocked.",
                "Allow them from Settings so you can see transfer progress and results."
            )
        }
    }

    private static func batteryCheck(_ env: DiagEnv) -> CheckResult {
        if env.ignoringBatteryOptimizations() {
            return CheckResult("battery", "Battery optimization", .ok, "This app is not restricted by battery optimization.")
        } else {
            return CheckResult(
                "battery", "Battery optimization", .warning, "Android may pause transfers in the background.",
                "In system Settings, set LANyard to Unrestricted so long transfers can finish."
            )
        }
    }

    /// A plain-text report with secrets redacted.
    public static func copyReport(_ results: [CheckResult], serverEvents: [String] = []) -> String {
        let checks = results.map { r -> String in
            let fix = r.fix.isEmpty ? "" : "\nFix: \(r.fix)"
            return "[\(r.status.rawValue.uppercased())] \(r.title)\n\(r.detail)\(fix)"
        }.joined(separator: "\n\n")
        let events = serverEvents.isEmpty
            ? ""
            : "\n\nServer events (most recent last):\n" + serverEvents.joined(separator: "\n")
        return Redaction.redact(checks + events)
    }
}

/// Removes secrets from report text: tokens, invites and full fingerprints.
public enum Redaction {
    private static let inviteLinkPattern = "lanyard://pair[^\\s]*"
    private static let urlSecretPattern = "([?&](?:t|token|nonce|invite|n)=)[^&\\s]+"
    private static let hex64Pattern = "(?<![0-9a-fA-F])[0-9a-fA-F]{64}(?![0-9a-fA-F])"
    private static let ipv4Pattern = "\\b(?:\\d{1,3}\\.){3}\\d{1,3}\\b"
    // An absolute path under a well-known root (a message can name a file location).
    private static let absPathPattern = "/(?:data|storage|sdcard|system|home|users|tmp|private|var|mnt|cache)/[\\w./\\-]+"

    public static func redact(_ text: String) -> String {
        var out = text
        out = replace(out, pattern: inviteLinkPattern, template: "lanyard://pair?<redacted>")
        out = replace(out, pattern: urlSecretPattern, template: "$1<redacted>")
        out = truncateFullFingerprints(out)
        out = replace(out, pattern: ipv4Pattern, template: "<ip>")
        out = replace(out, pattern: absPathPattern, template: "<path>")
        return out
    }

    private static func replace(_ text: String, pattern: String, template: String) -> String {
        guard let re = try? NSRegularExpression(pattern: pattern) else { return text }
        let range = NSRange(text.startIndex..<text.endIndex, in: text)
        return re.stringByReplacingMatches(in: text, options: [], range: range, withTemplate: template)
    }

    /// Replaces every 64-char hex run with its first 8 characters, matching the
    /// Kotlin `it.value.take(8)`. Done in reverse so UTF-16 ranges stay valid.
    private static func truncateFullFingerprints(_ text: String) -> String {
        guard let re = try? NSRegularExpression(pattern: hex64Pattern) else { return text }
        let full = NSRange(text.startIndex..<text.endIndex, in: text)
        let matches = re.matches(in: text, options: [], range: full)
        guard !matches.isEmpty else { return text }
        let ns = text as NSString
        let mutable = NSMutableString(string: text)
        for m in matches.reversed() {
            let short = String(ns.substring(with: m.range).prefix(8))
            mutable.replaceCharacters(in: m.range, with: short)
        }
        return mutable as String
    }
}
