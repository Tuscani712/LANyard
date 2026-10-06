# LANyard File Transfer

Send files and folders directly between your own devices on the same network. No cloud, no accounts, no
server. One small program (about 9 MB, nothing else to install) runs on each device; the devices find each
other, and everything between them is encrypted.

* **Share** a file or a folder, for as long as you choose (30 minutes, until you stop it, once, always).
* **Download** from any device you can see, with progress, speed, ETA and **resume** after any interruption.
* **Connect** to a device for a single transfer, or **Pair** with it for unattended access later.
* **Push** files into another device's Inbox, **mount** a paired device as a read-only drive.

Status: the full design is in `p2p_file_transfer_specification_v2.md`. Binaries are **not code-signed yet**, so
Windows SmartScreen and macOS Gatekeeper will warn the first time.

## Quick start

1. Copy the program for your system to each device (see *Downloads* below) and run it. A browser tab opens with
   the LANyard window. (It only listens on your own computer for that window; nothing else can open it.)
2. If Windows asks about the firewall, choose **Private networks** and **Allow access**.
3. Devices running LANyard on the same network appear under **Nearby devices** within a few seconds.
4. On the device that has the files: **My shares** -> type the path of a file or folder, pick how long to share
   it, **Share**.
5. On the other device: **Connect** (one transfer) or **Pair** (trusted) -> compare the 6-digit code shown on
   both screens -> **Browse** -> tick files -> **Download**.

## Connect or Pair?

| | Connect | Pair |
| :--- | :--- | :--- |
| For | one transfer with a device you do not keep trusting | your own machines, unattended use |
| Remote can browse | only the shares you **offer** into the session | every active share open to paired devices |
| Remote can push to you | only if you **accept each transfer** | if you allow it (optionally ask above a size) |
| Lasts | one transfer, then it closes (or "Keep connected") | until you unpair |

Both start the same way: one device asks, the other accepts, and **both screens must show the same 6-digit
code**. That check is what stops someone in the middle of the network from impersonating a device. If the
codes differ, press Reject.

## Shares and how long they last

Each share is one file or one folder, with a lifetime: **5, 15, 30 minutes, 1, 2, 3, 6, 12, 24 hours**, a
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

## Mount a paired device as a drive

On a paired device's card, **Mount as drive**. The device's shares appear as a **read-only** drive.

* **Windows:** pick a drive letter (for example `Z:`). This uses Windows' WebClient service, which must be
  running; if it is not, LANyard tells you. Start it once with `sc start WebClient` in an administrator prompt.
* **macOS:** run the command shown (`mount_webdav ...`). **Linux:** `davfs2` (or your file manager's "Connect to
  server") with the address shown.

Unpairing, or the other device stopping a share, takes effect on the drive immediately. Nothing is cached on disk.

## Command line

The command line talks to the LANyard that is already running on this computer.

```
lanyard                                   start (opens the window; --no-browser to skip, --no-tray for no tray icon)
lanyard peers                             list nearby devices
lanyard share add <path> [--for 30m|2h|45s|always|once] [--label NAME]
lanyard get <device> <share> [folder]     download a share
lanyard mount add <device> [Z:]           mount a paired device (also: mount list | mount remove <id>)
lanyard settings get | set <key> <value>  device_name, theme, start_on_login, bandwidth_limit_mbps, ...
lanyard shares cancel-all                 stop every share and every transfer now
```

`--data-dir <folder>` (before or after the command) selects another profile, which is how you run two copies on
one computer for testing.

## Settings

The **Settings** button in the window: device name, **Device ID** (a friendly label; the real identity is the certificate
fingerprint), light/dark theme, speed unit (MB/s or Mbps), a sound when a transfer finishes, **start LANyard when
I sign in**, default download folder, Inbox folder, port, bandwidth limit, paired devices (edit what each may do, or
unpair), and **Cancel all shares**.

## How it is protected

* Every connection is TLS 1.3 with **certificates on both sides**. Each device makes its own key on first run;
  its **fingerprint** is its identity. There is no server, no password and no account.
* A device can only do what you allowed it to do when you paired or connected, and unpairing takes effect at
  once, including for open connections and mounted drives.
* Shares are confined to the file or folder you picked (no `..`, no symlink escapes). Other devices never see
  your real folder paths, only the share name and relative paths. Unoffered shares answer "not found".
* The window on your own computer is protected against other web pages (token, host and origin checks) and only
  listens on `127.0.0.1`.
* Nothing leaves your network. LANyard makes no internet connections and has no telemetry or auto-update.

## Ports, firewall and when devices do not appear

| Port | Use |
| :--- | :--- |
| TCP 47800 | other devices talk to this one (falls back to a random free port; discovery tells the others) |
| UDP 5353 (multicast) | finding devices (mDNS) |
| UDP 47801 | finding devices when multicast is blocked (broadcast) |
| `127.0.0.1` only | the window and the CLI |

If a device does not show up: both must be on the same network (guest Wi-Fi and some routers isolate devices);
allow LANyard through the firewall for **private** networks; on a VPN or a different subnet use **Add by
address** with the other device's IP address and the port shown at the top of its window.

## Downloads and building

Release builds are produced by `build.ps1 -Release` into `dist\release\`:

| File | For |
| :--- | :--- |
| `lanyard-win-x64.exe` | Windows, no console window (double-click, start at sign-in) |
| `lanyard-win-x64-console.exe` | Windows, for batch files and scripts |
| `lanyard-mac-arm64`, `lanyard-mac-x64` | macOS (Apple silicon / Intel) |
| `lanyard-linux-x64`, `lanyard-linux-arm64` | Linux |

Building needs Go (the version in `go.mod`, currently 1.27) and nothing else: `.\build.ps1` makes the development build
`dist\lanyard.exe` (no console window) and `dist\lanyard-console.exe` (console, for the CLI and the `dist\itest*.ps1`
scripts); the program is a single static executable with the UI embedded. Windows builds carry the app icon (in Explorer,
the taskbar and the browser tab) and a **system-tray icon** (left-click to open the window, right-click to quit;
`--no-tray` to skip it). Tests: `go test ./...`; end-to-end scripts are `dist\itest*.ps1`.

## Linux

On Linux the program opens in its own window (an embedded WebKitGTK view, same UI as Windows), has a system-tray
icon (StatusNotifierItem: KDE, XFCE, GNOME with the AppIndicator extension) and uses the desktop's own file dialogs.
With no display (ssh, servers) it runs headless, or `--web` uses the browser.

* **Run:** needs `libwebkit2gtk-4.1-0` and GTK 3 (`sudo apt install libwebkit2gtk-4.1-0`; present on most desktops).
* **Build:** `sudo apt install libwebkit2gtk-4.1-dev build-essential`, then `go build -o lanyard ./cmd/lanyard`.
  This build uses cgo. A cross-compiled (`CGO_ENABLED=0`) Linux binary still works, but opens the browser instead of a window.

## Where things are stored

Settings, the device key and certificate, trust list and log live in `%APPDATA%\LANyard` (Windows),
`~/Library/Application Support/LANyard` (macOS) or `~/.config/LANyard` (Linux); the log is `lanyard.log` there.
Received pushes go to the Inbox folder, by default `Inbox` inside that folder.

## Known limits

* Not signed or notarized yet. Not yet tested across several physical machines and operating systems; please
  report what you see.
* Received files are not encrypted at rest (use your disk's own encryption).
* Mounting needs an OS component (WebClient on Windows, davfs2 on Linux).
* The Linux window needs WebKitGTK installed; the tray needs a desktop with a tray host. Not tested on GNOME or Wayland yet.
