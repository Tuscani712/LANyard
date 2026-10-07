// KeychainIdentityStore — persists the device's long-term Ed25519 identity.
//
// Apple-only: Network.framework is not strictly needed here, but the whole
// LanyardNet target is compiled only on Apple platforms, so this file carries
// the same guard as its siblings to compile to nothing on Linux.
#if canImport(Network) && canImport(Security)
import Foundation
import Security
import LanyardCore

/// Stores the device's Ed25519 private key and its self-signed certificate in
/// the Keychain, keyed by a stable application tag, and reads them back.
///
/// What is stored (and why):
///  - `kSecClassKey`      — the Ed25519 private key. `kSecAttrAccessibleAfterFirstUnlock`
///    keeps it usable for background pushes while the phone is locked, which
///    LANyard needs (files arrive without the person unlocking).
///  - `kSecClassCertificate` — the DER of the self-signed certificate, whose
///    `SubjectPublicKeyInfo` is the Device ID.
///
/// The two entries share the same tag so `SecIdentity` matching pairs them.
/// **Never** Secure Enclave: it has no Ed25519 support (P-256 only).
enum KeychainIdentityStore {
    /// Stable tag; changing it orphans an existing pairing identity.
    static let applicationTag = "io.github.tuscani712.lanyard.identity"
    private static let label = "LANyard Device Identity"

    /// Error wrapper so callers get the OSStatus.
    struct KeychainError: Error, CustomStringConvertible {
        let status: OSStatus
        let operation: String

        var description: String {
            let message = SecCopyErrorMessageString(status, nil) as String? ?? "unknown"
            return "keychain \(operation) failed (\(status)): \(message)"
        }
    }

    /// True when the identity may not be present yet.
    static func hasIdentity() -> Bool {
        (try? loadIdentity()) != nil
    }

    /// Stores the private key and certificate. Replaces any previous entries
    /// with the same tag, so calling twice is safe.
    static func store(privateKey: SecKey, certificate: SecCertificate) throws {
        try? delete()
        try addKey(privateKey)
        try addCertificate(certificate)
    }

    /// Loads the key and certificate, or nil when no identity is stored.
    static func load() throws -> (key: SecKey, certificate: SecCertificate)? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrApplicationTag as String: applicationTag,
            kSecAttrKeyType as String: kSecAttrKeyTypeEd25519,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] + dataProtectionKeychain

        var item: CFTypeRef?
        let keyStatus = SecItemCopyMatching(query as CFDictionary, &item)
        if keyStatus == errSecItemNotFound { return nil }
        guard keyStatus == errSecSuccess, let key = item as? SecKey else {
            throw KeychainError(status: keyStatus, operation: "load key")
        }

        let certQuery: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrApplicationTag as String: applicationTag,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] + dataProtectionKeychain
        var certItem: CFTypeRef?
        let certStatus = SecItemCopyMatching(certQuery as CFDictionary, &certItem)
        guard certStatus == errSecSuccess, let certificate = certItem as? SecCertificate else {
            throw KeychainError(status: certStatus, operation: "load certificate")
        }
        return (key, certificate)
    }

    /// The `SecIdentity` the TLS options want, fetched by tag.
    ///
    /// There is no `SecIdentityCreate(key:certificate:)`; the only supported way
    /// to obtain one is to put both items in a Keychain and ask for
    /// `kSecClassIdentity`, which is exactly what this does.
    static func loadIdentity() throws -> SecIdentity? {
        // MAC-SPIKE: confirm the Keychain pairs a software-token Ed25519 key with
        // its certificate for a kSecClassIdentity match, and that
        // kSecAttrApplicationTag is accepted on a kSecClassCertificate query (it
        // is documented for keys). See SPIKE.md step 2.
        let query: [String: Any] = [
            kSecClass as String: kSecClassIdentity,
            kSecAttrApplicationTag as String: applicationTag,
            kSecReturnRef as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ] + dataProtectionKeychain
        var item: CFTypeRef?
        let status = SecItemCopyMatching(query as CFDictionary, &item)
        if status == errSecItemNotFound { return nil }
        guard status == errSecSuccess, let identity = item as? SecIdentity else {
            throw KeychainError(status: status, operation: "load identity")
        }
        return identity
    }

    /// The certificate DER, for advertising the fingerprint / Device ID.
    static func loadCertificateDER() throws -> Data? {
        guard let loaded = try load() else { return nil }
        return SecCertificateCopyData(loaded.certificate) as Data
    }

    /// Removes both entries (used when regenerating an identity).
    static func delete() throws {
        for cls in [kSecClassKey, kSecClassCertificate, kSecClassIdentity] {
            let query: [String: Any] = [
                kSecClass as String: cls,
                kSecAttrApplicationTag as String: applicationTag,
            ] + dataProtectionKeychain
            let status = SecItemDelete(query as CFDictionary)
            if status != errSecSuccess && status != errSecItemNotFound {
                throw KeychainError(status: status, operation: "delete")
            }
        }
    }

    // MARK: - Private

    private static func addKey(_ key: SecKey) throws {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassKey,
            kSecAttrKeyType as String: kSecAttrKeyTypeEd25519,
            kSecAttrKeyClass as String: kSecAttrKeyClassPrivate,
            kSecAttrApplicationTag as String: applicationTag,
            kSecAttrLabel as String: label,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueRef as String: key,
        ] + dataProtectionKeychain
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError(status: status, operation: "add key")
        }
    }

    private static func addCertificate(_ certificate: SecCertificate) throws {
        let attributes: [String: Any] = [
            kSecClass as String: kSecClassCertificate,
            kSecAttrApplicationTag as String: applicationTag,
            kSecAttrLabel as String: label,
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
            kSecValueRef as String: certificate,
        ] + dataProtectionKeychain
        let status = SecItemAdd(attributes as CFDictionary, nil)
        guard status == errSecSuccess else {
            throw KeychainError(status: status, operation: "add certificate")
        }
    }

    /// On macOS the legacy file keychain would be used unless we opt into the
    /// iOS-style data-protection keychain; on iOS this is already the default.
    private static var dataProtectionKeychain: [String: Any] {
        #if os(macOS)
        return [kSecUseDataProtectionKeychain as String: true]
        #else
        return [:]
        #endif
    }
}
#endif
