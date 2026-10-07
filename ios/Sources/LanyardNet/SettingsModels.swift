// SettingsModels.swift — the Settings and Troubleshoot view models.
//
// WRITTEN, NOT COMPILED. Guarded with `#if canImport(Combine)` so on Linux
// (where Combine does not exist) this file compiles to nothing and the package
// build/test stay green. On a Mac/iOS toolchain it is compiled for real.
//
// These are *thin* `@MainActor ObservableObject` wrappers over the REAL
// `LanyardCore` types — the same pattern as `ObservableModels.swift` and
// `DevicesModels.swift`:
//   * `SettingsModelVM` wraps `LanyardCore.SettingsModel` and republishes its
//     `settings` on the main actor. The download-folder override is not re-
//     implemented here: it is delegated to the app through a
//     `FolderOverrideBridge`, whose producer is the existing
//     `InboxDestinationAdapter` (a security-scoped bookmark; no typed paths).
//   * `TroubleshootModel` runs `Troubleshoot.checks(env:ios:)`, holds the live
//     `ServerDiagnostics`, and builds the copy report with
//     `Troubleshoot.report(checks:events:generatedAt:)`. The iOS rows come from
//     a live `IOSDiagState`: the Local Network permission (fed by the Devices
//     tab's `DiscoveryService`) and the listener state (fed by
//     `ServerLifecycle`).
//
// There is no assumed view-model surface: every call below is a real public
// `LanyardCore` symbol.

#if canImport(Combine)
import Foundation
import Combine
import LanyardCore

// MARK: - Download-folder override bridge

/// The app-owned seam for the download-folder override. `SettingsModelVM`
/// never touches a bookmark or a file path itself; the app supplies these four
/// closures over the existing `InboxDestinationAdapter`.
///
/// * `existingToken`/`existingName` reconcile an override that is already
///   persisted (the adapter holds it in UserDefaults across launches) with the
///   `AppSettings.downloadFolder` token `SettingsModel` persists.
/// * `choose` persists a security-scoped bookmark for a `UIDocumentPicker` URL
///   and returns the opaque token to store in settings.
/// * `clear` drops the override and returns to `Documents/LANyard`.
public struct FolderOverrideBridge {
    public let existingToken: () -> String?
    public let existingName: () -> String?
    public let choose: (URL) throws -> String
    public let clear: () -> Void

    public init(
        existingToken: @escaping () -> String?,
        existingName: @escaping () -> String?,
        choose: @escaping (URL) throws -> String,
        clear: @escaping () -> Void
    ) {
        self.existingToken = existingToken
        self.existingName = existingName
        self.choose = choose
        self.clear = clear
    }
}

// MARK: - Settings

/// Publishes the current `AppSettings` and forwards each field to
/// `LanyardCore.SettingsModel`, which owns persistence and the single mutation
/// path (`update`). The view binds to the typed setters below; nothing here
/// writes `AppSettings` directly.
@MainActor
public final class SettingsModelVM: ObservableObject {
    /// The current settings, republished for SwiftUI.
    @Published public private(set) var settings: AppSettings

    /// The label for the inbox-folder row. Never a typed path: the default
    /// (`Documents/LANyard`) or the chosen folder's last path component.
    @Published public private(set) var downloadFolderDisplay: String

    /// Whether a security-scoped override folder is in use.
    @Published public private(set) var isUsingOverrideFolder: Bool

    /// Static About facts. The app sets `version` from its bundle.
    public let about: AboutApp

    private let model: SettingsModel
    private let folder: FolderOverrideBridge

    public init(model: SettingsModel, about: AboutApp, folder: FolderOverrideBridge) {
        self.model = model
        self.about = about
        self.folder = folder

        // Reconcile an override the adapter already persisted with the token
        // `SettingsModel` persists, so the first render is accurate.
        if let token = folder.existingToken(), !token.isEmpty {
            model.overrideFolderName = folder.existingName()
            model.setDownloadFolder(bookmarkToken: token)
        }

        self.settings = model.settings
        self.downloadFolderDisplay = model.defaultDownloadFolderDisplay
        self.isUsingOverrideFolder = model.isUsingOverrideFolder

        // `update` fires after every persisted change; marshal to the main
        // actor for SwiftUI (the core is thread-safe but not MainActor-bound).
        model.onChange = { [weak self] new in
            Task { @MainActor in self?.apply(new) }
        }
    }

