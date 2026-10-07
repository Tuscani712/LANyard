import XCTest
@testable import LanyardCore

final class SettingsStoreTests: XCTestCase {
    private func store() -> (JsonFileSettingsStore, URL) {
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("settings-\(UUID().uuidString)")
        try? FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let file = dir.appendingPathComponent("settings.json")
        return (JsonFileSettingsStore(file: file), file)
    }

    func testDefaultsWhenMissing() {
        let (store, _) = store()
        XCTAssertEqual(AppSettings(), store.load())
        XCTAssertEqual(ThemeMode.system, store.load().theme)
        XCTAssertTrue(store.load().notifications)
        XCTAssertFalse(store.load().soundOnComplete)
        XCTAssertTrue(store.load().wifiOnly)
        XCTAssertNil(store.load().downloadFolder)
        XCTAssertEqual(0, store.load().bandwidthLimitMBps)
    }

    func testRoundTripsAcrossInstances() {
        let (store, file) = store()
        store.save(
            AppSettings(
                theme: .dark,
                speedUnit: .Mbps,
                notifications: false,
                soundOnComplete: true,
                wifiOnly: false,
                downloadFolder: "content://com.android.externalstorage.documents/tree/primary%3ADownload",
                bandwidthLimitMBps: 7
            )
        )

        let reopened = JsonFileSettingsStore(file: file).load()
        XCTAssertEqual(ThemeMode.dark, reopened.theme)
        XCTAssertEqual(SpeedUnit.Mbps, reopened.speedUnit)
        XCTAssertFalse(reopened.notifications)
        XCTAssertTrue(reopened.soundOnComplete)
        XCTAssertFalse(reopened.wifiOnly)
        XCTAssertEqual("content://com.android.externalstorage.documents/tree/primary%3ADownload", reopened.downloadFolder)
        XCTAssertEqual(7, reopened.bandwidthLimitMBps)
    }

    func testCorruptFileReadsDefaults() {
        let (store, file) = store()
        try? "{ this is not json".write(to: file, atomically: true, encoding: .utf8)
        XCTAssertEqual(AppSettings(), store.load())
    }

    func testPartialFileFillsMissingFieldsWithDefaults() {
        let (store, file) = store()
        try? "{\"theme\":\"Light\"}".write(to: file, atomically: true, encoding: .utf8)
        let loaded = store.load()
        XCTAssertEqual(ThemeMode.light, loaded.theme)
        XCTAssertEqual(SpeedUnit.MBps, loaded.speedUnit)
        XCTAssertTrue(loaded.notifications)
        XCTAssertFalse(loaded.soundOnComplete)
        XCTAssertTrue(loaded.wifiOnly)
        XCTAssertNil(loaded.downloadFolder)
        XCTAssertEqual(0, loaded.bandwidthLimitMBps)
    }

    func testWritesAtomicallyLeavingNoTempFile() {
        let (store, file) = store()
        store.save(AppSettings(theme: .light))
        XCTAssertTrue(FileManager.default.fileExists(atPath: file.path))
        let leftovers = ((try? FileManager.default.contentsOfDirectory(atPath: file.deletingLastPathComponent().path)) ?? [])
            .filter { $0.hasSuffix(".tmp") }
        XCTAssertTrue(leftovers.isEmpty, "temp file(s) left behind: \(leftovers)")
    }

    func testFormatsSpeedPerUnit() {
        XCTAssertEqual("1.0 MB/s", formatSpeed(1_048_576.0, .MBps))
        XCTAssertEqual("8.0 Mbps", formatSpeed(1_000_000.0, .Mbps))
        XCTAssertEqual("0.0 MB/s", formatSpeed(0.0, .MBps))
    }
}
