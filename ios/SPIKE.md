# LANyard iOS/macOS — First-Mac Spike Checklist

Status of this work: **written, not compiled.** This Linux box has no macOS,
Xcode, or Apple SDK, so nothing under `Sources/LanyardNet/` (or the parts of
`Sources/LanyardCore/CertificateBuilder.swift` that Apple uses) has ever been
type-checked by the Apple toolchain. On Linux every `LanyardNet` file is behind
`#if canImport(Network)` / `#if canImport(Security)` and compiles to nothing.
The checklist below is the order to run on a real Mac, highest risk first.

---

## Prerequisites (do these once, before step 1)

These are real blockers found while writing the code on Linux. They are not
guesses; they are facts about the current repository snapshot.

1. **Module access — RESOLVED.** The Phase-1b seams were `internal`; they are now
   `package`, so the sibling `LanyardNet` target can see `Fingerprint`,
   `ChunkedDecoder`, `PairingSessions`, `InboxReceiver`, `PeerHttpException` and
   the seam protocols. Verify with `swift build` on the Mac; a "cannot find type
   'X' in scope" error means a seam was still `internal`.
2. **`CertificateBuilder`.** `Sources/LanyardCore/CertificateBuilder.swift`
   builds the Ed25519 self-signed X.509 with `swift-asn1` + swift-crypto (never
   Security), and is Linux-tested in both directions against Go
   (`ios/fixtures/crosscheck.sh` and `GoCertCrossCheckTests`). It exposes
   `build(deviceName:privateKey:serial:notBefore:notAfter:)` plus an
   external-signer `selfSignedCertificate(publicKeyRaw:subject:serialNumber:
   notBefore:notAfter:sign:)` — the one `SecIdentityFactory` uses when the
   Ed25519 key lives in the Keychain as a `SecKey`.
3. **Info.plist, iOS.** Add `NSLocalNetworkUsageDescription` and
   `NSBonjourServices = ["_lanyard._tcp"]`, or the Bonjour browse in step 5
   silently returns nothing (TN3179).
4. **macOS entitlements.** For step 5, the app needs
   `com.apple.security.network.client` (and `…network.server` for the listener)
   plus the Location Services local-network approval prompt.
5. **A harness target.** The package has no `LanyardNet` executable. Add a
   throwaway `Sources/spike/main.swift` and an `executableTarget(name: "spike",
   dependencies: ["LanyardNet", "LanyardCore"])` to `Package.swift` **on the
   Mac only** to drive the steps below. Do not commit it to the real repo.
6. **Invite QR port — resolved in core, wire it on the Mac.** `PairFlow` now
   takes a `PortProvider` and stays `.starting` (no QR) until the bound listener
   port is known, so the invite can never carry `addr=host:0`. The app must feed
   the real `PeerListener` port into a `ClosurePortProvider` (see
   `LanyardApp/PairingServices.swift`) once the listener is bound on the Mac.

---

## Step 1 — Ed25519 client certificate accepted by the Go server  *(highest risk)*

**Why first:** Android's first attempt used Conscrypt/BoringSSL and the Ed25519
client certificate was rejected by the Go server (`SSLv3_ALERT_HANDSHAKE_FAILURE
/ HANDSHAKE_FAILURE_ON_CLIENT_HELLO`); the fix was to switch to BouncyCastle's
JSSE provider. Apple's TLS stack is a third implementation and may or may not
accept a software-token Ed25519 `SecIdentity`. Do not build anything else on top
until this handshake completes.

**Run the Go peer (on the desktop / Linux box), with a known data dir:**

```sh
cd /path/to/LANyard
go run ./cmd/lanyard --port 47800 --no-browser --data-dir /tmp/lanyard-spike
# note the Device ID it prints; the cert is /tmp/lanyard-spike/identity.crt
```

**Run the Mac harness (client role):** connect a `PeerConnector` to the peer,
pinned to the Go peer's fingerprint, then `GET /api/v1/hello`.

