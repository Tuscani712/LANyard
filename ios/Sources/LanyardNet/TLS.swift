// TLS — mutual TLS 1.3 for LANyard peers, with a certificate-fingerprint pin.
//
// Mirrors `android/core/.../Tls.kt`: TLS 1.3 only, our own Ed25519 certificate
// as the local identity, peer authentication required on both roles, and the
// presented leaf's Device ID (SPKI SHA-256) compared with the expected one in a
// verify block. The Go server uses the same rules (`internal/peerapi`).
#if canImport(Network) && canImport(Security)
import Foundation
import Network
import Security
import LanyardCore

/// Builds `NWProtocolTLS.Options` for the client and server roles.
enum TLS {
    /// Options for a client connecting to a peer whose certificate must hash to
    /// `expectedFingerprint`. A mismatch aborts the handshake before any request
    /// is written.
    static func clientOptions(identity: SecIdentity, expectedFingerprint: String) -> NWProtocolTLS.Options {
        options(identity: identity, allowedFingerprints: [expectedFingerprint.lowercased()], onPeerFingerprint: nil)
    }

    /// Options for the discovery probe: accept any peer certificate, but record
    /// the presented Device ID so the caller can compare it with the pairing
    /// link. Never guards a data request.
    static func probeOptions(identity: SecIdentity, onPeerFingerprint: @escaping (String) -> Void) -> NWProtocolTLS.Options {
        options(identity: identity, allowedFingerprints: nil, onPeerFingerprint: onPeerFingerprint)
    }

    /// Options for the listener. When `allowedFingerprints` is nil any client
    /// certificate is accepted at the TLS layer (authorization happens per
    /// request, like the Go server); when non-nil, only those fingerprints may
    /// complete the handshake.
    static func serverOptions(identity: SecIdentity, allowedFingerprints: Set<String>? = nil) -> NWProtocolTLS.Options {
        options(identity: identity, allowedFingerprints: allowedFingerprints, onPeerFingerprint: nil)
    }

    /// The common builder.
    static func options(
        identity: SecIdentity,
        allowedFingerprints: Set<String>?,
        onPeerFingerprint: ((String) -> Void)?
    ) -> NWProtocolTLS.Options {
        let tls = NWProtocolTLS.Options()
        let sec = tls.securityProtocolOptions

        // TLS 1.3 only, exactly like the Go peer.
        sec_protocol_options_set_min_tls_protocol_version(sec, .TLSv13)
        sec_protocol_options_set_max_tls_protocol_version(sec, .TLSv13)

        // Present our own Ed25519 certificate (client *and* server role).
        if let secIdentity = sec_identity_create(identity) {
            sec_protocol_options_set_local_identity(sec, secIdentity)
        }

        // Both ends must authenticate.
        sec_protocol_options_set_peer_authentication_required(sec, true)

        // The pin. Even when no fingerprint is enforced we still run the block so
        // the peer chain is read (and optionally reported).
        let expected = allowedFingerprints
        let queue = DispatchQueue(label: "io.github.tuscani712.lanyard.tls.verify")
        sec_protocol_options_set_verify_block(sec, { _, secTrust, complete in
            // MAC-SPIKE: `sec_trust_copy_ref` memory management (takeRetainedValue
            // vs. takeUnretainedValue) must be confirmed on the Mac; see SPIKE.md
            // step 3.
            let trust = sec_trust_copy_ref(secTrust).takeRetainedValue()
            guard let chain = SecTrustCopyCertificateChain(trust) as? [SecCertificate],
                  let leaf = chain.first else {
                complete(false)
                return
            }
            let fingerprint = fingerprint(of: leaf)
            if let onPeerFingerprint, let fingerprint {
                onPeerFingerprint(fingerprint)
            }
            if let expected {
                complete(fingerprint.map { expected.contains($0.lowercased()) } ?? false)
            } else {
                complete(true)
            }
        }, queue)

        return tls
    }

    /// `NWParameters` carrying the TLS options and a TCP transport.
    static func parameters(tls: NWProtocolTLS.Options, keepAlive: Bool = true) -> NWParameters {
        let parameters = NWParameters(tls: tls, tcp: NWProtocolTCP.Options())
        if keepAlive {
            let tcp = parameters.defaultProtocolStack.transportProtocol as? NWProtocolTCP.Options
            tcp?.enableKeepalive = true
        }
        return parameters
    }

    /// The Device ID of a certificate: SHA-256 of its SubjectPublicKeyInfo.
    ///
    /// Security.framework hands back the raw Ed25519 public key, so we rebuild
    /// the SPKI with `Fingerprint` (the same hash Go and Kotlin compute). See
    /// `Fingerprint.swift` for the fixed prefix.
    static func fingerprint(of certificate: SecCertificate) -> String? {
        guard let key = SecCertificateCopyKey(certificate) else { return nil }
        guard let raw = rawPublicKey(key) else { return nil }
        return Fingerprint.ofEd25519PublicKey(raw)
    }

    /// Normalizes `SecKeyCopyExternalRepresentation`: 32 raw bytes or the 44-byte
    /// SPKI prefix + raw key. MAC-SPIKE: confirm which form the OS returns for
    /// Ed25519 (SPIKE.md step 4).
    static func rawPublicKey(_ key: SecKey) -> [UInt8]? {
        var error: Unmanaged<CFError>?
        guard let data = SecKeyCopyExternalRepresentation(key, &error) as Data? else { return nil }
        let bytes = [UInt8](data)
        if bytes.count == 32 { return bytes }
        let prefix = Fingerprint.ed25519SPKIPrefix
        if bytes.count == prefix.count + 32, Array(bytes.prefix(prefix.count)) == prefix {
            return Array(bytes.suffix(32))
        }
        return nil
    }
}
#endif
