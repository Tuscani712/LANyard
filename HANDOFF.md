# LANyard File Transfer Handoff

> **Protocol:** Claude Code and opencode take turns. Whoever finishes a turn **overwrites this whole file** with the current state (keep it short and accurate), and sets "Last updated by" and "Next agent". Read `p2p_file_transfer_specification_v2.md` for the design; this file is only the current state.

- **Last updated by:** Claude Code (Sonnet 5.5), 2026-10-06, branch `ubuntu` (local, not pushed)
- **Next agent:** opencode
- **Milestone just finished:** Linux port: native WebKitGTK window, D-Bus tray, GTK file chooser, `--data-dir` before the command fixed.
- **Next:** test on GNOME/Wayland and on a real second machine (Windows <-> Ubuntu); merge `ubuntu` after Meatbag approves.
- **Linux files:** `cmd/lanyard/nativeui_linux.go` (cgo GTK3 + webkit2gtk-4.1; window, SIGUSR2 raise, file chooser via `uiserver.PickHook`), `tray_linux.go` (pure-Go godbus SNI + dbusmenu), `nativeui_other.go`/`tray_other.go` are the fallbacks. SIGUSR1 is reserved by WebKit's JS engine; do not use it. Closing the window quits unless `minimize_to_tray` is on and a tray host registered.
- **Linux tested here:** two local instances pair + pull a 5 MB file (hash ok); window renders; picker cancel; hide-to-tray, tray Activate/Quit, second launch raises window (KDE X11). Not tested: push, mount, GNOME, Wayland.

## Environment
- Go 1.27.0 (default install in Program Files\Go\bin; `build.ps1` finds it even if it is not on PATH. For plain `go` in a non-interactive shell: `$env:Path += ";$env:ProgramFiles\Go\bin"`).
- Git repo: github.com/Tuscani712/LANyard (main). Module `lanyard`, entry `cmd/lanyard`, version `1.0.0`. CGO off. Working folder is `LANyard` (renamed from `EZ-Share`).
- **Build:** `.\build.ps1` -> `dist\lanyard.exe` (windowless app) + `dist\lanyard-console.exe` (console, used by the `dist\itest*.ps1` scripts); `.\build.ps1 -Release` -> `dist\release\` (windowless + console Windows, mac arm64/x64, linux x64/arm64). All build clean.
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

### Follow-up fixes (Claude Code)
- **Stale "Review"/"Confirm code" after pairing:** the Paired page listed finished pairings (status `active`) under "Requests & sessions". Now only pending/accepted requests and open Connect sessions are listed; Confirm code shows only while `accepted`.
- **Online/offline:** devices show real status. Discovery probes every 5 s (was 15 s) and drops a device after 2 misses (~10 s). The UI keeps paired devices listed when away (greyed, "Offline - last seen ..." from localStorage `lanyard.seen`), disables Push/Mount for them, explains on the device page, and toasts "X is online / went offline" for paired devices.
- **Exit:** closing the window (X) with "minimize to tray" off now cancels the app context, removes the tray icon and force-exits after 4 s if a graceful stop hangs (`main.go`). Verified by script: window close -> process gone in under 2 s, run.json removed. With "minimize to tray" ON the X hides the window by design; quit from the tray menu.
- Push files / Push folder use the Windows file dialog (see below); if it still asks for a typed path the running build is old or the dialog errored (it only falls back to typing on non-Windows).

### This turn: two-machine feedback (Claude Code)
- **Notifications, not takeovers:** an incoming pair/connect request and an incoming-files approval are now sticky toasts (bottom right, `stickyToast` in `app.js`); clicking one opens the accept screen / approvals modal ("Decide later" closes it). Nothing opens by itself.
- **Native dialogs:** `POST /api/fs/pick {kind: folder|files, title, start}` shows the real Windows IFileOpenDialog (`internal/uiserver/pick_windows.go`, raw COM, no cgo); other OSes return 501 and the UI falls back to typing. Download uses the saved/default folder (first time it asks via the dialog); "Download to..." always asks; Settings folders have Browse; Push files.../Push folder... use the dialog.
- **Receiver can cancel an accepted push:** `inbox.Incoming()/Cancel()`, `GET /api/incoming`, `POST /api/incoming/{id}/cancel`, SSE event `incoming`, rows with Cancel on the Transfers page. The sender gets 410 "cancelled by the receiver"; partial files are removed. Incoming progress is live (`FileState.live`). Test: `TestReceiverCancelsAnAcceptedPush`.
- **Shared with me:** sidebar entry listing every paired/connected device's shares (Open / Download); the device page explains when you must pair first.
- **Minimize to system tray (Windows):** setting `minimize_to_tray` (Settings page). The tray now runs in native mode too; minimize and close hide the window when the setting is on; tray click/Open restores it; a second launch also finds a hidden window.
- Not verified here: the tray hide/restore by hand (needs the native window), two physical machines.

### Earlier turn: icon and tray
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
