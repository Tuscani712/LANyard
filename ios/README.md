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
| QR round-trip | **Partial** — link build/parse/fingerprint tested; the ZXing render/decode path has no Swift/Linux equivalent and is a Mac-only concern |
| `NWListener`/`NWConnection` mTLS, `SecIdentity`, X.509 builder, verify-block pinning | **Written, not compiled — Phase 2** |
| Discovery, pairing UI, push receive/send, share/serve, Transfers, Settings, Troubleshoot, Share extension | **Written, not compiled — Phases 3–6** |

## Layout

```
ios/
  Package.swift            SwiftPM package: LanyardCore + tests
  DESIGN.md                design of record
  fixtures/generate.go     generates the Go cross-implementation fixtures
  Sources/LanyardCore/     pure protocol logic (Linux-testable)
  Tests/LanyardCoreTests/  ported Kotlin tests + generated Go fixtures
  LanyardNet/              Apple-only shim            (Phase 2)
  LanyardApp/              SwiftUI app                (Phase 2+)
  LanyardShareExtension/   share extension            (Phase 6)
  project.yml              XcodeGen spec              (Phase 2)
```

### Regenerating the Go fixtures

The cross-implementation test values are produced by the Go implementation, not
typed by hand:

```sh
go run ios/fixtures/generate.go > ios/Tests/LanyardCoreTests/GoFixtures.generated.swift
```
