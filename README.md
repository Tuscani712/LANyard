# LANyard File Transfer

Send files and folders directly between your own devices on the same network. No cloud, no accounts, no
server. One small program (about 12 MB, nothing else to install) runs on each computer, and an Android app runs on
your phone; the devices find each other, and everything between them is encrypted.

* **Share** a file or a folder, for as long as you choose (30 minutes, until you stop it, once, always).
* **Download** from any device you can see, with progress, speed, ETA and **resume** after any interruption.
* **Connect** to a device for a single transfer, or **Pair** with it for unattended access later.
* **Push** files into another device's Inbox, **mount** a paired device as a read-only drive.
* **Pair with a QR code** (or a pasted link): the code carries the device's fingerprint, so there are no six digits to compare.
* **Send text**: a short message or a link, without making a file.
* **History**: every finished or failed transfer stays listed, with **Resend**.
* **Permissions per device**: browsing, pushing and text are each **Allow**, **Ask each time** or **Never**.
* **Rename** any paired device for yourself; the name stays on your device.
* **Desktop notifications** for pairing requests and finished transfers, and a **Troubleshoot** wizard for when devices do not connect.
* **Android app** with the same features: pair by QR code, send files and folders from the share sheet or the app, receive, browse and download.

Status: the full design is in `p2p_file_transfer_specification_v2.md`. Binaries are **not code-signed yet**, so
Windows SmartScreen and macOS Gatekeeper will warn the first time.

## Quick start

1. Copy the program for your system to each device (see *Downloads* below) and run it. LANyard opens in its own
   window (Windows and Linux; on macOS, or with `--web`, it opens a browser tab instead). The window is served only
   on your own computer; nothing else can open it.
