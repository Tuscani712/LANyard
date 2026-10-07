import Foundation
import Crypto

/// The Device ID: the lowercase hex SHA-256 of a certificate's
/// SubjectPublicKeyInfo, exactly as `identity.FingerprintOf` (Go) and
/// `Identity.fingerprintOf` (Kotlin) compute it.
///
/// Apple's Security framework exposes the *raw* public key rather than the
/// SPKI DER, so for Ed25519 we rebuild the SPKI from its fixed encoding:
/// `SEQUENCE { SEQUENCE { OID 1.3.101.112 }, BIT STRING <32 bytes> }`.
enum Fingerprint {
    /// DER prefix of an Ed25519 SubjectPublicKeyInfo: 12 bytes, then the raw key.
    static let ed25519SPKIPrefix: [UInt8] = [
        0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00,
    ]

    /// SHA-256 of an already-encoded SPKI, lowercase hex (64 chars).
    static func ofSPKI(_ spki: some DataProtocol) -> String {
        Hex.encode(SHA256.hash(data: Data(spki)))
    }

    /// SHA-256 of the SPKI rebuilt from a raw 32-byte Ed25519 public key.
    static func ofEd25519PublicKey(_ raw: some DataProtocol) -> String {
        ofSPKI(Data(ed25519SPKIPrefix) + Data(raw))
    }
}
