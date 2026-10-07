import XCTest
@testable import LanyardCore

private struct FakeMeteredNetwork: MeteredNetwork {
    let metered: Bool
    func isMetered() -> Bool { metered }
}

final class TransferPolicyTests: XCTestCase {
    func testBlocksWhenWifiOnlyAndMetered() {
        XCTAssertNotNil(TransferPolicy.wifiOnlyRefusal(wifiOnly: true, metered: true))
    }

    func testAllowsWhenWifiOnlyButUnmetered() {
        XCTAssertNil(TransferPolicy.wifiOnlyRefusal(wifiOnly: true, metered: false))
    }

    func testAllowsWhenWifiOnlyIsOff() {
        XCTAssertNil(TransferPolicy.wifiOnlyRefusal(wifiOnly: false, metered: true))
    }

    func testFakeMeteredNetworkDrivesTheGate() {
        // The gate takes the boolean from the MeteredNetwork seam, so a fake can
        // stand in for the real ConnectivityManager in a unit test.
        let metered = FakeMeteredNetwork(metered: true)
        let unmetered = FakeMeteredNetwork(metered: false)
        XCTAssertNotNil(TransferPolicy.wifiOnlyRefusal(wifiOnly: true, metered: metered.isMetered()))
        XCTAssertNil(TransferPolicy.wifiOnlyRefusal(wifiOnly: true, metered: unmetered.isMetered()))
    }
}
