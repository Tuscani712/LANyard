import XCTest
@testable import LanyardCore

/// Tests written from the documented behaviour of the new iOS-specific checks
/// and report (Android has no equivalent seam; its report test lives in
/// `DiagnosticsTests`). The base checks are covered there.
final class TroubleshootTests: XCTestCase {
    private final class FakeEnv: DiagEnv {
        var connected = true
        var metered = false
        var restricted = false
        var wifiOnlyFlag = true
        var addresses = ["10.0.0.5"]
        var multicast = true
        var mdnsSeen = 2
        var target: DiagTarget?
        var reach = Reachability(tcp: true, tls: true, presentedFingerprint: "fp", match: true)
        var folderSelected = true
        var freeBytes: Int64 = 500 * 1024 * 1024
        var notifications = true
        var ignoringBattery = true

        func networkConnected() -> Bool { connected }
        func networkMetered() -> Bool { metered }
        func networkRestricted() -> Bool { restricted }
        func wifiOnly() -> Bool { wifiOnlyFlag }
        func localAddresses() -> [String] { addresses }
        func multicastLockHeld() -> Bool { multicast }
        func mdnsDevicesSeen(timeoutMillis: Int64) -> Int { mdnsSeen }
        func reachTarget() -> DiagTarget? { target }
        func probeTarget(host: String, port: Int, expectedFingerprint: String) -> Reachability { reach }
        func downloadFolderSelected() -> Bool { folderSelected }
        func downloadFolderFreeBytes() -> Int64 { freeBytes }
        func notificationsGranted() -> Bool { notifications }
        func ignoringBatteryOptimizations() -> Bool { ignoringBattery }
    }

    private func row(_ state: IOSDiagState, _ id: String) -> CheckResult {
        Troubleshoot.iosChecks(state).first { $0.id == id }!
    }

    func testIOSChecksGrantedAndRunningAreOk() {
        let state = IOSDiagState(localNetworkAccess: .granted, listener: .running)
        for r in Troubleshoot.iosChecks(state) {
            XCTAssertEqual(.ok, r.status, "check \(r.id): \(r.detail)")
        }
    }

    func testLocalNetworkDeniedFailsWithSettingsFix() {
        let r = row(IOSDiagState(localNetworkAccess: .denied, listener: .running), "ios-local-network")
        XCTAssertEqual(.failed, r.status)
        XCTAssertTrue(r.fix.contains("Settings"))
        XCTAssertTrue(r.fix.contains("Local Network"))
    }

    func testLocalNetworkUnknownWarnsNotAskedYet() {
        let r = row(IOSDiagState(localNetworkAccess: .unknown, listener: .running), "ios-local-network")
        XCTAssertEqual(.warning, r.status)
        XCTAssertTrue(r.detail.contains("not been requested"))
    }

    func testListenerBackgroundedWarns() {
        let r = row(IOSDiagState(localNetworkAccess: .granted, listener: .backgrounded), "ios-listener")
        XCTAssertEqual(.warning, r.status)
        XCTAssertTrue(r.detail.contains("background"))
    }

    func testListenerStoppedWarns() {
        let r = row(IOSDiagState(localNetworkAccess: .granted, listener: .stopped), "ios-listener")
        XCTAssertEqual(.warning, r.status)
        XCTAssertTrue(r.detail.contains("stopped"))
    }

    func testChecksAppendsIOsRowsOnlyWhenSupplied() {
        let env = FakeEnv()
        let base = Troubleshoot.checks(env: env)
        XCTAssertEqual(Diagnostics.run(env).count, base.count)
        XCTAssertFalse(base.contains { $0.id.hasPrefix("ios-") })

        let withIOS = Troubleshoot.checks(env: env, ios: IOSDiagState(localNetworkAccess: .granted, listener: .running))
        XCTAssertEqual(base.count + 2, withIOS.count)
        XCTAssertEqual("ios-local-network", withIOS[withIOS.count - 2].id)
        XCTAssertEqual("ios-listener", withIOS[withIOS.count - 1].id)
    }

    func testReportIsDeterministicForFixedInputs() {
        let checks = [CheckResult("network", "Network connection", .ok, "Connected on Wi-Fi.")]
        let events = [ServerDiagnostics.Event(timestampMillis: 0, message: "conn open")]
        let a = Troubleshoot.report(checks: checks, events: events, generatedAt: 0)
        let b = Troubleshoot.report(checks: checks, events: events, generatedAt: 0)
        XCTAssertEqual(a, b)
        XCTAssertTrue(a.hasPrefix("LANyard diagnostics\n"))
        XCTAssertTrue(a.contains("1970-01-01T00:00:00Z"))
        XCTAssertTrue(a.contains("[OK] Network connection"))
        XCTAssertTrue(a.contains("00:00:00.000 conn open"))
    }

    func testReportRedactsSecretsAndPaths() {
        let fingerprint = String(repeating: "ab12cd34", count: 8)
        let checks = [
            CheckResult("net", "Network", .ok, "Connected from 192.168.1.9."),
            CheckResult("fp", "Fingerprint", .ok, "Presented \(fingerprint)"),
        ]
        let events = [
            ServerDiagnostics.Event(
                timestampMillis: 1000,
                message: "write failed at /data/user/0/app/files/note.txt"
            ),
        ]
        let out = Troubleshoot.report(checks: checks, events: events, generatedAt: 1000)
        XCTAssertFalse(out.contains("192.168.1.9"))
        XCTAssertTrue(out.contains("<ip>"))
        XCTAssertFalse(out.contains(fingerprint))
        XCTAssertTrue(out.contains(String(fingerprint.prefix(8))))
        XCTAssertFalse(out.contains("/data/user/0"))
        XCTAssertFalse(out.contains("note.txt"))
    }

    func testReportCapsEventsAtTwoHundredMostRecent() {
        let events = (0..<205).map { ServerDiagnostics.Event(timestampMillis: Int64($0), message: "event-\($0)") }
        let out = Troubleshoot.report(checks: [], events: events, generatedAt: 0)
        XCTAssertTrue(out.contains("event-204"))
        XCTAssertFalse(out.contains("event-4\n"))
        XCTAssertTrue(out.contains("event-5\n"))
    }

    func testReportWithoutEventsHasNoEventSection() {
        let out = Troubleshoot.report(
            checks: [CheckResult("network", "Network", .ok, "Connected.")],
            events: [],
            generatedAt: 0
        )
        XCTAssertFalse(out.contains("Server events"))
    }
}
