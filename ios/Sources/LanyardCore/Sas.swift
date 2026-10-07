import Foundation
import Crypto

/// The 6-digit Short Authentication String both devices show so a person can
/// confirm the pairing. Mirrors `identity.SAS` (Go) and `Sas.code` (Kotlin):
///
///  1. order the two fingerprints lexicographically (and their nonces with them),
///  2. SHA-256 over `fpA || 0 || fpB || 0 || nonceA || 0 || nonceB`,
///  3. the first four bytes, big-endian, modulo 1e6, zero-padded to six digits.
///
/// Both sides may pass their own fingerprint/nonce first; the result is the same.
enum Sas {
    static func code(fpA: String, fpB: String, nonceA: String, nonceB: String) -> String {
        var a = fpA, b = fpB, na = nonceA, nb = nonceB
        if a > b {
            (a, b) = (b, a)
            (na, nb) = (nb, na)
        }
        var hasher = SHA256()
        hasher.update(data: Data(a.utf8))
        hasher.update(data: Data([0]))
        hasher.update(data: Data(b.utf8))
        hasher.update(data: Data([0]))
        hasher.update(data: Data(na.utf8))
        hasher.update(data: Data([0]))
        hasher.update(data: Data(nb.utf8))
        let sum = Array(hasher.finalize())
        let n = (UInt32(sum[0]) << 24) | (UInt32(sum[1]) << 16) | (UInt32(sum[2]) << 8) | UInt32(sum[3])
        return String(format: "%06d", n % 1_000_000)
    }
}
