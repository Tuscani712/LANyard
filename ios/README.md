# LANyard for iOS

The iPhone app. It is one half of the LANyard peer protocol
(`../p2p_file_transfer_specification_v2.md`); the Go desktop and the Kotlin
Android app are the reference implementations.

This repository's build box is Linux and has no macOS, Xcode or Apple SDK, so
nothing in this directory can be compiled or run *for iOS* here. The protocol
core (`LanyardCore`) has no Apple-framework dependency and **is** compiled and
tested on Linux with `swift test`.

See [`DESIGN.md`](DESIGN.md) for the design of record and the phase plan.

## Requirements

- A Mac with Xcode (for the app).
- XcodeGen: `brew install xcodegen`.
- Or, for the protocol core only, a Swift 6.x toolchain on Linux or macOS.

## One-command Mac setup

```sh
cd ios
xcodegen generate            # regenerates Lanyard.xcodeproj from project.yml
open Lanyard.xcodeproj
```

(`project.yml` and the app / share-extension targets arrive in Phase 2. The
SwiftPM package below can be opened in Xcode as-is today.)

## Running the protocol-core tests

On Linux (this box):

```sh
cd ios
export PATH="$HOME/swift/usr/bin:$PATH"   # Swift 6.4 toolchain
swift test
```

On a Mac, the same command works, or open `Package.swift` in Xcode and run the
`LanyardCoreTests` scheme.

`swift test` on Linux currently runs **373 tests, 0 failures**.

## What was verified

| Area | Status |
| :--- | :--- |
| Swift 6.4 toolchain, Linux x86_64, from swift.org (sha256 `69f7b2b4…05094e`, GPG good) | **Linux-tested** |
| `LanyardCore` compiles with `swift test` on Linux (no Apple frameworks; Foundation + `swift-crypto` only) | **Linux-tested** |
| Device ID / SPKI fingerprint vs Go (`ios/fixtures/generate.go` fixtures) | **Linux-tested** |
| SAS vs Go (`ios/fixtures/generate.go` fixtures) | **Linux-tested** |
| Pair link encode/parse, caps, rejections (Kotlin `PairLinkTest`) | **Linux-tested** |
| Pair invites (Kotlin `PairInvitesTest`) | **Linux-tested** |
| Push receive rules: name sanitising, free-space (Kotlin `PushProtocolTest`) | **Linux-tested** |
| Share validation / spool / picker rules (Kotlin `ShareModelTest`) | **Linux-tested** |
| Settings persistence, trust store, transfer policy, self-filter, peer addresses, NIC throttle, display (Kotlin `*Test`) | **Linux-tested** |
| Chunked transfer decoder (chunk line 256, trailer 32 lines / 8 KiB) | **Linux-tested** |
| `InboxReceiver` spool rules (`.lanpart`, offsets, over-run, free-space, abandoned-spool cleanup, `onCancelled`) | **Linux-tested** |
| `Diagnostics` + redaction + the 200-event server ring buffer | **Linux-tested** |
| `PairingFlow` / `PairingSessions` state machines (behind a transport/clock seam) | **Linux-tested** |
| `PushSession` / `DownloadSession` state machines (behind a transport/sink seam) | **Linux-tested** |
| QR round-trip | **Partial** — link build/parse/fingerprint tested; the ZXing render/decode path has no Swift/Linux equivalent and is a Mac-only concern |
| Ed25519 self-signed X.509 certificate builder (`swift-asn1` + swift-crypto, never Security) | **Linux-tested** |
| Certificate cross-check: `ios/fixtures/verify_cert.go` parses Swift's DER (`crosscheck.sh`); Swift parses and verifies a Go-generated cert | **Linux-tested** |
| `LanyardNet`: Keychain identity storage, `SecIdentity` creation, `NWListener`/`NWConnection` TLS 1.3 mTLS, the verify-block pin, `NWBrowser` Bonjour, and the PeerClient/PeerServer adapters over the Phase 1b seams | **Written, not compiled — Apple-only; see [`SPIKE.md`](SPIKE.md)** |
| Receive logic: `TransferManager` (states, `Interrupted`, `Dismiss`, clear history), `InboxDestination` (Documents/`LANyard` default, `name (1).ext`, writability refusal), `Authorizer` (per-request rules), `ServerLifecycle` (foreground/background) | **Linux-tested** |
| Discovery/pairing logic: `DiscoveryTxt` (Bonjour TXT + 16-hex short id, beacon), `Devices` (merge/self-filter/online-offline/last-seen/eviction), `PairFlow` (2-minute invite countdown, SAS confirm/decline, permissions, unpair, paste-a-link only) | **Linux-tested** |
| Send/serve/pull logic: `SendFlow` (offer, per-file progress, cancel, resend a Failed send, 403/410/else), `ShareList` (lifetimes, stop, 4-concurrency cap + 503/Retry-After, digest LRU), `PullBrowse` (browse/pull over the `DownloadSession` seams); and `PairFlow`'s `PortProvider` QR gate (no QR until the listener port is known) | **Linux-tested** |
| Settings + Troubleshoot logic: `SettingsModel` (theme, speed unit, notifications, sound, Wi-Fi-only, bandwidth, folder override, About), `Troubleshoot` (platform checks + the iOS Local-Network/listener rows + the 200-event redacted copy report) | **Linux-tested** |
| SwiftUI app (`LanyardApp/`: Devices, Pair, invite QR + countdown, Receive, Send, Share, Browse, Settings, Troubleshoot, Local Network permission screen), the AVFoundation QR scanner, the `UIDocumentPicker` file/folder pickers, the `NWListener` `ShareServer` adapter, the background `URLSession` downloader, the Share extension (`ShareViewController` + App Group), the lifecycle glue, `Info.plist`, `project.yml` | **Written, not compiled — iOS-only; see [`DESIGN.md`](DESIGN.md) and [`SPIKE.md`](SPIKE.md)** |