**Expected result:** the harness logs the peer's JSON hello (`{"name":…}`), and
the Go side logs a completed mTLS connection from the Mac (a request line for
`GET /api/v1/hello`). This proves both directions of certificate exchange.

**If it fails:**
- `errSSLPeerHandshakeFail` / `-9808` / "no common cipher": capture both ends
  with `NWConnection`'s `stateUpdateHandler` error and the Go server log at
  `slog.LevelDebug`. If the failure is specifically "no shared signature
  algorithm" or the Go server reports it cannot verify the certificate
  signature, the Ed25519 `SecKey` signature is the suspect.
- Confirm the certificate truly has an Ed25519 key:
  `openssl x509 -in identity.crt -noout -text | grep -A1 "Public Key Algorithm"`
  on the Mac-generated cert should say `ED25519`.
- Try a trivial `openssl s_client -connect 127.0.0.1:47800 -cert mac.crt
  -key mac.key -tls1_3` to see whether the failure is inside Network.framework
  or in the Go verifier.
- **Documented fallback (DO NOT IMPLEMENT without the project lead's
  sign-off):** a P-256 (prime256v1) identity. It would mean generating the key
  with `kSecAttrKeyTypeECSECPrimeRandom`, extending `CertificateBuilder` with the
  P-256 SPKI (`id-ecPublicKey` + `prime256v1`) and an ECDSA-SHA256 signature, and
  changing the Go/Kotlin verifiers to accept it. This is a protocol change with
  fingerprint and wire-compatibility consequences, so it is **not** implemented
  here — record the spike outcome and escalate.

---

## Step 2 — `SecIdentity` created from the Keychain is usable by TLS

**Why:** there is no `SecIdentityCreate`. `SecIdentityFactory` writes
`kSecClassKey` + `kSecClassCertificate` under a shared tag and reads them back
with `SecItemCopyMatching(kSecClassIdentity)`. Whether the Keychain pairs a
software-token Ed25519 key with its cert — and whether `SecKeyCreateSignature`
with `.eddsaSignature` works on it — must be confirmed.

**Run (Mac):**

```sh
# 1. Generate + store, then read back:
#    SecIdentityFactory.create(deviceName: "Spike")
#    SecIdentityFactory.loadStored()
# 2. Print id and cert:
security find-identity -v -p ssl-client       # should list the tag as a valid identity
security find-certificate -a -Z -c "LANyard"  # SHA-256 of the leaf
```

**Expected result:** `SecIdentityFactory.create` returns a `Material` whose
`deviceId` is a 64-char hex string; `loadStored` returns the same identity after
a process restart; `security find-identity` lists it.

**If it fails:**
- `errSecParam (-50)` from `SecItemAdd` on the key: the key may need
  `kSecAttrIsPermanent` set at creation, or the attribute set is too sparse. Try
  generating with `kSecAttrIsPermanent: true` + `kSecAttrApplicationTag` so
  `SecKeyCreateRandomKey` writes it directly, and drop the separate `addKey`.
- Identity lookup returns `errSecItemNotFound`: the key and certificate may not
  be associated. Check that both carry the same `kSecAttrApplicationTag`, and
  that the cert's public key bytes equal the key's public half.
- `.eddsaSignature` does not compile: on some SDKs the case is named
  differently. Check the `SecKeyAlgorithm` declaration; if only a raw constant
  exists, use `kSecKeyAlgorithmEdDSASignature`.
- On macOS, confirm `kSecUseDataProtectionKeychain: true`; without it the login
  keychain is used and items behave differently (see the file).

---

## Step 3 — The verify-block fingerprint pin on **both** roles

**Why:** the pin must run under `NWConnection` (client) *and* under `NWListener`
(server), and the peer chain must be readable after `require-peer-auth` with a
self-signed cert (no CA). `SecTrustCopyCertificateChain` may or may not return
the chain for a self-signed leaf; and the chain is only available if the verify
block actually runs.

**Run (Mac), two harnesses:**

