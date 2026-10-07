import XCTest
@testable import LanyardCore

/// Filling a port-less paired peer's address from mDNS discovery.
final class PeerAddressesTests: XCTestCase {
    private func peer(_ fp: String, _ host: String = "", _ port: Int = 0) -> PairedPeer {
        PairedPeer(fingerprint: fp, name: "Desk", host: host, port: port, browse: true, push: true, pairedAt: 0)
    }

    func testFillsPortAndHostWhenThePeerHasNoPort() {
        let fp = "4d635a83f4033d53" + "a1b2c3d4e5f60718" // 32 chars; short id is first 16
        let updates = PeerAddresses.fillFromDiscovery(
            peers: [peer(fp)],
            discovered: [DiscoveredAddr(shortId: "4d635a83f4033d53", host: "192.168.1.20", port: 47800)]
        )
        XCTAssertEqual(1, updates.count)
        XCTAssertEqual("192.168.1.20", updates[0].host)
        XCTAssertEqual(47800, updates[0].port)
    }

    func testLeavesPeersThatAlreadyHaveAPort() {
        let fp = "4d635a83f4033d53" + "a1b2c3d4e5f60718"
        let updates = PeerAddresses.fillFromDiscovery(
            peers: [peer(fp, "10.0.0.5", 47800)],
            discovered: [DiscoveredAddr(shortId: "4d635a83f4033d53", host: "192.168.1.20", port: 55555)]
        )
        XCTAssertTrue(updates.isEmpty)
    }

    func testNoMatchMeansNoUpdate() {
        let updates = PeerAddresses.fillFromDiscovery(
            peers: [peer("aaaaaaaaaaaaaaaa" + "0000000000000000")],
            discovered: [DiscoveredAddr(shortId: "bbbbbbbbbbbbbbbb", host: "192.168.1.9", port: 47800)]
        )
        XCTAssertTrue(updates.isEmpty)
    }

    func testMatchingIgnoresCaseAndWhitespace() {
        let fp = "4D635A83F4033D53" + "0000000000000000"
        let updates = PeerAddresses.fillFromDiscovery(
            peers: [peer(fp)],
            discovered: [DiscoveredAddr(shortId: "4d635a83f4033d53", host: "192.168.1.20", port: 47800)]
        )
        XCTAssertEqual(1, updates.count)
        XCTAssertEqual(47800, updates[0].port)
    }

    func testIgnoresInvalidDiscoveredPorts() {
        let fp = "4d635a83f4033d53" + "0000000000000000"
        let updates = PeerAddresses.fillFromDiscovery(
            peers: [peer(fp)],
            discovered: [DiscoveredAddr(shortId: "4d635a83f4033d53", host: "192.168.1.20", port: 0)]
        )
        XCTAssertTrue(updates.isEmpty)
    }
}
