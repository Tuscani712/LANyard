import Foundation

/// Static "About" facts for the settings screen. `version` is a constant the app
/// overrides from its bundle (`CFBundleShortVersionString`); the license and
/// repository URL are fixed.
public struct AboutApp: Equatable, Sendable {
    /// The app version. The app replaces the placeholder from its bundle.
    public var version: String
    /// The app's license.
    public let license: String = "AGPL-3.0"
    /// Where the app is published.
    public let repoURL: String = "https://github.com/Tuscani712/LANyard"

    public init(version: String = "0.0.0") {
        self.version = version
    }
}

/// A view-model over `AppSettings`/`SettingsStore`: it holds the current
/// settings, persists every change through one path (`update`), and exposes a
/// typed accessor for each field. Foundation-only, so it runs and is tested on
/// Linux.
///
/// Ported from the Android `SettingsHolder`: the same "apply a block, store the
/// result" shape, made observable through `onChange` instead of a `StateFlow`.
public final class SettingsModel {
    /// The current settings. Never mutated from outside; use `update`.
    public private(set) var settings: AppSettings

    /// Fired after every persisted change, with the new settings.
    public var onChange: ((AppSettings) -> Void)?

    /// Shown when no override folder is set.
    public static let defaultFolderDisplay = "Documents/LANyard"
    /// Shown for an override whose human-readable name the app has not supplied.
    public static let fallbackOverrideDisplay = "Selected folder"

    /// The display name for a chosen override folder, supplied by the app after
    /// it resolves the bookmark token. Purely presentational.
    public var overrideFolderName: String?

    private let store: SettingsStore
    private let lock = NSRecursiveLock()

    /// Builds the model, loading from `store` unless `initial` is given.
    public init(store: SettingsStore, initial: AppSettings? = nil) {
        self.store = store
        self.settings = initial ?? store.load()
    }

    /// The single mutation path: applies `block` to a copy, clamps the bandwidth
    /// limit, persists the result, then notifies. No change is skipped, so a
    /// caller can force a rewrite by passing an empty block.
    public func update(_ block: (inout AppSettings) -> Void) {
        lock.lock()
        var next = settings
        block(&next)
        next.bandwidthLimitMBps = Bandwidth.clampMBps(next.bandwidthLimitMBps)
        settings = next
        store.save(next)
        lock.unlock()
        onChange?(next)
    }

    // MARK: - Convenience accessors

    public var theme: ThemeMode {
        get { settings.theme }
        set { update { $0.theme = newValue } }
    }

    public var speedUnit: SpeedUnit {
        get { settings.speedUnit }
        set { update { $0.speedUnit = newValue } }
    }

    public var notifications: Bool {
        get { settings.notifications }
        set { update { $0.notifications = newValue } }
    }

    public var soundOnComplete: Bool {
        get { settings.soundOnComplete }
        set { update { $0.soundOnComplete = newValue } }
    }

    public var wifiOnly: Bool {
        get { settings.wifiOnly }
        set { update { $0.wifiOnly = newValue } }
    }

    /// Bandwidth cap in MB/s; `0` means unlimited. Values are clamped by
    /// `update`.
    public var bandwidthLimitMBps: Int {
        get { settings.bandwidthLimitMBps }
        set { update { $0.bandwidthLimitMBps = newValue } }
    }

    /// The opaque bookmark token for the download folder, or nil for the default.
    /// Core never stores a typed path.
    public var downloadFolder: String? {
        get { settings.downloadFolder }
        set { update { $0.downloadFolder = newValue } }
    }

    /// The label for the download-folder row: the default folder name, or the
    /// app-supplied override name.
    public var defaultDownloadFolderDisplay: String {
        guard let token = settings.downloadFolder, !token.isEmpty else {
            return Self.defaultFolderDisplay
        }
        return overrideFolderName ?? Self.fallbackOverrideDisplay
    }

    /// Whether an override folder is set.
    public var isUsingOverrideFolder: Bool {
        !(settings.downloadFolder?.isEmpty ?? true)
    }

    // MARK: - Actions

    /// Clears the override and returns to the default download folder.
    public func useDefaultFolder() {
        update { $0.downloadFolder = nil }
    }

    /// Sets (or clears) the override bookmark token.
    public func setDownloadFolder(bookmarkToken: String?) {
        update { $0.downloadFolder = bookmarkToken }
    }
}