2. If Windows asks about the firewall, choose **Private networks** and **Allow access**.
3. On Linux, allow LANyard through `ufw` (the ports are listed under
   [Ports, firewall](#ports-firewall-and-when-devices-do-not-appear)):
   `sudo ufw allow 47800/tcp && sudo ufw allow 47801/udp && sudo ufw allow 5353/udp`. If TCP 47800 is already
   taken, LANyard waits briefly for it, then uses a temporary port for that run only and shows the exact
   command to run in the banner; open the peer port it names, because the rules above only cover the defaults.
   Change the peer port in **Settings -> Network & Discovery** and the beacon port under **Advanced** there, and
   the banner (and its **Copy** button) generates the matching commands for your ports.
4. Devices running LANyard on the same network appear under **View Devices** within a few seconds.
5. On the device that has the files: **My Shares** -> type the path of a file or folder, pick how long to share
   it, **Share**.
6. On the other device: open the device, then **Connect** (one transfer) or **Pair** (trusted) -> compare the
   6-digit code shown on both screens (or pair with a QR code, below) -> pick the share -> **Download**.

## Connect or Pair?

| | Connect | Pair |
| :--- | :--- | :--- |
| For | one transfer with a device you do not keep trusting | your own machines, unattended use |
| Remote can browse | only the shares you **offer** into the session | every active share open to paired devices |
| Remote can push to you | only if you **accept each transfer** | per its permissions: Allow, Ask each time, or Never |
| Lasts | one transfer, then it closes (or "Keep connected") | until you unpair |

Both start the same way: one device asks, the other accepts, and **both screens must show the same 6-digit
code**. That check is what stops someone in the middle of the network from impersonating a device. If the
codes differ, press Reject.

## Devices and permissions

Open a device to see everything you can do with it, in the same order on the desktop and on the phone: its
status, address and last-seen time; **Send files**, **Send folder** and **Send text**; **Browse their shares**;
**Permissions**; and **Manage** (**Rename**, **Unpair**, and on the desktop **Disconnect** and **Mount as drive**).
Anything you cannot do right now stays visible but greyed out, with the reason (for example "Offline").

For each paired device you choose what it may do to you, separately for **browse/pull**, **push** and **text**:

* **Allow**: it just happens.
* **Ask each time** (the default): you get a prompt (and a notification). The other device shows "Waiting for
  approval on ..." until you answer; no answer counts as a refusal. A browse is asked once per browsing session.
* **Never**: refused, and the other device is told exactly what was refused ("push not permitted").

The boxes ticked while pairing become **Allow**; unticked ones become **Ask each time**. Change them later on the
device page or in Settings. The section also shows, read-only, what that device allows you to do.

**Rename** gives a device a name that only you see (in lists, transfers, notifications and the log); it is never
sent to the other device, and unpairing forgets it.

Cancelling works from either end: when the sender or the receiver cancels, the other side stops at once and shows
"Cancelled by the sender" or "Cancelled by the receiver".

## Shares and how long they last

Each share is one file or one folder, with a lifetime: **5, 15, 30 minutes, 1, 2, 6, 24 hours**, a
custom number of **seconds, minutes or hours** (10 seconds to 30 days), **until I stop**, **always** (kept
across restarts), or **one-time** (ends after one completed, verified download).

When a share ends, nobody new can use it. A download that is already running may finish (up to 10 minutes);
**Stop now** ends it at once, as does **Cancel all shares** in Settings. A downloader whose share ended is told
why ("This share has expired.") and keeps the files and partial files it already has.

## Downloads, resume and the Inbox

* Progress shows the current file, overall percent, speed and ETA. Pause and Resume at any time; after a dropped
  connection, sleep, or restart, a download continues where it stopped (partial files are `name.lanpart`).
* Every file is checked with SHA-256 after it arrives.
* Folders with very many small files are fast (200,000 files took about 3 minutes in our test), and a 50 GB file streams at disk speed with a flat memory use. Before a download starts, LANyard checks that the disk has room and tells you if it does not.
* A download never overwrites a different file: it is saved as `name (1).ext`. Downloading the same thing again
  into the same folder skips what you already have.
* Pushed files land in your **Inbox** folder (Settings). Pushes are also never overwritten. Pushing a folder of many small files is as fast as pulling it (200,000 files in under three minutes in our test). Unless you set a maximum in a paired device's permissions, there is no size limit other than free disk space.

## Pair with a QR code

On the device that should be found, click the QR button at the top right of **View Devices**. It shows a QR code
and a `lanyard://pair?...` link. On a phone, scan it with the LANyard app (**Devices -> Scan QR**); on another
computer, paste the link into the same panel and press **Pair**.

* The link carries this device's **certificate fingerprint**. The scanning device refuses to continue if the
  certificate it meets on the network is not that one, so a device pretending to be it is rejected before anything
  is sent. That is why the six-digit comparison is not needed on this path.
* The link also carries a **one-time invite**. It works once and expires after two minutes; a wrong, used or
  expired invite is refused outright and never falls back to the six-digit flow.
* The device that showed the code still gets an **Accept / Reject** prompt with the permissions to grant, and sees
  the other device's name and short fingerprint. Nothing pairs silently.
* The code only lists addresses on your local network (private ranges), never public ones.

## Text snippets

Open a paired or connected device and use **Send text** to send a short message or a link (up to 64 KB). It
appears on the other device under **Transfers** as "Text from ...", with **Copy** and **Dismiss**. Text is shown as
plain text and links are never opened for you. It has its own **text** permission (see *Devices and
permissions*), and snippets are kept in memory only (they are gone after a restart).

## History and resend

**Transfers** shows what is running, then a **History** list of finished and failed transfers (the newest 500), with
filters **All / Sent / Received / Failed**. **Resend** starts the same transfer again: a push offers the same files
again (it fails clearly if a file is gone), and a download requests the same share paths again. The original entry
stays in the list. **Remove** drops one entry and **Clear history** drops them all; nothing is deleted from disk.

## Notifications

LANyard shows a desktop notification when a device asks to pair or connect, when a transfer finishes, when one
fails, and when a text snippet arrives. Turn them off in **Settings** (they are on by default). On Linux this uses
the desktop's notification service; Windows toast notifications are not wired up yet.

## Troubleshoot

**Settings -> Troubleshoot** checks the usual causes when devices do not see each other or a transfer will not
start: the peer port, this computer's network addresses, whether discovery is hearing other devices, whether
inbound connections reach this computer (a likely firewall block), optionally a chosen device (reachable, encrypted
handshake, same identity), and free disk space. Each result says what to try. **Copy report** gives plain text for
a bug report; it contains no keys, tokens, one-time invites or full fingerprints. The wizard never changes your
firewall or settings.

## Mount a paired device as a drive

On a paired device's page, **Manage -> Mount as drive** (desktop only). The device's shares appear as a **read-only** drive.

* **Windows:** pick a drive letter (for example `Z:`). This uses Windows' WebClient service, which must be
  running; if it is not, LANyard tells you. Start it once with `sc start WebClient` in an administrator prompt.
* **macOS:** run the command shown (`mount_webdav ...`). **Linux:** `davfs2` (or your file manager's "Connect to
  server") with the address shown.

Unpairing, or the other device stopping a share, takes effect on the drive immediately. Nothing is cached on disk.

## Command line

The command line talks to the LANyard that is already running on this computer.

```
lanyard                                   start (opens the window; --no-browser for none, --web for the browser, --no-tray for no tray icon)
lanyard peers                             list nearby devices
lanyard share add <path> [--for 30m|2h|45s|always|once] [--label NAME]
lanyard get <device> <share> [folder]     download a share (default: your download folder, else the current folder)
lanyard mount add <device> [Z:]           mount a paired device (also: mount list | mount remove <id>)
lanyard settings get | set <key> <value>  device_name, theme, start_on_login, bandwidth_limit_mbps, ...
lanyard shares cancel-all                 stop every share and every transfer now (also: lanyard cancel-all)
lanyard update [status|check|download]    optional updates (off until an update address is configured)
```

Other start options: `--name <device name>` (saved), `--port <n>` (preferred peer port), `--ui-port <n>`.

`--data-dir <folder>` (before or after the command) selects another profile, which is how you run two copies on
one computer for testing.

## Settings

The **Settings** button in the window: device name, **Device ID** (a friendly label; the real identity is the certificate
fingerprint), light/dark theme, speed unit (MB/s or Mbps), a sound when a transfer finishes, **desktop notifications**,
**start LANyard when I sign in**, **minimize to system tray** (where a tray exists), default download folder, Inbox
folder, bandwidth limit, **Network & Discovery** (peer port; beacon port under Advanced), paired devices (edit what
each may do, or unpair), **Troubleshoot**, and
**Cancel all shares**. `lanyard settings` covers the same basics from the command line.

## How it is protected

* Every connection is TLS 1.3 with **certificates on both sides**. Each device makes its own key on first run;
  its **fingerprint** is its identity. There is no server, no password and no account.
* A device can only do what you allowed it to do when you paired or connected, and unpairing takes effect at
  once, including for open connections and mounted drives.
* Shares are confined to the file or folder you picked (no `..`, no symlink escapes). Other devices never see
  your real folder paths, only the share name and relative paths. Unoffered shares answer "not found".
* The window on your own computer is protected against other web pages (token, host and origin checks) and only
  listens on `127.0.0.1`.
* Nothing leaves your network. LANyard has no telemetry and makes no internet connections of its own. The only
  exception is the optional update check, which is off until an update address is configured (`lanyard update`).

## Ports, firewall and when devices do not appear

| Port | Use |
| :--- | :--- |
| TCP 47800 | other devices talk to this one. Configurable under **Settings -> Network & Discovery**; if it is busy LANyard uses a temporary port for that run and names it in the banner |
| UDP 5353 (multicast) | finding devices (mDNS); fixed by the mDNS standard |
| UDP 47801 | finding devices when multicast is blocked (broadcast). Configurable under **Settings -> Network & Discovery -> Advanced**; every device must use the same value |
| `127.0.0.1` only | the window and the CLI |

The firewall banner and its **Copy** button generate their `ufw` commands from the ports above, so they always
match your configuration. The default line is
`sudo ufw allow 47800/tcp && sudo ufw allow 47801/udp && sudo ufw allow 5353/udp`.

If a device does not show up: both must be on the same network (guest Wi-Fi and some routers isolate devices);
allow LANyard through the firewall for **private** networks; on a VPN or a different subnet use **Add by
address** with the other device's IP address and the port shown at the top of its window.

## Downloads and building

Release builds are produced by `build.ps1 -Release` on Windows into `dist\release\`, or with the Go commands below on Linux:

| File | For |
| :--- | :--- |
| `lanyard-win-x64.exe` | Windows, no console window (double-click, start at sign-in) |
| `lanyard-win-x64-console.exe` | Windows, for batch files and scripts |
| `lanyard-mac-arm64`, `lanyard-mac-x64` | macOS (Apple silicon / Intel) |
| `lanyard-linux-x64` | Linux with its own window (needs GTK 3 and `libwebkit2gtk-4.1-0`; Ubuntu 22.04+ / Debian 12+) |
| `lanyard-linux-x64-static`, `lanyard-linux-arm64-static` | Linux, no extra libraries; opens in the browser |
| `lanyard-android-<version>.apk` | Android 8.0 and later (see *Android*) |

Building needs Go (the version in `go.mod`, currently 1.27) and nothing else: `.\build.ps1` makes the development build
`dist\lanyard.exe` (no console window) and `dist\lanyard-console.exe` (console, for the CLI and the `dist\itest*.ps1`
scripts); the Windows, macOS and cross-compiled Linux builds are a single static executable with the UI embedded (the
native Linux window build uses cgo and needs the WebKitGTK libraries, see *Linux*). Windows builds carry the app icon (in Explorer,
the taskbar and the browser tab) and a **system-tray icon** (left-click to open the window, right-click to quit;
`--no-tray` to skip it). Tests: `go test ./...`; end-to-end scripts are `dist\itest*.ps1`.

On Linux, the release set is built with:

```
go build -trimpath -ldflags='-s -w' -o lanyard-linux-x64 ./cmd/lanyard                     # native window (cgo)
CGO_ENABLED=0 GOOS=linux GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o lanyard-linux-x64-static ./cmd/lanyard
CGO_ENABLED=0 GOOS=linux GOARCH=arm64 go build -trimpath -ldflags='-s -w' -o lanyard-linux-arm64-static ./cmd/lanyard
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w -H windowsgui' -o lanyard-win-x64.exe ./cmd/lanyard
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags='-s -w' -o lanyard-win-x64-console.exe ./cmd/lanyard
```

## Android

The app (`android/`, id `io.github.tuscani712.lanyard`) runs on Android 8.0 (API 26) and later. Install the APK
(allow "install unknown apps" for your file manager once), open it, and pair with a computer by scanning its QR
code or pasting its link.

* **Send** from the app (a device page: Send files, Send folder, Send text) or from any app's **Share** menu.
  While files are being prepared you see "Preparing N file(s)…" with Cancel.
* **Receive**: pushes land in `Download/LANyard` (Settings can pick another folder), with a notification and
  **Open folder** when they finish. The phone is reachable while the app is open or a transfer is running.
* **Browse and download** a computer's shares into a folder you pick.
* Transfers keep running with the screen off (a foreground service with a notification). **Keep screen on during
  transfers** (Settings -> Receiving, on by default) keeps the screen awake while the app is open and a transfer
  runs, and **Allow background activity** turns off battery optimization for phones that stop apps anyway.
* **Settings -> Troubleshoot** and **Copy log** help with connection problems.

Build: JDK 21 and the Android SDK (platform 35), then `cd android && ./gradlew :app:assembleRelease` (signing is read
from the properties file named by `LANYARD_KEYSTORE_PROPS`; without it, build `:app:assembleDebug`). Tests:
`./gradlew :core:test :app:testDebugUnitTest`.

## Linux

On Linux the program opens in its own window (an embedded WebKitGTK view, same UI as Windows), has a system-tray
icon (StatusNotifierItem: KDE, XFCE, GNOME with the AppIndicator extension) and uses the desktop's own file dialogs.
With no display (ssh, servers) it runs headless, or `--web` uses the browser.

* **Run:** needs `libwebkit2gtk-4.1-0` and GTK 3 (`sudo apt install libwebkit2gtk-4.1-0`; present on most desktops).
* **Build:** `sudo apt install libwebkit2gtk-4.1-dev build-essential`, then `go build -o lanyard ./cmd/lanyard`.
  This build uses cgo. A cross-compiled (`CGO_ENABLED=0`) Linux binary still works, but opens the browser instead of a window.

## Third-party components

LANyard bundles a few small libraries (for example the QR code generator, Nayuki's `qrcodegen`, MIT) and links Go
modules under MIT, BSD and ISC licenses. They are listed in `THIRD_PARTY.md`.

## Where things are stored

Settings, the device key and certificate, trust list and log live in `%APPDATA%\LANyard` (Windows),
`~/Library/Application Support/LANyard` (macOS) or `~/.config/LANyard` (Linux); the log is `lanyard.log` there.
Received pushes go to the Inbox folder, by default a `LANyard` folder in your home folder (on Android,
`Download/LANyard`).

## Known limits

* Not signed or notarized yet. Tested between a Linux desktop and an Android phone; Windows, macOS, GNOME and
  Wayland have had little or no testing on real machines. Please report what you see.
* Received files are not encrypted at rest (use your disk's own encryption).
* Mounting needs an OS component (WebClient on Windows, davfs2 on Linux).
* The Linux window needs WebKitGTK installed; the tray needs a desktop with a tray host. Not tested on GNOME or Wayland yet.
* Desktop notifications are Linux-only for now (Windows toasts are not wired up).
* The Android app is reachable only while it is open or a transfer is running. There is no iOS app yet.

## License

LANyard is licensed under the **GNU Affero General Public License, version 3.0
(AGPL-3.0)**. You may use and modify it freely. If you distribute it, or run a
modified version as a network service, you must release your source under the
same license. See `LICENSE` for the full text, `NOTICE` for the copyright line
and `TRADEMARK.md` for the name and logo.
