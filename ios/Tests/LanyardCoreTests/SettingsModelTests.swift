import XCTest
@testable import LanyardCore

/// Tests written from the documented behaviour of the ported `SettingsHolder`
/// (Android has no unit test for its holder, only for the store). They cover the
/// new single-update path, the convenience accessors and the folder display.
final class SettingsModelTests: XCTestCase {
    private final class RecordingStore: SettingsStore {
        var loaded: AppSettings
        private(set) var saves: [AppSettings] = []

        init(_ initial: AppSettings = AppSettings()) {
            self.loaded = initial
        }

        func load() -> AppSettings { loaded }

        func save(_ settings: AppSettings) {
            saves.append(settings)
            loaded = settings
        }
    }

    func testLoadsInitialFromStore() {
        let store = RecordingStore(AppSettings(theme: .dark, notifications: false))
        let model = SettingsModel(store: store)
        XCTAssertEqual(ThemeMode.dark, model.settings.theme)
        XCTAssertFalse(model.settings.notifications)
        XCTAssertTrue(store.saves.isEmpty)
    }

    func testInitialOverrideSkipsStoreRead() {
        let store = RecordingStore(AppSettings(theme: .dark))
        let model = SettingsModel(store: store, initial: AppSettings(theme: .light))
        XCTAssertEqual(ThemeMode.light, model.settings.theme)
    }

    func testAccessorsPersistAndNotify() {
        let store = RecordingStore()
        let model = SettingsModel(store: store)
        var notified: [AppSettings] = []
        model.onChange = { notified.append($0) }

        model.theme = .dark
        model.speedUnit = .Mbps
        model.notifications = false
        model.soundOnComplete = true
        model.wifiOnly = false

        XCTAssertEqual(5, store.saves.count)
        XCTAssertEqual(5, notified.count)
        XCTAssertEqual(ThemeMode.dark, model.settings.theme)
        XCTAssertEqual(SpeedUnit.Mbps, model.settings.speedUnit)
        XCTAssertFalse(model.settings.notifications)
        XCTAssertTrue(model.settings.soundOnComplete)
        XCTAssertFalse(model.settings.wifiOnly)
        // The last persisted copy is the live one.
        XCTAssertEqual(store.loaded, model.settings)
    }

    func testBandwidthIsClampedOnEveryUpdatePath() {
        let store = RecordingStore()
        let model = SettingsModel(store: store)

        model.bandwidthLimitMBps = -5
        XCTAssertEqual(0, model.settings.bandwidthLimitMBps)

        model.bandwidthLimitMBps = 99_999_999
        XCTAssertEqual(Bandwidth.MAX_MBPS, model.settings.bandwidthLimitMBps)

        model.bandwidthLimitMBps = 7
        XCTAssertEqual(7, model.settings.bandwidthLimitMBps)
    }

    func testRawUpdateClampsBandwidthToo() {
        let store = RecordingStore()
        let model = SettingsModel(store: store)
        model.update { $0.bandwidthLimitMBps = -100 }
        XCTAssertEqual(0, model.settings.bandwidthLimitMBps)
    }

    func testDefaultFolderDisplayWhenNoOverride() {
        let model = SettingsModel(store: RecordingStore())
        XCTAssertEqual("Documents/LANyard", model.defaultDownloadFolderDisplay)
        XCTAssertFalse(model.isUsingOverrideFolder)
        XCTAssertNil(model.settings.downloadFolder)
    }

    func testOverrideFolderDisplayAndUseDefault() {
        let model = SettingsModel(store: RecordingStore())
        model.setDownloadFolder(bookmarkToken: "opaque-bookmark-token")
        XCTAssertTrue(model.isUsingOverrideFolder)
        XCTAssertEqual("Selected folder", model.defaultDownloadFolderDisplay)

        model.overrideFolderName = "My Downloads"
        XCTAssertEqual("My Downloads", model.defaultDownloadFolderDisplay)

        model.useDefaultFolder()
        XCTAssertNil(model.settings.downloadFolder)
        XCTAssertFalse(model.isUsingOverrideFolder)
        XCTAssertEqual("Documents/LANyard", model.defaultDownloadFolderDisplay)
    }

    func testEmptyTokenCountsAsDefault() {
        let model = SettingsModel(store: RecordingStore())
        model.downloadFolder = ""
        XCTAssertFalse(model.isUsingOverrideFolder)
        XCTAssertEqual("Documents/LANyard", model.defaultDownloadFolderDisplay)
    }

    func testAboutAppDefaultsAndOverride() {
        let about = AboutApp()
        XCTAssertEqual("0.0.0", about.version)
        XCTAssertEqual("AGPL-3.0", about.license)
        XCTAssertEqual("https://github.com/Tuscani712/LANyard", about.repoURL)

        let versioned = AboutApp(version: "1.2.3")
        XCTAssertEqual("1.2.3", versioned.version)
        XCTAssertEqual(about.license, versioned.license)
        XCTAssertEqual(about.repoURL, versioned.repoURL)
    }
}