## Layout

```
ios/
  Package.swift            SwiftPM package: LanyardCore, LanyardNet, certgen
  DESIGN.md                design of record
  SPIKE.md                 first-Mac checklist (Ed25519 client cert, etc.)
  fixtures/generate.go     generates the Go cross-implementation fixtures
  fixtures/generate_cert.go  generates the Go certificate fixture
  fixtures/verify_cert.go  parses Swift's cert DER (used by crosscheck.sh)
  fixtures/crosscheck.sh   Swift -> Go certificate cross-check
  Sources/LanyardCore/     pure protocol logic (Linux-testable)
  Sources/LanyardNet/      Apple-only layer   (guarded; written, not compiled)
  Sources/certgen/         tiny helper that emits a cert DER for the cross-check
  Tests/LanyardCoreTests/  ported Kotlin tests + generated Go fixtures
```

### Regenerating the Go fixtures

The cross-implementation test values are produced by the Go implementation, not
typed by hand:

```sh
go run ios/fixtures/generate.go > ios/Tests/LanyardCoreTests/GoFixtures.generated.swift
go run ios/fixtures/generate_cert.go > ios/Tests/LanyardCoreTests/GoCert.generated.swift
```

### Certificate cross-check (Linux)

The X.509 builder is verified in both directions without a Mac:

```sh
bash ios/fixtures/crosscheck.sh     # Swift builds a DER; Go parses and validates it
```

`crosscheck.sh` runs `swift run certgen <seed>`, pipes the DER into
`go run ios/fixtures/verify_cert.go -`, and fails unless Go's
`x509.ParseCertificate` confirms the Ed25519 signature, the SPKI fingerprint,
the subject, the validity window, and both client and server `ExtKeyUsage`. In
the other direction, `GoCertCrossCheckTests` parses a Go-generated certificate
and verifies its SPKI hash and signature. No expected value is hand-typed.
