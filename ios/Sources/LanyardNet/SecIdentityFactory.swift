// SecIdentityFactory — creates (or loads) the device's Ed25519 identity as a
// `SecIdentity` usable by Network.framework's TLS options.
#if canImport(Network) && canImport(Security)
import Foundation
import Security
import LanyardCore

/// Generates the long-term Ed25519 key, builds the self-signed certificate with
/// `LanyardCore.CertificateBuilder`, imports both into the Keychain, and returns
/// the resulting `SecIdentity`.
///
/// **No public `SecIdentityCreate`.** Security.framework offers no API to
/// construct a `SecIdentity` from a `SecKey` + `SecCertificate` directly. The
/// supported route (used here) is: add the private key as `kSecClassKey` and the
/// certificate as `kSecClassCertificate` under the same application tag, then
/// ask the Keychain for the matching `kSecClassIdentity`. That is why
/// `SecIdentityFactory` depends on `KeychainIdentityStore`.
enum SecIdentityFactory {
    struct FactoryError: Error, CustomStringConvertible {
        enum Kind {
            case keyGenerationFailed(CFError?)
            case publicKeyUnavailable
            case publicKeyEncoding(String)
            case signingFailed(CFError?)
            case identityUnavailable
        }
        let kind: Kind

        var description: String {
            switch kind {
            case .keyGenerationFailed(let e): return "Ed25519 key generation failed: \(describe(e))"
            case .publicKeyUnavailable: return "could not read the public half of the generated key"
            case .publicKeyEncoding(let detail): return "unexpected Ed25519 public key encoding: \(detail)"
            case .signingFailed(let e): return "Ed25519 signing failed: \(describe(e))"
            case .identityUnavailable: return "identity was stored but could not be read back"
            }
        }

        private func describe(_ error: CFError?) -> String {
            guard let error else { return "unknown error" }
            return CFErrorCopyDescription(error) as String? ?? "unknown error"
        }
    }

    /// The loaded/created material. `deviceId` is the lowercase hex SHA-256 of
    /// the certificate's SubjectPublicKeyInfo (the Device ID).
    struct Material {
        let identity: SecIdentity
        let certificate: SecCertificate
        let deviceId: String
    }

    /// Loads the Keychain identity if present, otherwise generates, stores and
    /// returns one.
    static func loadOrCreate(deviceName: String) throws -> Material {
        if let existing = try loadStored() { return existing }
        return try create(deviceName: deviceName)
    }

    /// Loads and validates a previously stored identity, or nil.
    static func loadStored() throws -> Material? {
        guard let identity = try KeychainIdentityStore.loadIdentity() else { return nil }
        var certificate: SecCertificate?
        SecIdentityCopyCertificate(identity, &certificate)
        guard let certificate else { return nil }
        guard let deviceId = deviceId(of: certificate) else { return nil }
        return Material(identity: identity, certificate: certificate, deviceId: deviceId)
    }

    /// Generates a fresh Ed25519 identity and stores it. Replaces any existing
    /// entry with the same tag.
    static func create(deviceName: String) throws -> Material {
        let privateKey = try generateKey()
        guard let publicKey = SecKeyCopyPublicKey(privateKey) else {
            throw FactoryError(kind: .publicKeyUnavailable)
        }
        let rawPublic = try rawPublicKey(publicKey)

        var serial = [UInt8](repeating: 0, count: 16)
        guard SecRandomCopyBytes(kSecRandomDefault, serial.count, &serial) == errSecSuccess else {
            throw FactoryError(kind: .keyGenerationFailed(nil))
        }

        let now = Date()
        let der = try CertificateBuilder.selfSignedCertificate(
            publicKeyRaw: rawPublic,
            subject: deviceName,
            serialNumber: serial,
            notBefore: now.addingTimeInterval(-3600),
            notAfter: now.addingTimeInterval(10 * 365 * 24 * 3600),
            sign: { try signature(privateKey, $0) }
        )

        guard let certificate = SecCertificateCreateWithData(nil, Data(der) as CFData) else {
            throw FactoryError(kind: .identityUnavailable)
        }

        try KeychainIdentityStore.store(privateKey: privateKey, certificate: certificate)
        try KeychainIdentityStore.deleteOrphanIdentityCache()
        guard let identity = try KeychainIdentityStore.loadIdentity() else {
            throw FactoryError(kind: .identityUnavailable)
        }
        let id = deviceId(of: certificate) ?? Fingerprint.ofSPKI(CertificateBuilder.subjectPublicKeyInfo(publicKeyRaw: rawPublic))
        return Material(identity: identity, certificate: certificate, deviceId: id)
    }

    /// Lowercase hex SHA-256 of the certificate's SPKI, matching Go/Kotlin.
    static func deviceId(of certificate: SecCertificate) -> String? {
        guard let key = SecCertificateCopyKey(certificate) else { return nil }
        guard let raw = try? rawPublicKey(key) else { return nil }
        return Fingerprint.ofEd25519PublicKey(raw)
    }

    // MARK: - Key handling

    private static func generateKey() throws -> SecKey {
        // Ed25519 is a fixed-size curve: do NOT pass kSecAttrKeySizeInBits.
        // Software token (no kSecAttrTokenID / Secure Enclave) because the
        // Secure Enclave does not support Ed25519.
        let attributes: [String: Any] = [
            kSecAttrKeyType as String: kSecAttrKeyTypeEd25519,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
        ]
        var error: Unmanaged<CFError>?
        guard let key = SecKeyCreateRandomKey(attributes as CFDictionary, &error) else {
            throw FactoryError(kind: .keyGenerationFailed(error?.takeRetainedValue()))
        }
        return key
    }

    /// The raw 32-byte Ed25519 public key.
    ///
    /// `SecKeyCopyExternalRepresentation` for Ed25519 returns either the bare
    /// 32-byte key or the 44-byte SPKI (28-byte prefix + raw key), depending on
    /// the OS. Accept both; reject anything else.
    private static func rawPublicKey(_ key: SecKey) throws -> [UInt8] {
        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else {
            throw FactoryError(kind: .publicKeyEncoding(
                (error?.takeRetainedValue()).map { CFErrorCopyDescription($0) as String? ?? "" } ?? "no representation"
            ))
        }
        let bytes = [UInt8](data)
        if bytes.count == 32 { return bytes }
        let prefix = Fingerprint.ed25519SPKIPrefix
        if bytes.count == prefix.count + 32, Array(bytes.prefix(prefix.count)) == prefix {
            return Array(bytes.suffix(32))
        }
        throw FactoryError(kind: .publicKeyEncoding("\(bytes.count) bytes"))
    }

    private static func signature(_ key: SecKey, _ message: Data) throws -> Data {
        var error: Unmanaged<CFError>?
        // MAC-SPIKE: confirm the Swift spelling of the Ed25519 algorithm; on some
        // SDKs it is `.eddsaSignature` (kSecKeyAlgorithmEdDSASignature), on
        // others `.ed25519Signature`. See SPIKE.md step 2.
        guard let signature = SecKeyCreateSignature(
            key,
            SecKeyAlgorithm.eddsaSignature,
            message as CFData,
            &error
        ) as Data? else {
            throw FactoryError(kind: .signingFailed(error?.takeRetainedValue()))
        }
        return signature
    }
}

private extension KeychainIdentityStore {
    /// Placeholder hook: if the Keychain caches a stale identity across a
    /// regeneration we may need to nudge it. Kept as a named call site so the
    /// MAC spike has one place to add it.
    static func deleteOrphanIdentityCache() throws {}
}
#endif
