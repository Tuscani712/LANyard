# LANyard File Transfer Handoff

> **Protocol:** Claude Code and opencode take turns. Whoever finishes a turn **overwrites this whole file** with the current state (keep it short and accurate), and sets "Last updated by" and "Next agent". `p2p_file_transfer_specification_v2.md` is the design; `README.md` is the user guide; this file is only the current state.

- **Last updated by:** Claude Code (Opus 5.5), 2026-10-09, V1 merge (`main` = `dev` + `android`)
- **Next agent:** whoever Meatbag assigns
- **State:** V1 = **1.1.0** for both apps (desktop `cmd/lanyard/main.go` `version`; Android `versionName` 1.1.0, `versionCode` 20). Before V1: GitHub pre-releases desktop `v1.1.0-beta.1..7` and Android `android-v0.1.0-beta.1..13`; later betas were local builds only. Versions only move forward (`update.Compare` sorts a pre-release below its release).
- **Branches:** `main` = release. `dev` (desktop) and `android` (the app in `android/`) are merged into it. `ios` is a separate, untested branch and is NOT in main. `ubuntu` is historical.
- **Rules from Meatbag:** nothing is published (GitHub releases, F-Droid, stores) without his explicit go; build locally into git-ignored `dist/release-*` with `SHA256SUMS`. Pro/paid features live in a separate repo: no Pro/tier/upgrade wording here. License AGPL-3.0 (`LICENSE`, `NOTICE`, `TRADEMARK.md`, `CONTRIBUTING.md`).

## Build and test
- **Desktop (Go 1.27, module `lanyard`, entry `cmd/lanyard`):** `gofmt -l ./cmd ./internal ./tools`, `go vet ./...` (also with `GOOS=windows` and `GOOS=darwin`), `go test -race ./...`, `staticcheck ./...` (build it with `GOTOOLCHAIN=go1.27.0`). Linux release commands are in the README (*Downloads and building*); Windows uses `build.ps1 -Release`. The native Linux window build uses cgo and needs `libwebkit2gtk-4.1-dev`; `CGO_ENABLED=0` builds open the browser instead.
- **Android (`android/`, id `io.github.tuscani712.lanyard`, minSdk 26, targetSdk 35):** toolchain in `~/Android` (SDK, JDK 21 at `~/Android/jdk/jdk-21*`, AVDs need `ANDROID_AVD_HOME=~/Android/avd`). Tests: `./gradlew --offline --rerun-tasks :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug`. Release: `LANYARD_KEYSTORE_PROPS=~/Android/keystores/lanyard-release.properties ./gradlew :app:assembleRelease`, then `apksigner verify --print-certs` (cert SHA-256 `d7de7bf9…3b2e`). Back up the keystore; losing it means users can't update.

## Ports
TCP 47800 peer API (configurable; busy = temporary port for that run only, never saved), UDP 47801 beacon (configurable under Advanced; all devices must match), UDP 5353 mDNS (fixed), UI 47810 on 127.0.0.1 only. The phone listens on one remembered TCP port while the app is open or a transfer runs.

## Known gaps
- Tested between a Linux (KDE/X11) desktop and a Pixel phone. Windows, macOS, GNOME and Wayland have had little or no real-machine testing. Binaries are unsigned.
- Desktop notifications are Linux-only (Windows toast is a stub). The phone isn't reachable while the app is closed and idle. No iOS app in main.
- `peerapi.portHolder` reads only `/proc/net/tcp`, so the "in use by <program>" name is often blank for dual-stack listeners (the temporary-port fallback itself works).
- `internal/inbox` `TestReaperReapsPushStuckBetweenFilesFreesPart` can flake under heavy machine load (timing).

## Traps
- WebKit reserves SIGUSR1 (the window raise uses SIGUSR2). `webview_go` pins webkit2gtk-4.0, so LANyard has its own cgo binding for 4.1.
- Don't `pkill -f <path>` (it can kill your own shell); kill by the pid in `run.json`. Run two copies with separate `--data-dir` profiles. Test hosts must never kill a LANyard someone started from `dist/`.
- Android TLS: stock Conscrypt can't do Ed25519 client certs, so the app uses BouncyCastle JSSE, and Android's stripped "BC" provider must be replaced at startup.
- Android share sheet: a `content:` URI's read grant dies with the activity, so files are spooled before it finishes.
- Agent shells inherit `DISPLAY=:0` (the user's screen): prefix GUI runs with `DISPLAY=:77`.

## Decisions already made with the user (do not re-litigate)
- Product **LANyard File Transfer**, binary `lanyard`. Single binary with the UI embedded; no cloud, accounts or telemetry.
- Connect = one transfer, only offered shares; Pair = trusted. Permissions per paired device and per action (browse, push, text): Allow / Ask each time (default; unticked at pairing) / Never. Local device names (alias) are never sent to the other side.
- Downloads and pushes never overwrite (`name (1).ext`). Shares: timed presets, custom 10 s–30 days, Until I stop, Always, One-time.
- Remote peers only see share ids, labels and relative paths. No "visible to nearby" toggle on the phone. At-rest encryption is out of scope.
