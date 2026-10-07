import XCTest
@testable import LanyardCore

/// `DiscoveryTxt` and `BeaconMessage`.
///
/// There is no Kotlin unit test for the TXT/beacon wire format — Android's
/// `NsdDiscovery` is an `NsdManager` wrapper and the parsing lives inside its
/// resolver callback — so these cases are newly written from spec §5.1, the Go
/// reference (`internal/discovery/mdns.go`, `beacon.go`) and the Android
/// fallbacks in `NsdDiscovery.kt`. They use the round-trip `encode`/`parse`
/// helpers rather than hand-typed serialized blobs.
final class DiscoveryTxtTests: XCTestCase {
    private let fp = String(repeating: "4d635a83f4033d53", count: 4) // 64 hex
    private var shortId: String { String(fp.prefix(16)) }

    // MARK: - Short id

    func testShortIdIsFirstSixteenHex() {
        XCTAssertEqual("4d635a83f4033d53", DiscoveryTxt.shortId(fp))
        XCTAssertEqual("4d635a83f4033d53", DiscoveryTxt.shortId("  \(fp)  "))
    }

    func testShortIdOfShortInputIsWholeInput() {
        XCTAssertEqual("abcd", DiscoveryTxt.shortId("abcd"))
    }

    func testIsShortIdRequiresExactlySixteenHex() {
        XCTAssertTrue(DiscoveryTxt.isShortId("4d635a83f4033d53"))
        XCTAssertFalse(DiscoveryTxt.isShortId("4d635a83f4033d5"))   // 15
        XCTAssertFalse(DiscoveryTxt.isShortId("4d635a83f4033d534")) // 17
        XCTAssertFalse(DiscoveryTxt.isShortId("zz635a83f4033d53"))  // non-hex
    }

    // MARK: - TXT

    private func parsed() -> DiscoveryTxt.Parsed {
        DiscoveryTxt.Parsed(
            version: DiscoveryTxt.protocolVersion,
            shortId: shortId,
            deviceLabel: "Study-Mac",
            name: "Study Mac",
            os: "darwin",
            port: 47800
        )
    }

    func testRoundTripsThroughEncodedRecords() throws {
        let records = DiscoveryTxt.encode(parsed())
        let decoded = try XCTUnwrap(DiscoveryTxt.parse(records))
        XCTAssertEqual(parsed(), decoded)
        XCTAssertTrue(decoded.isUsable)
    }

    func testDidFallsBackToIdWhenUnset() throws {
        var p = parsed()
        p = DiscoveryTxt.Parsed(version: p.version, shortId: p.shortId, deviceLabel: "",
                                name: p.name, os: p.os, port: p.port)
        let records = DiscoveryTxt.encode(p)
        let decoded = try XCTUnwrap(DiscoveryTxt.parse(records))
        XCTAssertEqual(shortId, decoded.deviceLabel)
    }

    func testRejectsWrongProtocolVersion() {
        let records = DiscoveryTxt.encode(parsed()).map {
            $0.hasPrefix("v=") ? "v=1" : $0
        }
        XCTAssertNil(DiscoveryTxt.parse(records))
    }

    func testRejectsMissingId() {
        XCTAssertNil(DiscoveryTxt.parse(["v=2", "n=Ghost", "p=47800"]))
    }

    func testNameFallsBackToInstanceName() throws {
        let decoded = try XCTUnwrap(
            DiscoveryTxt.parse(["v=2", "id=\(shortId)", "p=47800"], instanceName: "Study-Mac")
        )
        XCTAssertEqual("Study-Mac", decoded.name)
    }

    func testPortFallsBackToResolvedPort() throws {
        let decoded = try XCTUnwrap(
            DiscoveryTxt.parse(["v=2", "id=\(shortId)"], resolvedPort: 47801)
        )
        XCTAssertEqual(47801, decoded.port)

        let unparseable = try XCTUnwrap(
            DiscoveryTxt.parse(["v=2", "id=\(shortId)", "p=not-a-port"], resolvedPort: 47802)
        )
        XCTAssertEqual(47802, unparseable.port)
    }

