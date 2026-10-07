import XCTest
@testable import LanyardCore

/// `Devices.build`, `Devices.selfFilter` and `DeviceRegistry`.
///
/// There is no Kotlin unit test for the device list: the merge logic lives in
/// `DevicesViewModel` (an `AndroidViewModel`, hence not unit-testable without
/// Robolectric) and the eviction rules in the Go `discovery.registry`, which has
/// no direct test either. These cases are newly written from the documented
/// behaviour in spec §4.1/§5.1, `registry.go`, `DevicesViewModel.kt` and the
/// Android `DevicesScreen` row rules (paired rows greyed when offline; nearby
/// rows offered a Connect).
final class DevicesTests: XCTestCase {
    private let now: Int64 = 1_000_000
    private let selfFp = String(repeating: "aa", count: 32)
    private let peerFp = String(repeating: "4d635a83f4033d53", count: 4) // 64 hex

    private var peerShort: String { String(peerFp.prefix(16)) }

    private func paired(
        _ fingerprint: String,
        name: String = "Desk",
        host: String = "",
        port: Int = 0,
        browse: Bool = true,
        push: Bool = false
    ) -> PairedPeer {
        PairedPeer(fingerprint: fingerprint, name: name, host: host, port: port,
                   browse: browse, push: push, pairedAt: 0)
    }

    private func discovered(
        _ shortId: String,
        name: String = "Desk",
        host: String = "192.168.1.20",
        port: Int = 47800,
        os: String = "linux",
        lastSeen: Int64 = 0
    ) -> DiscoveredPeer {
        DiscoveredPeer(shortId: shortId, name: name, os: os, host: host, port: port, lastSeen: lastSeen)
    }

    // MARK: - Merge and status

    func testPairedAndOnlineIsPaired() {
        let rows = Devices.build(
            discovered: [discovered(peerShort)],
            paired: [paired(peerFp)],
            onlineFingerprints: [peerFp],
            now: now
        )
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual(.paired, rows[0].status)
        XCTAssertTrue(rows[0].isPaired)
        XCTAssertEqual(peerFp, rows[0].fingerprint)
    }

    func testPairedButOfflineIsOffline() {
        let rows = Devices.build(
            discovered: [],
            paired: [paired(peerFp)],
            onlineFingerprints: [],
            now: now
        )
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual(.offline, rows[0].status)
        XCTAssertFalse(rows[0].browseEnabled)
    }

    func testUnpairedDiscoveredIsOnline() {
        let rows = Devices.build(
            discovered: [discovered(peerShort)],
            paired: [],
            onlineFingerprints: [],
            now: now
        )
        XCTAssertEqual(1, rows.count)
        XCTAssertEqual(.online, rows[0].status)
        XCTAssertFalse(rows[0].isPaired)
        XCTAssertNil(rows[0].fingerprint)
        XCTAssertTrue(rows[0].connectEnabled)
    }

    func testOnlineSetMatchesFullFingerprintOrShortIdCaseInsensitively() {
        let byShort = Devices.build(
            discovered: [discovered(peerShort)],
            paired: [paired(peerFp)],
            onlineFingerprints: [peerShort.uppercased()],
            now: now
        )
        XCTAssertEqual(.paired, byShort[0].status)

        let byFull = Devices.build(
            discovered: [discovered(peerShort)],
            paired: [paired(peerFp)],
            onlineFingerprints: [peerFp.uppercased()],
            now: now
        )
        XCTAssertEqual(.paired, byFull[0].status)
    }

    func testDiscoverySuppliesNameHostAndPort() {
        let rows = Devices.build(
            discovered: [discovered(peerShort, name: "Office Mac", host: "10.0.0.9", port: 55555)],
            paired: [paired(peerFp, name: "Stale", host: "", port: 0)],
            onlineFingerprints: [peerFp],
            now: now
        )
        XCTAssertEqual("Office Mac", rows[0].name)
        XCTAssertEqual("10.0.0.9", rows[0].host)
        XCTAssertEqual(55555, rows[0].port)
    }

    func testFallsBackToPairedRecordWhenNotDiscovered() {
        let rows = Devices.build(
            discovered: [],
            paired: [paired(peerFp, name: "Desk", host: "10.0.0.5", port: 47800)],
            onlineFingerprints: [peerFp],
            now: now
        )
        XCTAssertEqual("Desk", rows[0].name)
        XCTAssertEqual("10.0.0.5", rows[0].host)
        XCTAssertEqual(47800, rows[0].port)
    }

    // MARK: - Self filter

    func testSelfIsDroppedFromDiscoveredAndPaired() {
        let rows = Devices.build(
            discovered: [discovered("aaaaaaaaaaaaaaaa"), discovered(peerShort)],
            paired: [paired(selfFp), paired(peerFp)],
            onlineFingerprints: [selfFp, peerFp],
            now: now,
            selfFingerprint: selfFp
        )
        XCTAssertEqual(1, rows.count, "this device must not list itself")
        XCTAssertEqual(peerFp, rows[0].fingerprint)
    }

