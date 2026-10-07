import Foundation

/// Parsing of the `_lanyard._tcp` DNS-SD TXT record and the UDP beacon JSON.
///
/// The wire format is the one the Go desktop publishes in
/// `internal/discovery/mdns.go` and `beacon.go` and the Android app reads in
/// `NsdDiscovery.kt`. Spec §5.1 fixes the six keys:
///
/// | Key   | Meaning |
/// | :---- | :------ |
/// | `v`   | Protocol version (`2`) |
/// | `id`  | First 16 hex chars of the **certificate fingerprint** (authoritative) |
/// | `did` | User-definable Device ID label, falling back to `id` when unset |
/// | `n`   | Device name |
/// | `os`  | `windows`, `darwin`, `linux` |
/// | `p`   | Peer Service port |
///
/// Everything here is pure and Foundation-only: no `NWBrowser`, no sockets.
public enum DiscoveryTxt {
    /// The only protocol version this build understands (`ProtocolVersion` in Go).
    public static let protocolVersion = "2"

    /// DNS-SD service type, without the trailing dot.
    public static let serviceType = "_lanyard._tcp"
    /// The DNS-SD local domain.
    public static let serviceDomain = "local."

    /// The short id is the first 16 hex characters of the certificate fingerprint.
    public static let shortIdLength = 16

    /// TXT values are clipped to these byte budgets by the advertiser
    /// (`clip` in `mdns.go`); parsing does not re-clip but exposes the limits.
    public static let maxDeviceLabelLength = 32
    public static let maxNameLength = 60

    public static let minPort = 1
    public static let maxPort = 65535

    /// Everything a single announcement carries, after fallbacks and validation.
    public struct Parsed: Equatable {
        public let version: String
        public let shortId: String
        public let deviceLabel: String
        public let name: String
        public let os: String
        public let port: Int

        public init(
            version: String,
            shortId: String,
            deviceLabel: String,
            name: String,
            os: String,
            port: Int
        ) {
            self.version = version
            self.shortId = shortId
            self.deviceLabel = deviceLabel
            self.name = name
            self.os = os
            self.port = port
        }

        /// True when the short id looks like a certificate-fingerprint prefix and
        /// the port is dialable. The Android and Go layers additionally reject
        /// `port <= 0` before recording a peer; this exposes the same rule.
        public var isUsable: Bool {
            DiscoveryTxt.isShortId(shortId) && port >= DiscoveryTxt.minPort && port <= DiscoveryTxt.maxPort
        }
    }

    // MARK: - Short id

    /// The short id a full certificate fingerprint advertises: its first 16
    /// characters (Go `identity.ShortID`, Kotlin `SelfFilter.ownShortId`).
    public static func shortId(_ fingerprint: String) -> String {
        String(fingerprint.trimmingCharacters(in: .whitespacesAndNewlines).prefix(shortIdLength))
    }

    /// A strict check: exactly 16 ASCII hex characters.
    public static func isShortId(_ value: String) -> Bool {
        value.count == shortIdLength && value.allSatisfy { $0.isASCII && $0.isHexDigit }
    }

    // MARK: - TXT parsing

    /// Parses decoded `key=value` TXT strings. Returns nil unless a short id is
    /// present and the protocol version matches, mirroring Go `parseTXT`'s
    /// `a.ShortID != "" && a.Version == ProtocolVersion`.
    ///
    /// `instanceName` is the DNS-SD instance name and `resolvedPort` the port the
    /// resolver reported; both are the fallbacks Android `NsdDiscovery.kt` uses
    /// when the TXT record omits `id`/`n`/`p` (a record's `id` is still required).
    public static func parse(
        _ records: [String],
        instanceName: String? = nil,
        resolvedPort: Int? = nil
    ) -> Parsed? {
        var dict: [String: String] = [:]
        for record in records {
            guard let eq = record.firstIndex(of: "=") else { continue }
            let key = String(record[record.startIndex..<eq])
            let value = String(record[record.index(after: eq)...])
            dict[key] = value
        }
        return parse(dictionary: dict, instanceName: instanceName, resolvedPort: resolvedPort)
    }

    /// The dictionary form of `parse(_:instanceName:resolvedPort:)`.
    public static func parse(
        dictionary: [String: String],
        instanceName: String? = nil,
        resolvedPort: Int? = nil
    ) -> Parsed? {
        let version = dictionary["v"] ?? ""
        // Go accepts a record only when the version matches the protocol.
        guard version == protocolVersion else { return nil }

        let shortId = firstNonEmpty(dictionary["id"], instanceName)
        guard let shortId, !shortId.isEmpty else { return nil }

        // §5.1: `did` falls back to `id` when unset.
        let deviceLabel = firstNonEmpty(dictionary["did"], shortId) ?? shortId
        let name = firstNonEmpty(dictionary["n"], instanceName) ?? ""
        let os = dictionary["os"] ?? ""

        // Android: `attrs["p"]?.toIntOrNull() ?: info.port`, i.e. the resolved
        // port is the fallback when `p` is missing or not a number.
        var port = 0
        if let raw = dictionary["p"], let parsed = Int(raw), parsed >= minPort, parsed <= maxPort {
            port = parsed
        } else if let resolvedPort, resolvedPort >= minPort, resolvedPort <= maxPort {
            port = resolvedPort
        }

        return Parsed(
            version: version,
            shortId: shortId,
            deviceLabel: deviceLabel,
            name: name,
            os: os,
            port: port
        )
    }

