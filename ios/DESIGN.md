# LANyard for iOS — design

Status: approved by the project lead. This document is the design of record for
the Apple platform work. It was written, not compiled: this repository's CI box
has no macOS, Xcode or Swift-for-Apple SDK, so nothing here is compiled or run
for iOS. The protocol core (`LanyardCore`) *is* compiled and tested on Linux.

## Goal

An iPhone app that carries every current phone feature, so that once a Mac or
iPhone is available the work is testing and fixing, not writing. The protocol is
the one described in `p2p_file_transfer_specification_v2.md`; the Go desktop and
the Kotlin Android app are the reference implementations.

## 1. UI and project layout

- **SwiftUI**, minimum **iOS 16.0** (`NavigationStack`, async/await and
  `NWBrowser`-with-Bonjour are all available). The Android UI is Compose; the
  tab/flow structure maps across directly.
- Repository layout, all under `ios/`:
  - `Package.swift` — SwiftPM: **`LanyardCore`** (pure protocol logic, no Apple
    frameworks) plus its tests. This is the counterpart of the Android `:core`
    library and is what builds and tests on Linux.
  - `LanyardNet/` — the Apple-only shim (Phase 2): Network.framework,
    Security.framework and Keychain. Counterpart of `:app`'s `net/` and
    `SafInboxDestination`.
  - `LanyardApp/` — the SwiftUI app target (Devices/Transfers/Settings/
    Troubleshoot).
  - `LanyardShareExtension/` — the counterpart of `ShareReceiverActivity` /
    `SharePickerScreen`.
  - `project.yml` — **XcodeGen** spec (Phase 2). On a Mac:
    `brew install xcodegen && xcodegen generate && open Lanyard.xcodeproj`.
    The app and extension targets depend on the local SwiftPM package, so this
    one command regenerates the project.

## 2. Crypto and TLS

The protocol is TLS 1.3, mutual TLS, **no CA**, Ed25519 self-signed
certificates, and a trust decision that is a SHA-256 pin over the peer
certificate's **SubjectPublicKeyInfo**. That value *is* the Device ID and the
discovery `id` key.

Apple can express all of it:

- Transport: `NWListener` (server) and `NWConnection` (client) with
  `NWProtocolTLS.Options`; `sec_protocol_options_set_min_tls_protocol_version(opts, .TLSv13)`.
- Our certificate: `sec_protocol_options_set_local_identity(opts, secIdentity)`.
- Require the peer's: `sec_protocol_options_set_peer_authentication_required(opts, true)`.
- Pinning instead of CA trust: `sec_protocol_options_set_verify_block(...)` →
  `sec_trust_copy_ref` → `SecTrustCopyCertificateChain`, hash the leaf, compare,
  `complete(true/false)`. This replaces CA validation on both client and server.

### Where trouble is expected (ranked)

1. **Ed25519 client-certificate presentation.** On Android,
   Conscrypt/BoringSSL rejected an Ed25519 client cert
   (`SSLv3_ALERT_HANDSHAKE_FAILURE`); only BouncyCastle worked. Apple's TLS is
   BoringSSL-derived, so `NWProtocolTLS` may be unable to offer an Ed25519
   client cert to the Go server. **This is the first Mac spike.** Fallback (P-256
   for TLS only, fingerprint still SPKI SHA-256) requires the project lead's
   sign-off and a spec note; it is **not** implemented.
2. **Building the self-signed certificate.** Security.framework has no X.509
   builder; we build the RFC 5280 DER ourselves (`apple/swift-asn1`) and sign the
   TBS with Ed25519. This is the BouncyCastle role.
3. **Creating a `SecIdentity`.** No public `SecIdentityCreate`. Generate the key
   with `SecKeyCreateRandomKey(kSecAttrKeyTypeEd25519)`, add key and cert to the
   **Keychain**, then fetch a `SecIdentity` via `SecItemCopyMatching`. Whether
   Security accepts a raw Ed25519 `SecKey`, and whether
   `sec_protocol_options_set_local_identity` accepts an Ed25519 identity, is to
   be confirmed on a Mac.
4. **Fingerprint parity.** Apple exposes the raw public key, not the SPKI. For
   Ed25519 the SPKI is the fixed 12-byte prefix `302a300506032b6570032100` plus
   the 32-byte key, so the hash reproduces exactly (see `Fingerprint.swift`,
   proven against Go fixtures).
5. **Verify-block mechanics.** Whether the server-side verify block reliably
   yields the client chain under `NWListener`, and the ARC lifetime of
   `sec_identity_t`/`SecTrust` inside the block.

The private key lives in the Keychain, not a file. Secure Enclave is not used
(it does not do Ed25519).

## 3. What is checked on Linux

A Swift toolchain extracts under `~/swift/` with no root (swift.org Ubuntu 24.04
x86_64 tarball), and SwiftPM + XCTest + `apple/swift-crypto` run there. That
covers `LanyardCore` and most of the ported Kotlin tests. It does **not** cover
Network.framework, Security.framework, Keychain, `NWBrowser`, Bonjour/multicast,
the X.509 builder, the share extension, or SwiftUI.

Roughly 60–70% of the source and the bulk of the tests are verifiable on Linux;
the rest is behind `LanyardNet` and is written, not compiled, until a Mac exists.

## 4. Feature list to port (with iOS behaviour changes)

- **Discovery:** `NWBrowser` for `_lanyard._tcp` Bonjour (primary) + UDP beacon
  (may need BSD sockets for `IP_ADD_MEMBERSHIP`). **Local Network permission**
  (TN3179) prompts on first mDNS/LAN touch.
- **Pairing:** QR scan (`AVFoundation`) and invite-link paste, SAS confirm,
  permission screen, trust store.
- **Push receive:** peer server (`NWListener`), offer/approval, `.lanpart`
  spool, destination. **No long-running background server** on iOS; the listener
  lives only while foreground/within a background-task window.
- **Push send**, **share and serve**, **Transfers screen**, **Settings**,
  **Troubleshoot** with the server event log (ported redaction included),
  **Share extension**.
- **Sandbox/Files:** destination is the app's Documents dir (visible in the
  Files app via `UIFileSharingEnabled` + `LSSupportsOpeningDocumentsInPlace`),
  with a user-picked folder via `UIDocumentPicker` + a security-scoped bookmark
  as the override. Background downloads may use `URLSession`; background serving
  cannot.

## 5. Phased plan

- **Phase 1 — `LanyardCore` + parity tests.** Pure Swift protocol core and ported
  Kotlin tests. Runnable on Linux. *(this phase)*
- **Phase 2 — `LanyardNet` identity + TLS spike (Mac, written-not-compiled).**
  Ed25519 self-signed cert builder, Keychain `SecIdentity`, `NWConnection`/
  `NWListener` mTLS, verify-block pinning, the fingerprint-parity test, and the
  Ed25519-client-cert handshake spike against Go. Go/no-go on the P-256 fallback.
- **Phase 3 — push receive.** Listener, offer/approval, spool, Documents
  destination + Files-app visibility.
- **Phase 4 — discovery + pairing.** `NWBrowser` Bonjour + beacon, QR/link, SAS,
  trust store, Local Network permission.
- **Phase 5 — push send + share/serve + Transfers screen.**
- **Phase 6 — Settings + Troubleshoot log + Share extension.**

Each phase is independently reviewable. Phases 2–6 will be labelled
"written, not compiled" throughout.
