# LANyard File Transfer Handoff

> **Protocol:** Claude Code and opencode take turns. Whoever finishes a turn **overwrites this whole file** with the current state (keep it short and accurate), and sets "Last updated by" and "Next agent". Read `p2p_file_transfer_specification_v2.md` for the design; this file is only the current state.

- **Last updated by:** opencode, 2026-10-05
- **Next agent:** Claude Code
- **Milestone just finished:** application icon (binary/desktop + window) and a Windows **system-tray** icon.
- **Next:** hardening/release, per the list at the bottom. (All of M1–M6 plus hardening passes 1–2 are done.)

## Environment
- Go 1.27.0 (default install in Program Files\Go\bin; `build.ps1` finds it even if it is not on PATH. For plain `go` in a non-interactive shell: `$env:Path += ";$env:ProgramFiles\Go\bin"`).
- Not a git repository. Module `lanyard`, entry `cmd/lanyard`, version `0.4.0-m6`. CGO off. Working folder is still `EZ-Share`.
- **Build:** `.\build.ps1` -> `dist\lanyard.exe` (console; the `dist\itest*.ps1` scripts use it); `.\build.ps1 -Release` -> `dist\release\` (windowless + console Windows, mac arm64/x64, linux x64/arm64). All build clean.
- `go vet ./...`, `gofmt -l .`, `go test ./...` clean (race detector unavailable: needs cgo).
- **Traps:** PowerShell alias `RI` = `Remove-Item`; Windows PowerShell 5.1 `Invoke-WebRequest` cannot send PROPFIND (use `HttpClient`, see `dist/itest_mount.ps1`); a background browser tab has `document.hidden === true`.

## What exists (all working, tested)
```
cmd/lanyard/    main.go, cli.go, logging.go, console_windows.go, tray.go (shared opts), tray_windows.go, tray_other.go,
                icon.ico (embedded for the tray), rsrc_windows_amd64.syso (PE icon resource)
internal/…      config identity trust discovery peerapi shares transfer inbox approval autostart mount uiserver
                (uiserver/web/ now also has icon.png = favicon + header logo)
tools/mkicon    regenerates icons/icon.ico, cmd/lanyard/icon.ico and web/icon.png from icons/icon.png
icons/          icon.png (source, 1133x985), icon.ico (16–256 px)
build.ps1  README.md  dist/lwlib.ps1  dist/itest*.ps1 (twelve scripts)
```

### This turn: icon and tray
- **Binary / desktop icon:** `icons/icon.png` was made into a square multi-size `icons/icon.ico` and compiled into the Windows binaries with `rsrc` as `cmd/lanyard/rsrc_windows_amd64.syso`. Explorer, the desktop and the taskbar show it (verified: `ExtractAssociatedIcon` returns 32x32 for both Windows exes). The mac/linux builds ignore the `.syso` and are unchanged.
- **Window:** the same image is the browser favicon (`<link rel="icon">`), the `apple-touch-icon`, and a small header logo (`web/icon.png`, 192px, served at `/icon.png`).
- **System tray (Windows):** `cmd/lanyard/tray_windows.go` creates a hidden window and a `Shell_NotifyIcon` icon using only `golang.org/x/sys/windows` (no cgo; `runtime.LockOSThread` + a Win32 message loop). Left-click opens the UI in the browser; right-click offers **Open LANyard** / **Quit**. The icon is loaded from `<data-dir>\icon.ico` (the embedded `.ico` is written there at startup), so it does not depend on the resource id. `--no-tray` disables it. Non-Windows `startTray` is a no-op.
- **Tests:** the twelve `dist/itest*.ps1` scripts now pass `--no-tray` so they stay deterministic.

### Verified this turn
- `go test ./...`, `go vet`, `gofmt` clean.
- `.\build.ps1 -Release` builds all six binaries; both Windows exes carry the icon; starting `dist\lanyard.exe` logs `tray icon added`, stays alive, serves `/api/ping`, writes `<data-dir>\icon.ico`, and serves `/icon.png` (200, image/png); the index has the favicon link.
- Representative end-to-end scripts pass with `--no-tray`: `itest`, `itest_pair`, `itest_settings`, `itest_cli`, `itest_perms`, `itest_mount`.
- README, spec §10/§13 and build.ps1 document the icon/tray.

### Regenerating the icons
```
go run ./tools/mkicon
rsrc -arch amd64 -ico icons\icon.ico -o cmd\lanyard\rsrc_windows_amd64.syso   # go install github.com/akavel/rsrc@latest
```
`cmd/lanyard/icon.ico` (embedded by the tray) is rewritten by `mkicon` too.

## Known gaps / do not forget
- **Two physical machines / two OSes still never tested** (mDNS, beacon, firewall, pairing, pull, push, mount). Everything so far is loopback on one Windows machine.
- **Tray is Windows-only**; macOS/Linux have no tray and no app icon (a `.icns`/`.app` or `.desktop`/PNG would be needed). Not verified beyond "it registers and the process serves".
- No desktop **shortcut** is created; the exe itself carries the icon. Start-on-login uses a `HKCU\...\Run` value, not a `.lnk`.
- Tray menu is only Open/Quit (no balloon notifications, no per-job menu).
- Bandwidth limit is per stream and not applied on the serving side. Jobs store one `Host:Port`. Disk-full *during* a transfer is untested.
- No Auto-download/sync (v1.1). No auto-update. Builds are unsigned (SignTool/notarization pending user certificates).
- Mount refuses writes/`LOCK`/`PROPPATCH` (intended); large trees listed one level at a time.

## Suggested next tasks
1. **Two-machine, two-OS test** (needs the user's second device): discovery, pairing, pull, push, mount.
2. macOS/Linux app icon + tray (and a real smoke test of mount/autostart there).
3. Optional polish: tray balloon on completion / per-job items, desktop shortcut, multi-address job fallback, serving-side bandwidth limit.
4. **Release:** code signing/notarization when the user has certificates; bump `version`; refresh README.

## Decisions already made with the user (do not re-litigate)
- Product name **LANyard File Transfer**; binary `lanyard`.
- Port 47800 TCP with random free-port fallback; beacon UDP 47801.
- Unpaired (Connect) peers cannot browse; they see only shares offered into the session. Paired peers browse active shares. Connect closes after one transfer unless Keep connected.
- Timed-share presets (5, 15, 30 min, 1, 2, 3, 6, 12, 24 h) + custom (min 10 s, max 30 days), Until I stop, Always, One-time.
- Pulls never overwrite: saved as `name (1).ext`.
- Single static Go binary, embedded UI, no cgo, no Wails/Tauri. At-rest encryption out of scope for v1.
- Remote peers only see `share_id`, `label` and relative paths; absolute local paths never leave the machine.
- Settings: Device ID is a user-settable label; the certificate fingerprint is the identity; mount-as-share-drive is read-only by default.