    func testUnknownSelfFingerprintHidesNothing() {
        let rows = Devices.build(
            discovered: [discovered(peerShort)],
            paired: [],
            onlineFingerprints: [],
            now: now,
            selfFingerprint: ""
        )
        XCTAssertEqual(1, rows.count)
    }

    func testSelfFilterHelper() {
        let peers = [discovered("aaaaaaaaaaaaaaaa"), discovered(peerShort)]
        XCTAssertEqual(1, Devices.selfFilter(peers, selfFingerprint: selfFp).count)
        XCTAssertEqual(2, Devices.selfFilter(peers, selfFingerprint: "").count)
    }

    // MARK: - Ordering

    func testOrderingIsPairedThenOnlineThenOfflineThenName() {
        let offlineFp = String(repeating: "bb", count: 32)
        let rows = Devices.build(
            discovered: [
                discovered("cccccccccccccccc", name: "Zed"),
                discovered("dddddddddddddddd", name: "Ann"),
                discovered(peerShort, name: "Mid"),
            ],
            paired: [
                paired(peerFp, name: "Mid"),
                paired(offlineFp, name: "Aardvark"),
            ],
            onlineFingerprints: [peerFp],
            now: now
        )
        XCTAssertEqual([.paired, .online, .online, .offline], rows.map(\.status))
        // Within .online, Ann sorts before Zed.
        XCTAssertEqual(["Ann", "Zed"], rows.filter { $0.status == .online }.map(\.name))
    }

    func testOrderingIsDeterministicAcrossRuns() {
        let a = Devices.build(discovered: [discovered("dddddddddddddddd"), discovered("cccccccccccccccc")],
                              paired: [], onlineFingerprints: [], now: now)
        let b = Devices.build(discovered: [discovered("cccccccccccccccc"), discovered("dddddddddddddddd")],
                              paired: [], onlineFingerprints: [], now: now)
        XCTAssertEqual(a, b)
    }

    // MARK: - Enablement

    func testBrowseEnabledOnlyForPairedOnlineWithPermission() {
        let allowed = Devices.build(discovered: [discovered(peerShort)], paired: [paired(peerFp, browse: true)],
                                    onlineFingerprints: [peerFp], now: now)
        XCTAssertTrue(allowed[0].browseEnabled)

        let denied = Devices.build(discovered: [discovered(peerShort)], paired: [paired(peerFp, browse: false)],
                                   onlineFingerprints: [peerFp], now: now)
        XCTAssertFalse(denied[0].browseEnabled)

        let offline = Devices.build(discovered: [], paired: [paired(peerFp, browse: true)],
                                    onlineFingerprints: [], now: now)
        XCTAssertFalse(offline[0].browseEnabled)
    }

    func testConnectDisabledForPairedPeer() {
        let rows = Devices.build(discovered: [discovered(peerShort)], paired: [paired(peerFp)],
                                 onlineFingerprints: [peerFp], now: now)
        XCTAssertFalse(rows[0].connectEnabled)
    }

    // MARK: - Registry

    func testObserveRefreshesAndClearsMisses() {
        let registry = DeviceRegistry(ttlMillis: 1000, removeGraceMillis: 500)
        registry.observe(peerShort, now: 0)
        registry.miss(peerShort, now: 10)
        XCTAssertEqual(1, registry.entry(peerShort)?.misses)
        registry.observe(peerShort, now: 20)
        XCTAssertEqual(0, registry.entry(peerShort)?.misses)
        XCTAssertTrue(registry.entry(peerShort)?.online == true)
    }

    func testTwoMissesMarkOffline() {
        let registry = DeviceRegistry(ttlMillis: 1000, removeGraceMillis: 500)
        registry.observe(peerShort, now: 0)
        registry.miss(peerShort, now: 10)
        XCTAssertEqual(true, registry.entry(peerShort)?.online)
        registry.miss(peerShort, now: 20)
        XCTAssertEqual(false, registry.entry(peerShort)?.online)
        XCTAssertFalse(registry.onlineShortIds().contains(peerShort))
    }

    func testSweepMarksStaleAndThenRemovesAfterGrace() {
        let registry = DeviceRegistry(ttlMillis: 1000, removeGraceMillis: 500)
        registry.observe(peerShort, now: 0)
        // 2×TTL later: offline, still listed.
        registry.sweep(now: 2500)
        XCTAssertEqual(false, registry.entry(peerShort)?.online)
        XCTAssertNotNil(registry.entry(peerShort))
        // Past 2×TTL + grace: gone.
        registry.sweep(now: 4000)
        XCTAssertNil(registry.entry(peerShort))
    }

    func testGoodbyeRemovesImmediately() {
        let registry = DeviceRegistry()
        registry.observe(peerShort, now: 0)
        registry.remove(peerShort)
        XCTAssertNil(registry.entry(peerShort))
    }

    func testRegistryKeyedByShortIdCaseInsensitively() {
        let registry = DeviceRegistry()
        registry.observe(peerShort.uppercased(), now: 0)
        registry.miss(peerShort, now: 1)
        XCTAssertEqual(1, registry.entry(peerShort)?.misses)
        XCTAssertEqual(1, registry.list().count)
    }
}