1. Client pin: a `PeerConnector` pinned to the *wrong* fingerprint must fail the
   handshake before `GET /hello` is written. Pinned to the right one, it
   succeeds. (This is what step 1 already exercises.)
2. Server pin: start `PeerListener` with `TLS.serverOptions(identity:,
   allowedFingerprints: [macFingerprint])`; a client presenting a different cert
   must be rejected during the handshake. Then `allowedFingerprints: nil` accepts
   any cert (probe/hello path).
3. Server chain read: with a correct client, `PeerListener.peerFingerprint(_:)`
   must return the client's Device ID (check the `GET /hello` response does not
   401).

**Expected result:** mismatches abort the handshake on both roles; the correct
client is accepted; the server can compute the peer fingerprint and route the
request.

**If it fails:**
- Verify-block never runs / `SecTrustCopyCertificateChain` returns nil: the
  trust object may need `SecTrustSetAnchorCertificates`/`…AnchorCertificatesOnly`
  off, or evaluate manually with `SecTrustEvaluateWithError`. Try calling
  `SecTrustEvaluateWithError` first and, if the chain is still empty, build a
  `SecCertificate` from `sec_trust_copy_ref` trust directly.
- `sec_trust_copy_ref` crashes with over-release: it is `CF_RETURNS_RETAINED`, so
  `takeRetainedValue()` is correct; if the runtime disagrees, try
  `takeUnretainedValue()` (this is the exact thing to test).
- Server side: if `sec_protocol_metadata_copy_peer_certificate_chain` /
  `sec_certificate_copy_ref` do not exist or return nothing under `NWListener`,
  fall back to recording the fingerprint in the verify block into a
  connection-scoped box and attach it to the request via `NWConnection`'s
  `metadata`. Document the actual API in the code once known.

---

## Step 4 — Fingerprint parity: Mac SPKI SHA-256 == `go run` fingerprint

**Why:** the whole trust model is "the Device ID is SHA-256 of the cert's
SubjectPublicKeyInfo". If Apple's `SecKeyCopyExternalRepresentation` for Ed25519
is not the raw 32 bytes we assume, every fingerprint is wrong.

**Run (Mac):** generate an identity and print the raw public key hex and the
Mac-computed Device ID:

```swift
let material = try SecIdentityFactory.create(deviceName: "Spike")
var cert: SecCertificate?
SecIdentityCopyCertificate(material.identity, &cert)
let key = SecCertificateCopyKey(cert!)!
let raw = SecKeyCopyExternalRepresentation(key, nil)! as Data   // expect 32 bytes
print(Hex.encode(raw))            // raw Ed25519 public key
print(material.deviceId)          // Mac fingerprint
```

**Run (Go) for the same raw key:**

```sh
cd /path/to/LANyard
cat > /tmp/fp.go <<'EOF'
package main

import (
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"os"
)

func main() {
	raw, _ := hex.DecodeString(os.Args[1]) // 32-byte raw public key
	spki := append([]byte{0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00}, raw...)
	sum := sha256.Sum256(spki)
	_ = x509.MarshalPKIXPublicKey
	fmt.Println(hex.EncodeToString(sum[:]))
}
EOF
go run /tmp/fp.go "$(the raw hex from the Mac)"
```

**Expected result:** the Mac's `material.deviceId` equals the Go output, and the
raw hex is exactly 64 characters (32 bytes). Also compare against
`ios/fixtures/generate.go`'s vectors, which the Linux test already checks.

**If it fails:**
- If `SecKeyCopyExternalRepresentation` returns 44 bytes starting
  `302a300506032b6570032100`, the OS gave the SPKI, not the raw key; the code
  already accepts both. If it returns some *other* length/prefix, Apple changed
  the encoding and `TLS.rawPublicKey` / `SecIdentityFactory.rawPublicKey` must be
  corrected against the actual bytes.
- If the hashes differ only by the prefix, the raw-vs-SPKI branch was taken
  incorrectly — log the byte count.