    /// Parses a raw DNS-SD TXT record: a sequence of length-prefixed strings
    /// (`\xNNkey=value`). Mirrors `BonjourBrowser.parseTXT` on the Apple side so
    /// the same bytes yield the same map on Linux.
    public static func parseRecord(_ data: Data) -> [String: String] {
        var out: [String: String] = [:]
        let bytes = [UInt8](data)
        var index = 0
        while index < bytes.count {
            let length = Int(bytes[index])
            index += 1
            guard index + length <= bytes.count else { break }
            let entry = String(decoding: bytes[index..<(index + length)], as: UTF8.self)
            index += length
            guard let separator = entry.firstIndex(of: "=") else { continue }
            let key = String(entry[entry.startIndex..<separator])
            let value = String(entry[entry.index(after: separator)...])
            out[key] = value
        }
        return out
    }

    /// Renders a parsed announcement back into TXT `key=value` strings, in the
    /// order Go emits them. Empty optional values are omitted, matching the
    /// Android advertiser's `if (v.isNotEmpty())`.
    public static func encode(_ parsed: Parsed) -> [String] {
        var records = ["v=\(parsed.version)", "id=\(parsed.shortId)"]
        if !parsed.deviceLabel.isEmpty { records.append("did=\(parsed.deviceLabel)") }
        if !parsed.name.isEmpty { records.append("n=\(parsed.name)") }
        if !parsed.os.isEmpty { records.append("os=\(parsed.os)") }
        records.append("p=\(parsed.port)")
        return records
    }

    private static func firstNonEmpty(_ values: String?...) -> String? {
        for value in values {
            if let value, !value.isEmpty { return value }
        }
        return nil
    }
}

/// The UDP broadcast beacon's JSON datagram: a small fallback for networks that
/// block mDNS (`beacon.go`). The payload is the same `Announcement` as the TXT
/// record plus a `t` discriminator (`"beacon"` | `"probe"`).
public struct BeaconMessage: Equatable {
    /// `"beacon"` or `"probe"`. Go only replies to `"probe"`.
    public let type: String
    /// The same fields as the TXT record.
    public let announcement: DiscoveryTxt.Parsed

    public init(type: String, announcement: DiscoveryTxt.Parsed) {
        self.type = type
        self.announcement = announcement
    }

    /// True for a probe datagram, which asks listeners to answer immediately.
    public var isProbe: Bool { type == "probe" }

    /// Parses one beacon datagram. Returns nil on invalid JSON, a protocol
    /// version mismatch (Go skips those), or a missing short id.
    public static func parse(_ data: Data) -> BeaconMessage? {
        guard
            let object = try? JSONSerialization.jsonObject(with: data),
            let dict = object as? [String: Any]
        else { return nil }

        let version = string(dict["v"])
        guard version == DiscoveryTxt.protocolVersion else { return nil }
        let shortId = string(dict["id"])
        guard !shortId.isEmpty else { return nil }

        let deviceLabel = firstNonEmpty(string(dict["did"]), shortId) ?? shortId
        let name = string(dict["n"])
        let os = string(dict["os"])
        let port = int(dict["p"])

        let announcement = DiscoveryTxt.Parsed(
            version: version,
            shortId: shortId,
            deviceLabel: deviceLabel,
            name: name,
            os: os,
            port: port
        )
        return BeaconMessage(type: string(dict["t"]), announcement: announcement)
    }

    /// The wire object, e.g. for tests to produce a datagram without hand-typing
    /// JSON. The Device ID label is omitted when empty, like Go's
    /// `json:"did,omitempty"`.
    public func jsonObject() -> [String: Any] {
        var out: [String: Any] = [
            "t": type,
            "v": announcement.version,
            "id": announcement.shortId,
            "n": announcement.name,
            "os": announcement.os,
            "p": announcement.port,
        ]
        if !announcement.deviceLabel.isEmpty { out["did"] = announcement.deviceLabel }
        return out
    }

    /// Serialises this message as the beacon datagram.
    public func encoded() throws -> Data {
        try JSONSerialization.data(withJSONObject: jsonObject())
    }

    private static func string(_ value: Any?) -> String {
        (value as? String) ?? ""
    }

    private static func int(_ value: Any?) -> Int {
        if let n = value as? Int { return n }
        if let n = value as? NSNumber { return n.intValue }
        if let s = value as? String, let n = Int(s) { return n }
        return 0
    }

    private static func firstNonEmpty(_ values: String...) -> String? {
        for value in values where !value.isEmpty { return value }
        return nil
    }
}
