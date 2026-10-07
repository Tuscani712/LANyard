import Foundation
import Crypto

/// Lowercase hex helpers shared across the core, matching the Go and Kotlin
/// implementations (both emit lowercase, no separators).
enum Hex {
    static func encode(_ bytes: some Sequence<UInt8>) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    static func decode(_ string: String) -> [UInt8]? {
        let chars = Array(string)
        guard chars.count % 2 == 0 else { return nil }
        var out = [UInt8]()
        out.reserveCapacity(chars.count / 2)
        var i = 0
        while i < chars.count {
            guard let hi = chars[i].hexDigitValue, let lo = chars[i + 1].hexDigitValue else { return nil }
            out.append(UInt8(hi << 4 | lo))
            i += 2
        }
        return out
    }

    static func isHex(_ string: String, min: Int, max: Int) -> Bool {
        guard string.count >= min, string.count <= max, string.count % 2 == 0 else { return false }
        return string.allSatisfy(\.isHexDigit)
    }
}