    // MARK: - Field setters

    public func setTheme(_ value: ThemeMode) { model.theme = value; apply(model.settings) }
    public func setSpeedUnit(_ value: SpeedUnit) { model.speedUnit = value; apply(model.settings) }
    public func setNotifications(_ value: Bool) { model.notifications = value; apply(model.settings) }
    public func setSoundOnComplete(_ value: Bool) { model.soundOnComplete = value; apply(model.settings) }
    public func setWifiOnly(_ value: Bool) { model.wifiOnly = value; apply(model.settings) }
    public func setBandwidthLimitMBps(_ value: Int) { model.bandwidthLimitMBps = value; apply(model.settings) }

    // MARK: - Inbox folder

    /// Persists the picker's security-scoped URL through the bridge, then
    /// records the opaque token in settings. Throws when the app cannot hold
    /// access to the folder (surfaced by the view as an alert).
    public func chooseFolder(_ url: URL) throws {
        let token = try folder.choose(url)
        model.overrideFolderName = url.lastPathComponent
        model.setDownloadFolder(bookmarkToken: token)
        apply(model.settings)
    }

    /// Drops the override and returns to the default download folder.
    public func useDefaultFolder() {
        model.overrideFolderName = nil
        model.useDefaultFolder()
        folder.clear()
        apply(model.settings)
    }

    // MARK: - Internals

    private func apply(_ new: AppSettings) {
        settings = new
        downloadFolderDisplay = model.defaultDownloadFolderDisplay
        isUsingOverrideFolder = model.isUsingOverrideFolder
    }
}

// MARK: - Troubleshoot

/// A live box for the Local Network permission outcome. The Devices tab's
/// `DiscoveryService` is the only thing that can observe TN3179, so `RootView`
/// writes the mapped value here and `TroubleshootModel` reads it at run time.
/// Keeping it a separate observable means the Troubleshoot tab can re-run when
/// the permission flips.
@MainActor
public final class LocalNetworkAccessBox: ObservableObject {
    @Published public var access: LocalNetworkAccess

    public init(access: LocalNetworkAccess = .unknown) {
        self.access = access
    }
}

/// Runs the troubleshoot checks and produces the copyable report. All state
/// lives in `LanyardCore` (`Troubleshoot`, `Diagnostics`, `ServerDiagnostics`);
/// this type only drives them and republishes the result on the main actor.
@MainActor
public final class TroubleshootModel: ObservableObject {
    /// The rows the view renders, in report order. The two iOS rows (ids
    /// `ios-local-network` and `ios-listener`) are included.
    @Published public private(set) var checks: [CheckResult] = []
    /// The server event log, oldest-first, as `HH:MM:SS.mmm message` lines.
    @Published public private(set) var events: [String] = []

    /// The live breadcrumb buffer, also owned by the receive path.
    public let diagnostics: ServerDiagnostics

    private let env: DiagEnv
    private let permission: () -> LocalNetworkAccess
    private let listener: () -> ListenerState
    private let clock: () -> Int64

    public init(
        env: DiagEnv,
        diagnostics: ServerDiagnostics,
        permission: @escaping () -> LocalNetworkAccess,
        listener: @escaping () -> ListenerState,
        clock: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1000) }
    ) {
        self.env = env
        self.diagnostics = diagnostics
        self.permission = permission
        self.listener = listener
        self.clock = clock
    }

    /// The iOS-specific diagnostics, read live from the permission box and the
    /// server lifecycle each time checks run.
    public var iosState: IOSDiagState {
        IOSDiagState(localNetworkAccess: permission(), listener: listener())
    }

    /// Runs every check (platform + iOS) and refreshes the event log.
    public func runChecks() {
        checks = Troubleshoot.checks(env: env, ios: iosState)
        refreshEvents()
    }

    /// Re-reads the server event log without re-running the checks.
    public func refreshEvents() {
        events = diagnostics.snapshot()
    }

    public func clearEvents() {
        diagnostics.clear()
        events = []
    }

    /// The full redacted report, exactly what the Copy button places on the
    /// pasteboard. `Troubleshoot.report` redacts secrets again on the way out.
    public func reportText() -> String {
        Troubleshoot.report(
            checks: checks,
            events: diagnostics.snapshotEvents(),
            generatedAt: clock()
        )
    }
}