---

## Step 5 — Bonjour via `NWBrowser` and the Local Network prompt

**Why:** discovery must see `_lanyard._tcp` and the TXT fields `v,id,did,n,os,p`,
and iOS must actually prompt (and grant) Local Network access. A missing Info.plist
key fails silently.

**Run (Mac / iOS device):**

```sh
# On the desktop, ensure the peer is advertising (default peer port):
go run ./cmd/lanyard --port 47800 --no-browser
```

On the device, start `BonjourBrowser` and log `onResults`. Expect an entry whose
`shortId` equals the Go peer's `identity.ShortID` (first 16 hex of its Device ID),
with the same `port`, `name`, `os`, and `version == "2"`.

Also verify service visibility from the command line:

```sh
dns-sd -B _lanyard._tcp local.
dns-sd -L <instance> _lanyard._tcp local.   # then read the TXT record
```

**Expected result:** the system prompt appears on first browse; after allowing,
`dns-sd -B` and `NWBrowser` both list the Go peer; TXT fields round-trip.

**If it fails:**
- No prompt and no results: `NSLocalNetworkUsageDescription` and
  `NSBonjourServices` are missing from Info.plist, or the app was already denied
  (Settings → Privacy → Local Network). TN3179 covers this.
- `dns-sd` sees it but `NWBrowser` does not: on iOS simulator Local Network is
  often unavailable — use a real device.
- TXT values missing: the `NWBrowser.Result.metadata` case/`txtRecord` spelling
  differs on this SDK. If `service.txtRecord` is nil, check whether the SDK
  exposes `NWTXTRecord` instead and adapt `BonjourBrowser.announcement(from:)`.
- Wrong service type/domain: confirm `_lanyard._tcp` and `local.` exactly match
  `internal/discovery/mdns.go`; note `NWEndpoint.service` resolves to the
  instance name, which the code requires to equal the TXT `id`.

---

## Every `MAC-SPIKE:` item in the code

| File | Item |
| --- | --- |
| `SecIdentityFactory.swift` | Exact Swift spelling of the Ed25519 signing algorithm (`.eddsaSignature` vs `.ed25519Signature` / `kSecKeyAlgorithmEdDSASignature`). |
| `KeychainIdentityStore.swift` | Whether a software-token Ed25519 key pairs with its cert for a `kSecClassIdentity` lookup (see step 2). |
| `TLS.swift` | `sec_trust_copy_ref` retain semantics (`takeRetainedValue` vs `takeUnretainedValue`). |
| `TLS.swift` | Which form `SecKeyCopyExternalRepresentation` returns for Ed25519 (raw 32 vs 44-byte SPKI). |
| `PeerListener.swift` | Header cap: `PeerHelloServer.MAX_HEADER_BYTES` does not exist in the snapshot; using the Android value (16 KiB). |
| `PeerListener.swift` | `sec_protocol_metadata_copy_peer_certificate_chain` + `sec_certificate_copy_ref` availability/behaviour under `NWListener`. |
| `BonjourBrowser.swift` | `NWBrowser.Result.metadata` TXT-record spelling (`service.txtRecord` vs `NWTXTRecord`). |
| `BonjourBrowser.swift` | Address resolution via a plain TCP connect is intrusive; production should read addresses from a real connection. |

## Known gaps / things not implemented

- **P-256 fallback** — documented only in step 1, deliberately not implemented
  pending the project lead's sign-off.
- **`/api/v1/shares/*` server routes** — `LanyardCore` has no `ShareServer`;
  the server adapter returns 404 for share routes (the Android `ShareServer` has
  no Swift port yet). The *client* `ShareReader` is implemented.
- **Snippet endpoint, push approval queue, per-IP rate limit, keep-alive request
  loop** — `PeerListener` currently serves one request per connection and the
  adapter has no snippet route; the Android server has all of these. These are
  follow-ups, not regressions.
- **`package`/`public` access on `LanyardCore` seams** — see Prerequisites 1.
