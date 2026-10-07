import XCTest
@testable import LanyardCore

final class DiagnosticsTests: XCTestCase {
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

    private func status(_ env: FakeEnv, _ id: String) -> CheckStatus {
        Diagnostics.run(env).first { $0.id == id }!.status
    }

    private func detail(_ env: FakeEnv, _ id: String) -> String {
        Diagnostics.run(env).first { $0.id == id }!.detail
    }

    func testHealthyEnvironmentIsAllGreen() {
        let env = FakeEnv()
        env.target = DiagTarget(name: "Desk", host: "10.0.0.9", port: 47800, expectedFingerprint: String(repeating: "ff", count: 32))
        let results = Diagnostics.run(env)
        for r in results {
            XCTAssertEqual(CheckStatus.ok, r.status, "check \(r.id): \(r.detail)")
        }
        XCTAssertFalse(results.contains { $0.id == "peer-port" || $0.id == "firewall" || $0.id == "clock" })
    }

    func testDisconnectedNetworkFails() {
        let env = FakeEnv()
        env.connected = false
        XCTAssertEqual(CheckStatus.failed, status(env, "network"))
    }

    func testMeteredWithWifiOnlyWarnsAboutMobileData() {
        let env = FakeEnv()
        env.metered = true
        env.wifiOnlyFlag = true
        XCTAssertEqual(CheckStatus.warning, status(env, "network"))
        XCTAssertTrue(detail(env, "network").contains("Wi-Fi only is on and you are on mobile data"))
    }

    func testMeteredWithoutWifiOnlyWarnsAboutMetered() {
        let env = FakeEnv()
        env.metered = true
        env.wifiOnlyFlag = false
        XCTAssertEqual(CheckStatus.warning, status(env, "network"))
        XCTAssertTrue(detail(env, "network").contains("metered or mobile"))
    }

    func testRestrictedNetworkWarns() {
        let env = FakeEnv()
        env.restricted = true
        XCTAssertEqual(CheckStatus.warning, status(env, "network"))
    }

    func testMissingAddressesFail() {
        let env = FakeEnv()
        env.addresses = []
        XCTAssertEqual(CheckStatus.failed, status(env, "addresses"))
    }

    func testMulticastUnavailableWarns() {
        let env = FakeEnv()
        env.multicast = false
        XCTAssertEqual(CheckStatus.warning, status(env, "multicast"))
    }

    func testNoDiscoveredDevicesWarns() {
        let env = FakeEnv()
        env.mdnsSeen = 0
        XCTAssertEqual(CheckStatus.warning, status(env, "mdns"))
    }

    func testReachabilityGoodPath() {
        let env = FakeEnv()
        env.target = DiagTarget(name: "Desk", host: "10.0.0.9", port: 47800, expectedFingerprint: String(repeating: "ff", count: 32))
        XCTAssertEqual(CheckStatus.ok, status(env, "reach-tcp"))
        XCTAssertEqual(CheckStatus.ok, status(env, "reach-tls"))
        XCTAssertEqual(CheckStatus.ok, status(env, "reach-fp"))
    }

    func testReachabilityTcpFailureSkipsLaterSteps() {
        let env = FakeEnv()
        env.target = DiagTarget(name: "Desk", host: "10.0.0.9", port: 47800, expectedFingerprint: String(repeating: "ff", count: 32))
        env.reach = Reachability(tcp: false, tls: false)
        XCTAssertEqual(CheckStatus.failed, status(env, "reach-tcp"))
        XCTAssertEqual(CheckStatus.skipped, status(env, "reach-tls"))
        XCTAssertEqual(CheckStatus.skipped, status(env, "reach-fp"))
    }

    func testReachabilityTlsFailureSkipsIdentity() {
        let env = FakeEnv()
        env.target = DiagTarget(name: "Desk", host: "10.0.0.9", port: 47800, expectedFingerprint: String(repeating: "ff", count: 32))
        env.reach = Reachability(tcp: true, tls: false)
        XCTAssertEqual(CheckStatus.ok, status(env, "reach-tcp"))
        XCTAssertEqual(CheckStatus.failed, status(env, "reach-tls"))
        XCTAssertEqual(CheckStatus.skipped, status(env, "reach-fp"))
    }

    func testIdentityMismatchFailsAndOffersNoTrustShortcut() {
        let env = FakeEnv()
        env.target = DiagTarget(name: "Desk", host: "10.0.0.9", port: 47800, expectedFingerprint: String(repeating: "ff", count: 32))
        env.reach = Reachability(tcp: true, tls: true, presentedFingerprint: String(repeating: "00", count: 32), match: false)
        let row = Diagnostics.run(env).first { $0.id == "reach-fp" }!
        XCTAssertEqual(CheckStatus.failed, row.status)
        XCTAssertTrue(row.detail.contains("identity changed"))
        XCTAssertTrue(row.fix.contains("pair again"))
        XCTAssertFalse(row.fix.lowercased().contains("trust it"))
    }

