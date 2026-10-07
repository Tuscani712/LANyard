import Foundation

/// Which color scheme the app renders, or the system default.
enum ThemeMode: String, Codable {
    case system = "System"
    case light = "Light"
    case dark = "Dark"
}

/// How transfer speeds are shown.
enum SpeedUnit: String, Codable {
    case MBps = "MBps"
    case Mbps = "Mbps"
}

/// User-facing preferences that persist across restarts.
struct AppSettings: Codable, Equatable {
    var theme: ThemeMode = .system
    var speedUnit: SpeedUnit = .MBps
    var notifications: Bool = true
    var soundOnComplete: Bool = false
    /// Refuse transfers on a metered or mobile connection.
    var wifiOnly: Bool = true
    /// Persisted SAF tree URI for downloads, or null to ask each time.
    var downloadFolder: String? = nil
    /// Bandwidth cap in MB/s; 0 means unlimited.
    var bandwidthLimitMBps: Int = 0

    init(
        theme: ThemeMode = .system,
        speedUnit: SpeedUnit = .MBps,
        notifications: Bool = true,
        soundOnComplete: Bool = false,
        wifiOnly: Bool = true,
        downloadFolder: String? = nil,
        bandwidthLimitMBps: Int = 0
    ) {
        self.theme = theme
        self.speedUnit = speedUnit
        self.notifications = notifications
        self.soundOnComplete = soundOnComplete
        self.wifiOnly = wifiOnly
        self.downloadFolder = downloadFolder
        self.bandwidthLimitMBps = bandwidthLimitMBps
    }

    private enum CodingKeys: String, CodingKey {
        case theme, speedUnit, notifications, soundOnComplete, wifiOnly, downloadFolder, bandwidthLimitMBps
    }

    // Gson fills any field absent from the JSON with the value it had at
    // construction, so a partial settings file keeps the defaults for the
    // fields it omits. decodeIfPresent mirrors that.
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        theme = try c.decodeIfPresent(ThemeMode.self, forKey: .theme) ?? .system
        speedUnit = try c.decodeIfPresent(SpeedUnit.self, forKey: .speedUnit) ?? .MBps
        notifications = try c.decodeIfPresent(Bool.self, forKey: .notifications) ?? true
        soundOnComplete = try c.decodeIfPresent(Bool.self, forKey: .soundOnComplete) ?? false
        wifiOnly = try c.decodeIfPresent(Bool.self, forKey: .wifiOnly) ?? true
        downloadFolder = try c.decodeIfPresent(String.self, forKey: .downloadFolder)
        bandwidthLimitMBps = try c.decodeIfPresent(Int.self, forKey: .bandwidthLimitMBps) ?? 0
    }
}

/// Persists `AppSettings`. Implementations must never throw on read.
protocol SettingsStore {
    func load() -> AppSettings
    func save(_ settings: AppSettings)
}

/// A `SettingsStore` backed by a single JSON file. Writes go to a sibling temp
/// file and are atomically renamed into place. A missing, unreadable or partial
/// file reads as `AppSettings` defaults, so a bad file can never keep the app
/// from starting.
final class JsonFileSettingsStore: SettingsStore {
    private let url: URL
    private let encoder: JSONEncoder
    private let decoder = JSONDecoder()

    init(file: URL) {
        self.url = file
        let encoder = JSONEncoder()
        encoder.outputFormatting = [.prettyPrinted]
        self.encoder = encoder
    }

    func load() -> AppSettings {
        guard let data = try? Data(contentsOf: url) else { return AppSettings() }
        return (try? decoder.decode(AppSettings.self, from: data)) ?? AppSettings()
    }

    func save(_ settings: AppSettings) {
        guard let data = try? encoder.encode(settings) else { return }
        try? atomicWrite(data, to: url)
    }
}

/// Writes `contents` to `<name>.tmp` then renames it into place, replacing any
/// existing file. Mirrors `Files.move(..., REPLACE_EXISTING)`.
func atomicWrite(_ contents: Data, to file: URL) throws {
    let fm = FileManager.default
    let dir = file.deletingLastPathComponent()
    try fm.createDirectory(at: dir, withIntermediateDirectories: true)
    let tmp = dir.appendingPathComponent(file.lastPathComponent + ".tmp")
    try contents.write(to: tmp)
    if fm.fileExists(atPath: file.path) {
        _ = try fm.replaceItemAt(file, withItemAt: tmp)
    } else {
        try fm.moveItem(at: tmp, to: file)
    }
}

/// Formats a byte-per-second rate for display, in the chosen unit.
func formatSpeed(_ bytesPerSecond: Double, _ unit: SpeedUnit) -> String {
    switch unit {
    case .MBps:
        return String(format: "%.1f MB/s", bytesPerSecond / 1_048_576.0)
    case .Mbps:
        return String(format: "%.1f Mbps", bytesPerSecond * 8.0 / 1_000_000.0)
    }
}