    func testOutOfRangePortFallsBack() throws {
        let decoded = try XCTUnwrap(
            DiscoveryTxt.parse(["v=2", "id=\(shortId)", "p=70000"], resolvedPort: 47800)
        )
        XCTAssertEqual(47800, decoded.port)
    }

    func testIgnoresEntriesWithoutAnEqualsSign() throws {
        let decoded = try XCTUnwrap(
            DiscoveryTxt.parse(["v=2", "garbage", "id=\(shortId)", "p=47800"])
        )
        XCTAssertEqual(shortId, decoded.shortId)
    }

    func testUnusableWhenPortMissing() throws {
        let decoded = try XCTUnwrap(DiscoveryTxt.parse(["v=2", "id=\(shortId)"]))
        XCTAssertEqual(0, decoded.port)
        XCTAssertFalse(decoded.isUsable, "a zero port cannot be dialed")
    }

    // MARK: - Raw DNS-SD record

    func testParseRecordReadsLengthPrefixedStrings() throws {
        let data = txtRecord(["v=2", "id=\(shortId)", "p=47800"])
        let dict = DiscoveryTxt.parseRecord(data)
        XCTAssertEqual("2", dict["v"])
        XCTAssertEqual(shortId, dict["id"])
        XCTAssertEqual("47800", dict["p"])
        let decoded = try XCTUnwrap(DiscoveryTxt.parse(dictionary: dict))
        XCTAssertEqual(47800, decoded.port)
    }

    func testParseRecordStopsAtTruncatedEntry() {
        var data = txtRecord(["v=2"])
        data.append(0xFF) // claims a 255-byte entry that is not there
        let dict = DiscoveryTxt.parseRecord(data)
        XCTAssertEqual(["v": "2"], dict)
    }

    // MARK: - Beacon

    private func beacon(type: String = "beacon") -> BeaconMessage {
        BeaconMessage(type: type, announcement: parsed())
    }

    func testBeaconRoundTrips() throws {
        let decoded = try XCTUnwrap(BeaconMessage.parse(beacon().encoded()))
        XCTAssertEqual(beacon(), decoded)
    }

    func testBeaconProbeFlag() throws {
        XCTAssertTrue(try XCTUnwrap(BeaconMessage.parse(beacon(type: "probe").encoded())).isProbe)
        XCTAssertFalse(try XCTUnwrap(BeaconMessage.parse(beacon(type: "beacon").encoded())).isProbe)
    }

    func testBeaconOmitsEmptyDeviceLabel() {
        let announcement = DiscoveryTxt.Parsed(
            version: "2", shortId: shortId, deviceLabel: "", name: "", os: "", port: 1
        )
        let object = BeaconMessage(type: "beacon", announcement: announcement).jsonObject()
        XCTAssertNil(object["did"])
    }

    func testBeaconRejectsWrongVersion() throws {
        var object = beacon().jsonObject()
        object["v"] = "1"
        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertNil(BeaconMessage.parse(data))
    }

    func testBeaconRejectsMissingId() throws {
        var object = beacon().jsonObject()
        object["id"] = ""
        let data = try JSONSerialization.data(withJSONObject: object)
        XCTAssertNil(BeaconMessage.parse(data))
    }

    func testBeaconRejectsMalformedJSON() {
        XCTAssertNil(BeaconMessage.parse(Data("not json".utf8)))
    }

    // MARK: - Helpers

    private func txtRecord(_ entries: [String]) -> Data {
        var bytes: [UInt8] = []
        for entry in entries {
            let utf8 = Array(entry.utf8)
            bytes.append(UInt8(utf8.count))
            bytes.append(contentsOf: utf8)
        }
        return Data(bytes)
    }
}
