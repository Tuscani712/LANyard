import Foundation

/// The pairing link carried by a QR code: `lanyard://pair?fp=&name=&addr=&n=`.
/// Mirrors `internal/pairlink/pairlink.go` and the Kotlin `PairLink`: length and
/// address caps, only the known parameters, hex checks on the fingerprint and
/// nonce, and IP-literal host:port addresses. `build` leaves ':' and ',' unescaped
/// in `addr` (both are legal in a query value and keep the link short); `parse`
/// accepts both that form and a percent-encoded one.
enum PairLink {
    static let SCHEME = "lanyard"
    static let HOST = "pair"
    static let MAX_LEN = 2048
    static let MAX_ADDRS = 16

    private static let MAX_ADDR_LEN = 255
    private static let MAX_NAME_LEN = 200
    private static let MIN_NONCE_LEN = 32 // 128 bits, hex-encoded
    private static let MAX_NONCE_LEN = 128
    private static let KEYS: Set<String> = ["fp", "name", "addr", "n"]

    /// The Kotlin `ParseException`, kept as a distinct Swift error type.
    enum ParseError: Error, Equatable {
        case malformed(String)
    }

    struct Payload: Equatable {
        let fingerprint: String
        let name: String
        let addrs: [String]
        let nonce: String
    }

    /// Renders `p` as a pairing link. The fingerprint and nonce are hex, so only
    /// the name is escaped. The addr value is left unescaped — ':' and ',' are
    /// legal in a query value; `parse` accepts both this form and an encoded one.
    static func build(_ p: Payload) -> String {
        var out = "\(SCHEME)://\(HOST)?fp=\(p.fingerprint)"
        if !p.name.isEmpty {
            out += "&name=" + formEncode(p.name)
        }
        out += "&addr=" + p.addrs.joined(separator: ",")
        out += "&n=\(p.nonce)"
        return out
    }

    /// Decodes a pairing link. It rejects anything malformed, any unexpected or
    /// duplicated parameter, oversized input, and too many addresses.
    static func parse(_ raw: String) throws -> Payload {
        guard !raw.isEmpty, raw.count <= MAX_LEN else {
            throw ParseError.malformed("malformed pairing link")
        }
        let prefix = "\(SCHEME)://\(HOST)?"
        guard raw.hasPrefix(prefix) else {
            throw ParseError.malformed("not a LANyard pairing link")
        }

        var params: [String: [String]] = [:]
        let body = String(raw.dropFirst(prefix.count))
        for part in body.split(separator: "&", omittingEmptySubsequences: false) {
            if part.isEmpty { continue }
            guard let eq = part.firstIndex(of: "=") else {
                throw ParseError.malformed("malformed pairing link")
            }
            let key = String(part[part.startIndex..<eq])
            let value = try formDecode(String(part[part.index(after: eq)...]))
            params[key, default: []].append(value)
        }
        for key in params.keys where !KEYS.contains(key) {
            throw ParseError.malformed("unexpected parameter \(key)")
        }
        for key in ["fp", "addr", "n"] {
            guard params[key]?.count == 1 else {
                throw ParseError.malformed("missing or duplicate \(key)")
            }
        }
        if (params["name"]?.count ?? 0) > 1 {
            throw ParseError.malformed("duplicate name")
        }

        let fingerprint = params["fp"]![0]
        guard Hex.isHex(fingerprint, min: 64, max: 64) else {
            throw ParseError.malformed("bad fingerprint")
        }
        let nonce = params["n"]![0]
        guard Hex.isHex(nonce, min: MIN_NONCE_LEN, max: MAX_NONCE_LEN) else {
            throw ParseError.malformed("bad nonce")
        }

        var name = params["name"]?.first ?? ""
        if name.count > MAX_NAME_LEN {
            name = String(name.prefix(MAX_NAME_LEN))
        }

        var addrs: [String] = []
        for candidate in params["addr"]![0].split(separator: ",", omittingEmptySubsequences: false) {
            let addr = candidate.trimmingCharacters(in: .whitespacesAndNewlines)
            if addr.isEmpty { continue }
            guard validAddr(addr) else { throw ParseError.malformed("bad address") }
            if addrs.count >= MAX_ADDRS { throw ParseError.malformed("too many addresses") }
            addrs.append(addr)
        }
        guard !addrs.isEmpty else { throw ParseError.malformed("no addresses") }

        return Payload(fingerprint: fingerprint, name: name, addrs: addrs, nonce: nonce)
    }