// MARK: - iOS diagnostics environment

/// A closure-backed `DiagEnv`. The app supplies the real values; the defaults
/// are deliberately conservative and iOS-appropriate:
///   * `multicastLockHeld` is an Android concept — iOS always reports available.
///   * `ignoringBatteryOptimizations` is an Android concept — iOS reports the
///     row as not restricted so it does not scare anyone.
///
/// MAC-SPIKE: the app should inject live `networkConnected`/`networkMetered`/
/// `localAddresses`/`mdnsDevicesSeen`/`reachTarget`/`probeTarget` values from
/// `NWPathMonitor` and the discovery registry. Until that wiring exists the
/// defaults below keep the report honest but unsurprising.
public struct AppDiagEnv: DiagEnv {
    public var connected: () -> Bool
    public var metered: () -> Bool
    public var restricted: () -> Bool
    public var wifiOnly: () -> Bool
    public var addresses: () -> [String]
    public var multicast: () -> Bool
    public var mdnsSeen: (Int64) -> Int
    public var target: () -> DiagTarget?
    public var probe: (String, Int, String) -> Reachability
    public var folderSelected: () -> Bool
    public var folderFreeBytes: () -> Int64
    public var notificationsAllowed: () -> Bool
    public var batteryExempt: () -> Bool

    public init(
        connected: @escaping () -> Bool = { true },
        metered: @escaping () -> Bool = { false },
        restricted: @escaping () -> Bool = { false },
        wifiOnly: @escaping () -> Bool = { true },
        addresses: @escaping () -> [String] = { [] },
        multicast: @escaping () -> Bool = { true },
        mdnsSeen: @escaping (Int64) -> Int = { _ in 0 },
        target: @escaping () -> DiagTarget? = { nil },
        probe: @escaping (String, Int, String) -> Reachability = { _, _, _ in
            Reachability(tcp: false, tls: false)
        },
        folderSelected: @escaping () -> Bool = { true },
        folderFreeBytes: @escaping () -> Int64 = { -1 },
        notificationsAllowed: @escaping () -> Bool = { true },
        batteryExempt: @escaping () -> Bool = { true }
    ) {
        self.connected = connected
        self.metered = metered
        self.restricted = restricted
        self.wifiOnly = wifiOnly
        self.addresses = addresses
        self.multicast = multicast
        self.mdnsSeen = mdnsSeen
        self.target = target
        self.probe = probe
        self.folderSelected = folderSelected
        self.folderFreeBytes = folderFreeBytes
        self.notificationsAllowed = notificationsAllowed
        self.batteryExempt = batteryExempt
    }

    public func networkConnected() -> Bool { connected() }
    public func networkMetered() -> Bool { metered() }
    public func networkRestricted() -> Bool { restricted() }
    public func wifiOnly() -> Bool { wifiOnly() }
    public func localAddresses() -> [String] { addresses() }
    public func multicastLockHeld() -> Bool { multicast() }
    public func mdnsDevicesSeen(timeoutMillis: Int64) -> Int { mdnsSeen(timeoutMillis) }
    public func reachTarget() -> DiagTarget? { target() }
    public func probeTarget(host: String, port: Int, expectedFingerprint: String) -> Reachability {
        probe(host, port, expectedFingerprint)
    }
    public func downloadFolderSelected() -> Bool { folderSelected() }
    public func downloadFolderFreeBytes() -> Int64 { folderFreeBytes() }
    public func notificationsGranted() -> Bool { notificationsAllowed() }
    public func ignoringBatteryOptimizations() -> Bool { batteryExempt() }
}

// MARK: - TN3179 mapping

#if canImport(Network)
import Network

/// Maps the discovery layer's TN3179 outcome onto the core's `LocalNetworkAccess`
/// so the troubleshoot report can describe it. A pending prompt reads as
/// `unknown` (the report says "not requested yet").
public extension LocalNetworkPermissionState {
    var diagAccess: LocalNetworkAccess {
        switch self {
        case .unknown, .prompting: return .unknown
        case .granted: return .granted
        case .denied: return .denied
        }
    }
}
#endif

#endif