    func testReachabilitySkippedWithoutATarget() {
        let env = FakeEnv()
        env.target = nil
        XCTAssertEqual(CheckStatus.skipped, status(env, "reach-tcp"))
    }

    func testDiskSkippedWhenNoFolderSet() {
        let env = FakeEnv()
        env.folderSelected = false
        XCTAssertEqual(CheckStatus.skipped, status(env, "disk"))
    }

    func testLowDiskSpaceWarns() {
        let env = FakeEnv()
        env.freeBytes = 1024
        XCTAssertEqual(CheckStatus.warning, status(env, "disk"))
    }

    func testUnreadableDiskWarns() {
        let env = FakeEnv()
        env.freeBytes = -1
        XCTAssertEqual(CheckStatus.warning, status(env, "disk"))
    }

    func testNotificationsBlockedWarns() {
        let env = FakeEnv()
        env.notifications = false
        XCTAssertEqual(CheckStatus.warning, status(env, "notifications"))
    }

    func testBatteryRestrictedWarns() {
        let env = FakeEnv()
        env.ignoringBattery = false
        XCTAssertEqual(CheckStatus.warning, status(env, "battery"))
    }

    func testRedactionRemovesSecretsButKeepsShortFingerprint() {
        let fingerprint = String(repeating: "ab12cd34", count: 8)
        let nonce = "0123456789abcdef0123456789abcdef"
        let token = "deadbeefdeadbeefdeadbeefdeadbeef"
        let link = "lanyard://pair?fp=\(fingerprint)&addr=192.168.1.5:47800&n=\(nonce)"
        let out = Redaction.redact("link \(link) token ?t=\(token) fingerprint \(fingerprint) ip 10.0.0.9")

        XCTAssertFalse(out.contains(fingerprint))
        XCTAssertFalse(out.contains(nonce))
        XCTAssertFalse(out.contains(token))
        XCTAssertFalse(out.contains("192.168.1.5"))
        XCTAssertFalse(out.contains("10.0.0.9"))
        XCTAssertTrue(out.contains(String(fingerprint.prefix(8))))
    }

    func testReportOmitsDeviceNameAndAddresses() {
        let env = FakeEnv()
        env.target = DiagTarget(name: "SecretLaptop", host: "192.168.1.9", port: 47800, expectedFingerprint: String(repeating: "fa", count: 32))
        let report = Diagnostics.copyReport(Diagnostics.run(env))
        XCTAssertFalse(report.contains("SecretLaptop"))
        XCTAssertFalse(report.contains("192.168.1.9"))
        XCTAssertFalse(report.contains(String(repeating: "fa", count: 32)))
    }

    func testServerEventsAppearInReportAndAreRedacted() {
        let fp = String(repeating: "ab12cd34", count: 8)
        let out = Diagnostics.copyReport(
            [],
            serverEvents: [
                "00:00:01.000 conn open",
                "00:00:01.020 handshake ok peer=\(fp.prefix(8)) paired=yes",
                "00:00:01.100 req POST /api/v1/push/offer len=90",
                "00:00:01.120 resp 200 POST /api/v1/push/offer",
                "00:00:01.200 req PUT /api/v1/push/p_1/file?path ch=chunked",
                "00:00:01.650 resp 200 PUT /api/v1/push/p_1/file?path (wrote 2097152)",
                "00:00:01.660 conn close peer-closed",
            ]
        )
        XCTAssertTrue(out.contains("Server events"))
        XCTAssertTrue(out.contains("push/offer"))
        XCTAssertTrue(out.contains("conn close peer-closed"))
        XCTAssertFalse(out.contains(fp))
    }

    func testServerDiagnosticsRingIsBounded() {
        let d = ServerDiagnostics(capacity: 3, clock: { 0 })
        for i in 0..<5 { d.record("event-\(i)") }
        XCTAssertEqual(3, d.snapshot().count)
        XCTAssertTrue(d.snapshot().last!.hasSuffix("event-4"))
        XCTAssertFalse(d.snapshot().contains { $0.hasSuffix("event-1") })
    }

    func testRedactionRemovesAbsolutePathsButKeepsReasons() {
        let out = Redaction.redact(
            "resp 500 PUT /file (IOException: Could not write /data/user/0/app/files/spool/p_1/note.txt)"
        )
        XCTAssertFalse(out.contains("/data/user/0"))
        XCTAssertFalse(out.contains("note.txt"))
        XCTAssertTrue(out.contains("IOException"))
    }
}