    // MARK: - Address validation

    private static func validAddr(_ addr: String) -> Bool {
        if addr.count > MAX_ADDR_LEN { return false }
        let host: String
        let portText: String
        if addr.hasPrefix("[") {
            guard let end = addr.firstIndex(of: "]") else { return false }
            host = String(addr[addr.index(after: addr.startIndex)..<end])
            let afterEnd = addr.index(after: end)
            guard afterEnd < addr.endIndex, addr[afterEnd] == ":" else { return false }
            portText = String(addr[addr.index(after: afterEnd)...])
            if !isIpv6(host) { return false }
        } else {
            guard let colon = addr.lastIndex(of: ":"), colon > addr.startIndex else { return false }
            host = String(addr[addr.startIndex..<colon])
            portText = String(addr[addr.index(after: colon)...])
            if !isIpv4(host) { return false }
        }
        guard let port = Int(portText) else { return false }
        return port >= 1 && port <= 65535
    }

    private static func isIpv4(_ host: String) -> Bool {
        let parts = host.split(separator: ".", omittingEmptySubsequences: false)
        if parts.count != 4 { return false }
        return parts.allSatisfy { part in
            !part.isEmpty && part.count <= 3 && part.allSatisfy { $0.isASCII && $0.isNumber } &&
                (Int(part).map { $0 >= 0 && $0 <= 255 } ?? false)
        }
    }

    private static func isIpv6(_ host: String) -> Bool {
        if !host.contains(":") { return false }
        return host.allSatisfy { c in
            let isHexLower = c >= "a" && c <= "f"
            let isHexUpper = c >= "A" && c <= "F"
            return (c.isASCII && c.isNumber) || isHexLower || isHexUpper || c == ":" || c == "."
        }
    }

    // MARK: - application/x-www-form-urlencoded

    private static func hexValue(_ b: UInt8) -> UInt8? {
        switch b {
        case 0x30...0x39: return b - 0x30
        case 0x61...0x66: return b - 0x61 + 10
        case 0x41...0x46: return b - 0x41 + 10
        default: return nil
        }
    }

    /// Form-encodes `s` the way Go's `url.QueryEscape` (and Java's
    /// `URLEncoder`) does: unreserved `A-Za-z0-9-_.~` pass through, space becomes
    /// `+`, everything else is `%XX` (uppercase hex).
    private static func formEncode(_ s: String) -> String {
        var out = ""
        for b in s.utf8 {
            switch b {
            case 0x41...0x5A, 0x61...0x7A, 0x30...0x39, 0x2D, 0x5F, 0x2E, 0x7E:
                out.append(Character(UnicodeScalar(b)))
            case 0x20:
                out.append("+")
            default:
                out += String(format: "%%%02X", b)
            }
        }
        return out
    }

    /// Form-decodes a query value: `+` becomes space and `%XX` a byte. The byte
    /// stream is then decoded as UTF-8; a bad escape or bad UTF-8 is malformed.
    private static func formDecode(_ s: String) throws -> String {
        let utf8 = Array(s.utf8)
        var bytes: [UInt8] = []
        bytes.reserveCapacity(utf8.count)
        var i = 0
        while i < utf8.count {
            let c = utf8[i]
            if c == 0x2B {
                bytes.append(0x20)
                i += 1
            } else if c == 0x25 {
                guard i + 2 < utf8.count,
                      let hi = hexValue(utf8[i + 1]),
                      let lo = hexValue(utf8[i + 2]) else {
                    throw ParseError.malformed("malformed pairing link")
                }
                bytes.append(hi << 4 | lo)
                i += 3
            } else {
                bytes.append(c)
                i += 1
            }
        }
        guard let decoded = String(bytes: bytes, encoding: .utf8) else {
            throw ParseError.malformed("malformed pairing link")
        }
        return decoded
    }
}
