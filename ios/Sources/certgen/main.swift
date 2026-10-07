import Foundation
import Crypto
import LanyardCore

// certgen <seedHex> [name] [serial]
//
// Prints the lowercase hex of a self-signed Ed25519 certificate DER to stdout.
// Nothing else is written to stdout, so the output can be piped straight into
// the Go verifier in crosscheck.sh. Given the same inputs (and clock) the
// output is deterministic.

func fail(_ message: String) -> Never {
    FileHandle.standardError.write(Data(("certgen: " + message + "\n").utf8))
    exit(1)
}

func decodeHex(_ string: String) -> [UInt8]? {
    let chars = Array(string)
    guard chars.count % 2 == 0 else { return nil }
    var out = [UInt8]()
    out.reserveCapacity(chars.count / 2)
    var index = 0
    while index < chars.count {
        guard let hi = chars[index].hexDigitValue, let lo = chars[index + 1].hexDigitValue else { return nil }
        out.append(UInt8(hi << 4 | lo))
        index += 2
    }
    return out
}

func encodeHex(_ bytes: [UInt8]) -> String {
    bytes.map { String(format: "%02x", $0) }.joined()
}

let arguments = CommandLine.arguments
guard arguments.count >= 2 else {
    fail("usage: certgen <seedHex> [name] [serial]")
}

guard let seed = decodeHex(arguments[1]), seed.count == 32 else {
    fail("seed must be 32 bytes of hex (64 chars)")
}

let deviceName = arguments.count >= 3 ? arguments[2] : "device"

let privateKey: Curve25519.Signing.PrivateKey
do {
    privateKey = try Curve25519.Signing.PrivateKey(rawRepresentation: seed)
} catch {
    fail("invalid seed: \(error)")
}

// The serial defaults to a deterministic value derived from the key itself, so
// that a bare `certgen <seed>` is reproducible.
let serial: [UInt8]
if arguments.count >= 4 {
    guard let parsed = UInt64(arguments[3]) else {
        fail("serial must be an unsigned decimal integer")
    }
    var magnitude = withUnsafeBytes(of: parsed.bigEndian) { Array($0) }
    while magnitude.count > 1 && magnitude.first == 0 {
        magnitude.removeFirst()
    }
    serial = magnitude
} else {
    let rawPublicKey = Array(privateKey.publicKey.rawRepresentation)
    let digest = Array(SHA256.hash(data: Data(rawPublicKey)))
    serial = Array(digest.prefix(16))
}

let now = Date()
let notBefore = now.addingTimeInterval(-3600)
let notAfter = Calendar(identifier: .gregorian).date(byAdding: .year, value: 10, to: now) ?? now

let der: [UInt8]
do {
    der = try CertificateBuilder.build(
        deviceName: deviceName,
        privateKey: privateKey,
        serial: serial,
        notBefore: notBefore,
        notAfter: notAfter
    )
} catch {
    fail("could not build certificate: \(error)")
}

print(encodeHex(der))
