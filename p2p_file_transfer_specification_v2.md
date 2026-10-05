# LANyard File Transfer: Technical Specification

**Version:** 2.0.0 (revision of the 1.0.0 draft)
**Status:** Ready for implementation
**Targets:** Windows x64, macOS arm64/x64, Linux x64/arm64
**Deliverable:** one self-contained binary, no installer, no runtime dependencies
**Product name:** **LANyard File Transfer** (short form: **LANyard**, written with "LAN" in capitals)

### Naming

The product was drafted as "EZ-Share", a name already used by another product. It is now **LANyard File Transfer**. "LAN" stands for the local network, and a lanyard is a cord that keeps something securely attached, which describes authenticated, encrypted device-to-device transfer. Public-facing text (window title, installer, docs, store listings) always uses the full "LANyard File Transfer" so searches find it, because "lanyard" is also an ordinary English word. Neighbouring uses found during a web search (not a trademark clearance): a Discord-presence API, a Docker image of network tools, a hybrid-work scheduling app and a festival workforce platform. None is a file-transfer product. Run a proper trademark search before commercial distribution.

**Identifiers (target state after the rename pass):**

| Item | Old | New |
| :--- | :--- | :--- |
| Go module / binary | `ezshare` | `lanyard` (`lanyard.exe`, `dist/lanyard-win-x64.exe`, etc.) |
| mDNS service type | `_ezshare._tcp` | `_lanyard._tcp` |
| Invite string scheme | `ezshare://` | `lanyard://` |
| Data directory | `EZ-Share`, `~/.config/ezshare` | `LANyard`, `~/.config/lanyard` |
| Certificate common name | `EZ-Share <device>` | `LANyard <device>` |
| Partial-download files | `.lanpart` / `.lanstate` | `.lanpart` / `.lanstate` |
| UI title / window text | EZ-Share | LANyard File Transfer |

> **Status:** the existing code (M1) still uses the old identifiers. A single rename pass is scheduled for after M2, so do not rename piecemeal. New code that introduces *new* identifiers (partial-file extensions, new CLI text) should use the new names from the table now.

---

## 0. Review Summary (what changed from v1.0.0 and why)

| # | v1.0.0 | v2.0.0 | Reason |
|---|---|---|---|
| 1 | Three crypto layers: TLS 1.3, plus app-level X25519/HKDF/AES-GCM 64 KB chunks, plus HMAC headers | **One layer: mutual TLS 1.3 with pinned self-signed certificates** | The layers were redundant. App-level chunk encryption also breaks HTTP `Range` resume, because the nonce counter and tags would have to be re-synchronised. The spec never authenticated the ephemeral keys, so it was open to a man-in-the-middle attack. |
| 2 | Identity was a UUID and the public key sat in the mDNS TXT record | **Identity is a long-term Ed25519 keypair; its certificate fingerprint is the cryptographic ID. The Device ID is a user-settable label.** | Fingerprints can't be spoofed. Device ID is a label only. TXT records are limited to 255 bytes per string and are unauthenticated, so only the fingerprint prefix goes there. |
| 3 | Pairing exchanged keys with no human check | **Short Authentication String (6-digit code) shown on both screens and confirmed by the user** | This is what actually defeats a man-in-the-middle on a LAN. |
| 4 | "Connect mode" did not exist | **Added: Connect mode (ephemeral session, single transfer) and Paired mode (persistent, unattended)** | Your requirement. |
| 5 | Shares had no lifetime | **Added: share lifetimes (one-time, 30m, 1h, 3h, custom, until stopped, persistent)** | Your requirement. |
| 6 | Directory download streamed a zip on the fly | **Directory download is a manifest plus per-file transfers. Zip is optional and not resumable.** | A zip generated on the fly has no stable byte offsets, so it can't resume. Per-file `Range` can. |
| 7 | Resume was one line | **Full resume design: `.part` files, sidecar state, validators, hash verification** | Your requirement. |
| 8 | Progress was UI mock only | **Progress model on both ends: current file, per-file and overall percent, smoothed speed, ETA** | Your requirement. |
| 9 | Wails or Tauri webview | **Go binary with embedded web UI, served on loopback and opened in the default browser** | Wails needs WebView2 on Windows and webkit2gtk on Linux, which breaks "standalone, everything embedded". Pure Go with `CGO_ENABLED=0` gives a real static binary and trivial cross-compiling. |
| 10 | Rust or Go | **Go only** | One decision. Go has everything needed in its standard library. |
| 11 | `.p2penc` at-rest encryption | **Removed from v1** | It's scope creep, and the header format was underspecified (wrapped key size, master secret). The OS (BitLocker, FileVault, LUKS) already covers this. Moved to future work. |
| 12 | `SecurePathJoin` using `HasPrefix(rel, "..")` | **Replaced with `os.Root`-scoped access plus explicit rules** | The old check wrongly rejects a file named `..notes.txt` and doesn't stop symlink escapes. It also misses Windows quirks (reserved names, ADS, 8.3 names). |
| 13 | Prototype: node ID from a timestamp, self-filter by hostname, nothing actually listening | **Replaced by a project layout and milestones** | The prototype had real bugs. The self-filter would hide a legitimate peer with the same hostname. |
| 14 | `grandcat/zeroconf` | **Maintained fork (e.g. `libp2p/zeroconf`) behind an interface, plus UDP broadcast fallback and manual connect** | The original library is unmaintained. mDNS is frequently blocked (AP client isolation, firewalls, VPNs), so there must be a fallback. |
| 15 | `/ping` and the tree endpoint's `PeerAuth` token were undefined | **All endpoints except a minimal `/hello` require an authenticated mTLS peer** | Closes the unauthenticated surface. |
| 16 | Replay protection with timestamps | **Removed** | mTLS already provides it. |
| 17 | The `unattended` TXT flag | **Removed** | It advertises which machines are easiest to attack. |
| 18 | Working name EZ-Share | **LANyard File Transfer** (see Naming above) | The old name belongs to another product. |

> **v2.0.0 addendum:** a dedicated **Settings page** was added as §11 — Device ID (unique ID), theme, speed unit, completion sound, autostart, mount-as-share-drive, and Cancel all shares — with the affected sections (§2.1, §3.1, §5.1, §6.2, §8.3, §9.2) updated to match.

---

## 1. Goals and Non-Goals

### 1.1 Goals
1. Launch the binary and the device announces itself on the LAN. Other running instances appear in a live device list.
2. **Both ends are symmetric.** Every instance can share files and folders, and every instance can download from others.
3. Shares are visible to authorized peers and downloadable directly, peer to peer, with no server and no cloud.
4. **Connect mode:** a one-off, human-approved session for a single transfer.
5. **Paired mode:** a persistent trust relationship that allows unattended downloads and pushes.
6. Encryption and authentication between the two devices on every connection.
7. Shares are persistent (survive restarts) or timed, and expire on their own.
8. A transfer shows the current file or folder, speed, ETA and overall progress. It **resumes** after any interruption.
9. A single static binary with the UI and every library embedded.

### 1.2 Non-Goals (v1)
* Internet or NAT-traversal transfer. Reachability beyond the LAN is limited to VPN or an IP typed in by hand.
* Mobile apps.
* At-rest encryption of received files.
* Sync, versioning or conflict resolution. This is transfer only.
* Relays or accounts of any kind.

---

## 2. Architecture

One process, four modules, plus a local UI.

```
+--------------------------------------------------------------------+
|                         lanyard (single binary)                    |
|                                                                    |
|  +-------------+   +--------------+   +-------------------------+  |
|  | Discovery   |   | Peer Service |   | Local UI server         |  |
|  | mDNS +      |   | HTTPS :PORT  |   | 127.0.0.1:UIPORT        |  |
|  | UDP fallback|   | mTLS 1.3     |   | embedded SPA + REST/WS  |  |
|  +------+------+   +------+-------+   +------------+------------+  |
|         |                 |                        |               |
|         +--------+--------+------------------------+               |
|                  v                                                 |
|   +---------------------------------------------------------+      |
|   | Core: Identity | Trust store | Share manager | Transfer |      |
|   |       engine (resume, progress) | Config store (JSON)   |      |
|   +---------------------------------------------------------+      |
+--------------------------------------------------------------------+
            ^                                   |
            |  mutual TLS 1.3, HTTP/2           v
      other LANyard instances             user's browser (loopback)
```

* **Peer Service:** the network face. It listens on one TCP port (default `47800`, falls back to a random free port) and speaks HTTP/2 over mutual TLS 1.3.
* **Local UI server:** a separate listener bound to `127.0.0.1` only. It serves the embedded web UI and a REST/WebSocket API for the UI. It is never reachable from the network.
* **No cgo.** Build with `CGO_ENABLED=0`. Everything is Go standard library plus a few pure-Go modules.

### 2.1 User interfaces
* **Default:** run `lanyard` and it starts the services, then opens the UI in the default browser (`--no-browser` to skip it). A tray icon is optional for a later version.
* **CLI** for headless use, using the same core, e.g. `lanyard peers`, `lanyard share add <path> --for 1h`, `lanyard get <peer> <share> [path]`, `lanyard pair <peer>`, `lanyard settings set theme dark`, `lanyard mount add <peer> Z:`, `lanyard shares cancel-all`. (Implemented: `peers`, `share add`, `get`, `settings get|set`, `shares cancel-all`; the CLI talks to the running instance over its loopback API using the token in `run.json`.)
* Only one instance per user account. A second launch just opens the UI of the running one (lock file or loopback port probe).

---

## 3. Identity and Trust

### 3.1 Identity
On first run, generate:
* an **Ed25519 keypair**, and
* a **self-signed X.509 certificate** (10-year validity, CN = device name, the key used for TLS).

The **certificate fingerprint** (SHA-256 of the public key, shown as hex in groups `7F89A2BC…`) is the cryptographic identity: nobody can claim it without the private key. The **Device ID** shown in the UI, discovery and invite strings is a user-definable unique label (default: a generated value); the trust verifier always keys on the fingerprint. See §11.1.

Storage: config directory (`%APPDATA%\LANyard`, `~/Library/Application Support/LANyard`, `~/.config/lanyard`), private key file mode `0600` (user-only ACL on Windows). OS keystore integration is future work.

### 3.2 Trust store
A list of `{device_id, name, cert_fingerprint, mode: paired, permissions, created_at}`. A device is trusted only if its certificate fingerprint matches an entry. Names are labels, never identity.

### 3.3 Mutual TLS
* TLS 1.3 minimum. Both sides present their certificate and require the peer's.
* Standard CA verification is replaced by a custom verifier that accepts only:
  * a certificate whose fingerprint is in the trust store (paired), or
  * a certificate bound to a live Connect session (see §4.2), or
  * during pairing/connect handshakes only, any certificate, but limited to the handshake endpoints.
* The result is that all other endpoints are reachable only by an authenticated, authorized peer. Encryption, integrity, forward secrecy and replay protection all come from TLS. No custom crypto is written.

> **Implementation note (M4):** because `net/http` performs the TLS handshake once per connection but authorization per request, the handshake uses `RequireAnyClientCert` so the peer fingerprint can be read, and an **Authorizer** enforces the rules above on every endpoint. `GET /hello` and `POST /session/request` are open; every other endpoint requires either a paired fingerprint with the matching permission (pull needs `browse`, push needs `push`) or a live Connect session whose offered shares include the one requested. The client additionally **pins** the peer's presented certificate to the expected fingerprint on every request, so a swapped peer is rejected even at the data layer.

### 3.4 Short Authentication String (SAS)
To defeat a man-in-the-middle at first contact:

```
SAS = decimal6( SHA-256( sort(fpA, fpB) || nonceA || nonceB ) )
```

Both devices show the same 6-digit code, and the users confirm it matches ("Does Bob's screen say 482 913?"). A man-in-the-middle would have to present different certificates to each side, and so would produce different codes.

---

## 4. Transfer Modes

### 4.1 Common rules
* Both parties can be sender and receiver. "Share" means *I let you pull from me*. "Push" means *I send to your inbox*.
* The device list shows only instances that answered discovery. Each card shows name, OS, address, state (`Unpaired`, `Connected`, `Paired`, `Offline`), and a **Connect** or **Browse** button.

### 4.2 Connect mode (one-off, human-approved)
For a quick single transfer with someone whose device you do not want to keep trusting.

1. A clicks **Connect** on B's card, opening a TLS connection to B.
2. Both screens show the same SAS code. B must **Accept** and A must confirm the code.
3. A **Connect session** is created. It binds both certificate fingerprints, held in memory only. Nothing is written to the trust store.
4. Within the session:
   * **No browsing.** A connected (unpaired) peer sees only the specific file or folder that the sharer **offers into this session**, and can open it if it is a folder. They never see the sharer's list of shares.
   * **Pull:** the downloader picks files or folders. No further prompts.
   * **Push:** the receiver gets an Accept/Reject prompt showing sender, file name(s) and size, per transfer.
     *Implementation:* the sender's `POST /push/offer` is held open (up to 2 minutes) while the receiver's UI shows who is sending, how many files and how large. Declined, unanswered or abandoned offers fail the sender's job with a clear message and write nothing. An accepted transfer is remembered for 30 minutes so a resume or reconnect of the same files is not asked again, and a peer can have at most 3 prompts waiting. The same prompt serves paired peers above their "ask for files larger than N" limit.
5. **End of session:** by default the session closes automatically when the transfer completes ("single transfer"). A checkbox, **Keep connected**, holds it open until either side closes it, the app exits, or 15 minutes of inactivity pass.
   *Implementation:* when a transfer in either direction finishes (after a one-time share's completion report, §6.2), the device that ran it ends its side of the Connect session and tells the peer, which closes too unless it chose Keep connected. A declined or failed transfer does not end the session.
6. An interrupted transfer may resume as long as the session is still alive. After a reconnect with the same certificates and a fresh SAS, a transfer may resume.

### 4.3 Paired mode (persistent, unattended)
For machines you own or trust long-term.

1. Pairing uses the same SAS flow as Connect, plus a permission screen on each side:
   * **Let them browse and pull my shares**: yes/no
   * **Let them push files to me** → destination "Inbox" directory, with an optional maximum size and an optional "ask for files larger than N"
   * **Auto-download**: optionally pick a remote share and a local folder, and new or changed files are pulled automatically (v1.1).
2. On accept, each side stores the other's certificate fingerprint and the permissions in the trust store. Permissions are per direction and independent for each side, and can be changed later from Settings (browse / push / ask-over / maximum size) without re-pairing.
3. From then on, connections are authenticated by certificate alone. There are no prompts for allowed actions, and a transfer from a paired peer starts immediately.
4. **Revocation:** *Unpair* removes the entry. Active connections from that peer are closed immediately and future ones are rejected.
5. Anything not permitted (for example a push when only pulling is allowed) is rejected with `403`, and the sender sees the reason.

### 4.4 Inbox safety (push)
* Files land in the Inbox directory only. The sender cannot choose paths outside it, and sub-folders are created inside it.
* Never overwrite: a name collision becomes `name (1).ext`.
* Check free disk space before accepting. Enforce the max-size limit.
* Incoming data is written to `.lanpart` and renamed on verified completion.

---

## 5. Discovery

### 5.1 Primary: mDNS / DNS-SD
* Service type `_lanyard._tcp.local.`, instance name = certificate-fingerprint prefix + device name.
* Multicast `224.0.0.251` and `ff02::fb`, port 5353.
* TXT keys, each well under 255 bytes:

| Key | Meaning |
| :--- | :--- |
| `v` | Protocol version (`2`) |
| `id` | First 16 hex chars of the **certificate fingerprint** (authoritative; verified by TLS) |
| `did` | User-definable **Device ID** label (first 16 chars; falls back to `id` when unset) |
| `n` | Device name |
| `os` | `windows`, `darwin`, `linux` |
| `p` | Peer Service port |

* **Self-filter by certificate fingerprint**, never by hostname or by the Device ID label.
* **Active query on launch.** Send a query as soon as the app starts, so devices already running answer immediately. That is the "answer the call" behaviour. Then announce again at 1 s, 3 s, 9 s, then follow standard TTLs.
* **Goodbye packet** (TTL 0) on clean exit, so peers disappear at once.
* **Eviction:** if no refresh is seen for 2× the TTL, or a direct TCP dial fails twice, mark Offline, then remove after a grace period.
* Use all non-loopback, up interfaces. Re-bind when interfaces change (Wi-Fi roaming, VPN up/down).

### 5.2 Fallback: UDP broadcast beacon
Many networks block mDNS (Wi-Fi client isolation, corporate firewalls, some VPNs, Windows profile "Public"). Add a second discovery path: a small JSON beacon, the same fields as the TXT record, sent to the subnet broadcast address on UDP `47801` every 5 s and in reply to a probe packet. Both paths feed the same `PeerRegistry` and are de-duplicated by certificate fingerprint.

### 5.3 Manual connect
**Add device by address** (`192.168.1.20:47800` or hostname). Also, an **invite string** (`lanyard://host:port/fingerprint`) can be copied and pasted or shown as text. This covers VPNs, other subnets and anything that drops multicast.

### 5.4 What discovery does and does not reveal
Discovery reveals only name, OS and port. **Share names and contents are never announced**. They are fetched over the authenticated connection and only if the peer is allowed to see them. The earlier idea of shares being "broadcast" is implemented as: *once connected or paired, the remote peer's share list is shown to you*, and it updates live over a WebSocket or poll.

---

## 6. Shares

### 6.1 Model
```json
{
  "share_id": "s_9f2c…",
  "path": "D:\\Projects\\Renders",
  "kind": "folder",
  "label": "Renders",
  "visibility": "paired | specific",   // plus per-session offers to unpaired peers (no standing visibility)
  "allowed_devices": ["<device_id>"],
  "lifetime": {
    "type": "persistent | until_stopped | timed | one_time",
    "duration_sec": 3600,
    "expires_at": "2026-10-04T18:30:00Z"
  },
  "created_at": "…",
  "options": { "include_hidden": false, "follow_symlinks": false }
}
```

* A share is **one file or one folder**. A user can have many shares.
* **Add** by file picker, drag and drop onto the UI, or CLI.
* **Paired** peers see every share that is currently active and open to paired peers (or to them specifically), and can browse inside those shares only. Stopped or expired shares vanish from the list immediately.
* **Unpaired** (Connect) peers see only what is explicitly offered to them in the session.
* Remote peers only ever see `share_id`, `label` and relative paths. Absolute local paths never leave the machine.

### 6.2 Lifetimes
| Type | Behaviour |
| :--- | :--- |
| `persistent` | Saved to config. Survives restarts and stays shared until removed. |
| `until_stopped` | Active until the user stops it or the app exits. Not restored on restart. |
| `timed` | Dropdown presets **5 min, 15 min, 30 min, 1 h, 2 h, 3 h, 6 h, 12 h, 24 h**, or **Custom**: a number plus a unit selector (**Seconds / Minutes / Hours**), minimum 10 seconds, maximum 30 days. Stores an **absolute `expires_at`** and counts down in the UI. Not restored if it has expired while the app was closed. |
| `one_time` | Consumed by the first **fully completed and verified** download by one peer, then removed. Interrupted attempts can resume and do not consume it. It also has a safety expiry (default 24 h). |

Rules at expiry:
* New requests for the share get `410 Gone` immediately.
* **Transfers in progress are allowed to finish** (grace up to 10 min) so users are not cut off at 99%. The sharer can choose **Stop now** to end them instantly.
* A download that was interrupted **before** expiry can't be resumed **after** it, which the receiver sees as "share expired".
* Expiry is enforced by a scheduler, plus a check on every request, so a missed timer can't leave a share open.
* **"In progress" means** a peer with a stream being served right now, or one that moved file data within the last 60 s (long enough to cover retry back-off). Only the data endpoints (`file`, `hash`, `complete`) honour the grace period; listing, tree and manifest requests get `410` at once. Grace is capped at 10 minutes and is also cut short by **Stop now**, which cancels open streams.
* **Answers after the end:** a peer that used the share gets `410` with the reason ("This share has expired.", "This one-time share has already been downloaded.", "The sender stopped this share."). A peer that never used it gets `404`, so shares cannot be probed. The reason is remembered for one hour.
* **One-time consumption** happens only through `POST /shares/{id}/complete` (see §7) after the receiver has verified every file. Paused, interrupted or failed jobs never consume it. If the sender is unreachable at that moment, the receiver keeps the report pending and retries when the sender reappears. Files that were skipped because an identical copy already existed carry no fresh digest, so a download made entirely of such files does not consume the share.
* Across restarts, `timed` and `one_time` shares come back only while unexpired, `until_stopped` shares never come back, and expired or consumed entries are removed from the saved config.
* **Cancel all shares** (§11.3) is a hard stop and the one exception to the grace rule: every share is invalidated at once and in-progress transfers are ended immediately.

### 6.3 Live, not snapshot
Folder listings are read live. Files added later appear on the next browse. A file changed during a transfer is detected through its validator (§8.2).

### 6.4 Folder and file selection on the receiving side
The browse view shows the remote tree with checkboxes. The user can select the whole share, a sub-folder, or individual files, then **Download** into a chosen local folder.

---

## 7. Peer API

HTTP/2 over mutual TLS. All paths are under `/api/v1`. JSON unless noted. Authorization is by certificate fingerprint, never by a bearer token.

| Endpoint | Auth | Purpose |
| :--- | :--- | :--- |
| `GET /hello` | none (reachable before auth) | `{device_id, fingerprint, name, os, version}` only. Used by dialing and the invite flow. |
| `POST /session/request` | any cert | Starts Connect or Pair. Body: `{mode, name, nonce, requested_permissions}`. Returns `pending`. |
| `GET /session/{id}` | any cert (the requester) | Poll or long-poll for `accepted / rejected / expired`, plus the responder's nonce for the SAS. |
| `POST /session/{id}/confirm` | any cert (the requester) | The requester confirms the SAS matches; activates the session and (for Pair) stores the initiator's trust entry. |
| `POST /session/{id}/close` | session peer | Ends a Connect session. |
| `GET /shares` | paired or session | List shares visible to this peer (label, kind, size, expiry). |
| `GET /shares/{id}/tree?path=` | paired or session | One directory level: name, `is_dir`, size, mtime. Lazy, paginated. |
| `GET /shares/{id}/manifest?path=` | paired or session | Recursive file list under a path with sizes, mtimes, and the validator, for planning a folder download and resume. |
| `GET /shares/{id}/file?path=` | paired or session | The file bytes. Supports `Range`, `If-Range`, `ETag`. |
| `HEAD /shares/{id}/file?path=` | paired or session | Size and validator without the body. |
| `GET /shares/{id}/hash?path=` | paired or session | `{sha256, size, etag}` for the whole file, cached per share, path and validator. Used to verify resumed downloads, because `206` responses carry no whole-file trailer. |
| `POST /shares/{id}/complete` | paired or session | The receiver reports a finished, hash-verified download: `{files:[{path, sha256}]}`. The sender re-checks every digest against the files themselves and only then consumes a **one-time** share; for any other share it is a no-op. Wrong or unknown digests return `409` and leave the share alone. |
| `GET /shares/{id}/archive?path=` | paired or session | Optional streaming zip. **Not resumable.** UI marks it "fast for many tiny files". |
| `POST /push/offer` | paired (push allowed) or session | Offer `{files:[{rel_path,size,mtime}]}`. Connect: receiver prompts. Paired: auto-accepted if permitted. Returns `push_id`, per-file offsets already held. | The offer lists every file of a folder (up to 500,000 files / 128 MB of JSON); there is no default size cap beyond free space, and a paired peer's optional maximum applies.
| `PUT /push/{push_id}/file?path=` | holder of `push_id` | Upload with `Content-Range`. Supports resume. **Small-file fast path:** with an `X-Lanyard-SHA256` header and a body starting at offset 0, the receiver hashes while writing, checks size and digest, and finalizes the file in the same request (no `complete` call). Senders use it for files under 512 KB. |
| `POST /push/{push_id}/complete` | holder of `push_id` | Verify and finalize. |

Accepting a request, rejecting it, and choosing which shares to offer into a Connect session are **local UI actions** on the responder, not peer endpoints; the requester learns the outcome by polling `GET /session/{id}`.

Errors: `401` no/unknown cert, `403` not permitted, `404`, `410` share expired or stopped, `416` bad range, `409` source file changed, `507` insufficient storage. Rate-limit the unauthenticated endpoints (`/hello`, `/session/request`) and cap pending requests per source so prompts cannot be flooded.

(A full OpenAPI document is produced alongside the code and kept in the repository. The old v1 YAML contained mistakes, such as `headers` placed directly under an operation, which isn't valid for OpenAPI 3.0.)

---

## 8. Transfer Engine, Progress and Resume

### 8.1 Plan
Downloading a folder or a multi-file selection:
1. Fetch `manifest`. Compute total files and bytes.
2. Create the directory skeleton locally.
3. Transfer files with a worker pool (default 3 concurrent files; for very large single files, one stream is enough on a LAN). Multiplexing over one HTTP/2 connection avoids per-file TLS setup, which keeps thousands of small files fast.
4. Preserve the modification time on completion.
5. **Name collisions on pull:** an existing file at the destination is never overwritten. A different file there sends the download to `name (1).ext` (then `(2)`, …). The chosen name is saved with the job so a resume keeps it. A file already present with the same size and modification time counts as downloaded, so repeating a download does not create duplicates. This matches the push Inbox rule (§4.4).
6. **Many small files:** files under 512 KB are latency-bound, so up to 16 of them are in flight at once (large files keep the per-job limit of 3 and a global limit of 4). A small file verified by its hash trailer skips the extra "all bytes arrived" marker and the fsync (a file that a crash truncated fails the size check on resume and is fetched again). Big jobs save their state less often (every `files/10000` seconds, at most one minute), and the UI receives only a window of at most 200 rows starting at the first unfinished file plus the totals. Measured on a laptop over loopback: 200,000 files of 100 B-1 KB in 3 minutes (about 1,100 files/s), before: roughly 260 files/s. Pushes use the same approach: up to 16 small files uploaded in parallel, one request each; 200,000 files pushed in 2 min 42 s (about 1,230 files/s), where the first version managed 133 files/s and could not push more than about 8,000 files at all.
7. **Free space:** before a download starts or resumes, the bytes still needed plus 64 MB are compared with the free space on the destination volume; if they do not fit the job fails at once with a message and writes nothing, and Resume works after space is freed.

### 8.2 Resume design
* Each in-flight file is written to `<name>.lanpart`, with a sidecar `<name>.lanstate` (JSON): `{share_id, peer_id, remote_path, size, etag/validator, bytes_committed, sha256_state?}`.
* **Validator:** `ETag` = hash of `(size, mtime_ns, inode/file-id)`. It is sent in `If-Range` when resuming.
  * Match → the server answers `206`, from the offset.
  * Source changed → the server answers `200` with the full body, or the client sees an `ETag` mismatch → **restart that file from byte 0** and say so in the UI.
* `bytes_committed` is flushed periodically (every ~4 MB or 1 s) and on shutdown. On resume, the client **trusts only what it has committed**, truncates the `.lanpart` to that length, and requests `Range: bytes=N-`.
* **Integrity:** the sender computes SHA-256 while streaming and sends it in an HTTP trailer (`X-Content-SHA256`); the receiver hashes as it writes. On a full `200` response the digest comes from the trailer; after a resume (`206`) the receiver re-hashes the committed prefix, continues the same hash over the new bytes, and compares against `GET …/hash`. The validator is sent quoted (`If-Range: "…"`), exactly as the server emits it. On mismatch, the file is discarded and retried once; a source that changed (validator or size differs) restarts that file from byte 0, at most three times. TLS protects the wire, but the hash catches disk faults and a source modified mid-transfer.
* **Completion:** fsync, atomic rename to the final name, delete the sidecar.
* **Folder-level resume:** the client persists the job (`manifest`, per-file status: `pending / partial / done`). After a crash, network drop, sleep or app restart, **Resume** continues at the first unfinished file. Already-`done` files are skipped when size and mtime match.
* **Retry classification:** network errors, truncated bodies and `5xx/408/429` are retried; `401/403/404/410`, local disk errors and repeated checksum failures fail the job immediately. Progress resets the back-off. A global cap of 4 files in flight applies across all jobs.
* **Automatic retry** with exponential back-off (1 s → 30 s), for example on Wi-Fi drops, and the peer reappearing in discovery triggers an immediate retry. After N minutes, the job moves to `Paused (waiting for peer)` and stays resumable from the UI indefinitely.
* **Pushes** resume the same way, in reverse: `push/offer` returns per-file committed offsets and the sender continues with `Content-Range`.
* **Pause / Resume / Cancel** are explicit controls. Cancel offers "keep partial files" or "delete".

### 8.3 Progress and metrics
Both sides see the transfer. The receiver drives the UI. The sender sees the incoming-request status in its own panel.

Per job: 
* **Current item:** file name and its folder path, e.g. `Renders/Shot_04/frame_0231.exr`, index "file 42 of 380".
* **Per-file:** bytes done / size, percent.
* **Overall:** bytes done / total, percent, files done / total.
* **Speed:** an exponentially smoothed average (window ~5 s) with instantaneous speed as the tooltip. Displayed in the unit chosen in Settings (§11): **MB/s** (default) or **Mbps**; all internal math is in bytes and only the label conversion changes.
* **ETA:** `remaining_bytes / smoothed_speed`, clamped and hidden for the first 2 s, and shown as `--` when speed is ~0.
* **State:** `Queued | Connecting | Transferring | Paused | Waiting for peer | Verifying | Done | Failed`.
* **Security badge:** peer name, verified-fingerprint indicator, "TLS 1.3 · mutual auth".
* Delivered to the UI via a WebSocket at ≤ 4 updates/s to keep the UI light on thousands of files.

### 8.4 Performance notes
* 256 KB copy buffers, `io.CopyBuffer`; `sendfile`-style zero-copy isn't possible over TLS, but AES-NI makes TLS 1.3 fast, and gigabit LAN is the expected bottleneck.
* Cap total concurrent transfers (default 4) and optional bandwidth limit.

---

## 9. Security Requirements

### 9.1 Path handling (replaces v1 §8.2)
* Each share is opened as an **`os.Root`** (Go ≥ 1.24), so every access is confined to the share root, **including symlink and `..` escapes**, enforced by the OS-level resolution rather than by string checks.
* Client-supplied paths: forward slashes only, reject absolute paths, drive letters, `\\`, NUL, and any `..` segment *before* hitting the filesystem. 
* **Do not** use a `strings.HasPrefix(rel, "..")` test: it rejects valid names such as `..notes.txt`.
* Symlinks are not followed by default (`follow_symlinks: false`). If enabled, the target must still resolve inside the root.
* On **receive**, sanitize each remote-supplied name: strip control characters; reject Windows reserved names (`CON`, `NUL`, `COM1`…), trailing dots and spaces, and NTFS alternate data streams (`:`); handle case-insensitive collisions; enforce path length limits (use the `\\?\` prefix on Windows for long paths). A malicious sender must not be able to write outside the destination.
* The share root itself is never exposed. The share must be a directory or file the user explicitly selected. Warn and require confirmation when sharing a drive root, the home directory, or `.ssh`, `AppData` and similar.

### 9.2 Network
* Peer listener binds to LAN interfaces. The UI listener binds to **127.0.0.1 only**.
* **Local UI protection:** a random per-launch token (in the URL it opens, then moved to a cookie), a strict `Host` header check (blocks DNS rebinding) and an `Origin` check on state-changing requests (blocks CSRF from web pages in the user's browser). The token is never logged.
* **Mounted-drive endpoint:** the loopback WebDAV proxy (§11.2) is bound to `127.0.0.1` only, requires a random per-mount secret in the URL path, and is torn down when the mount is removed. The secret is never logged.
* Pairing and Connect prompts come from an authenticated-by-cert but unapproved peer: display names and file names must be rendered as plain text (no HTML) and truncated, because they are attacker-controlled.
* Limit pending requests, request body sizes, manifest size and concurrent connections per peer.

### 9.3 Secrets and data
* No telemetry and no network calls except to peers on the local network. No auto-update in v1.
* Private key is created with restrictive permissions and never leaves the device. The trust store contains only public fingerprints.
* The Windows Firewall prompt is expected on first run. The app explains this in the UI, and the docs cover manual rules.

---

## 10. User Interface

Single-page app embedded with `go:embed`. **No CDNs, no external fonts or scripts**, so it works on an offline LAN. Built with Svelte (small output) or plain TypeScript, compiled at build time, and the compiled assets are what is embedded.

```
+-------------------------------------------------------------------------+
| LANyard   This device: Alice-PC [rename]  Network: Home_WiFi [Settings] |
+-------------------------------------------------------------------------+
| NEARBY DEVICES                       | MY SHARES                [+ Add]  |
| +------------------+ +------------+  | +-------------------------------+ |
| | Bob-Desktop      | | Studio-Mac |  | | Renders/   folder  Persistent | |
| | Windows · Paired | | macOS      |  | | notes.pdf  file    expires 42m| |
| | [Browse] [Push]  | | Unpaired   |  | | Assets/    folder  One-time   | |
| |                  | | [Connect]  |  | |        [Stop] [Edit] [...]    | |
| |                  | | [Pair]     |  | +-------------------------------+ |
| +------------------+ +------------+  |                                   |
+-------------------------------------------------------------------------+
| TRANSFERS                                                               |
| ↓ Renders/Shot_04/frame_0231.exr   from Bob-Desktop     file 42 / 380   |
| [==================............] 61%  ·  118 MB/s  ·  ETA 00:02:41       |
| This file: 61%    Overall: 4.2 / 17.8 GB    [Pause] [Cancel]            |
| 🔒 Verified · TLS 1.3 mutual auth                                       |
+-------------------------------------------------------------------------+
```

Screens and dialogs:
1. **Device grid:** live, with state badges and actions per state.
2. **My Shares:** add via picker or drag-and-drop; per share: lifetime selector (*One-time, 5 min, 15 min, 30 min, 1 h, 2 h, 3 h, 6 h, 12 h, 24 h, Custom (NN seconds/minutes/hours), Until I stop, Always*), visibility, countdown, Stop button.
3. **Remote browser:** the peer's shares, lazy-loaded tree, checkboxes, **Download** with destination picker.
4. **Transfers panel:** as above, with Pause/Resume/Cancel, retry state and history. Failed or paused jobs show **Resume**.
5. **Pairing/Connect dialog:** big SAS code, the peer name, "Does this match?", the permission toggles.
6. **Incoming push prompt (Connect mode):** sender, file list, total size, Accept/Reject.
7. **Settings:** a dedicated settings page (see §11), reached from the gear icon in the header, covering device name and Device ID, theme (light/dark/system), speed unit, completion sound, autostart, drive mounting, default folders, port, bandwidth limit, paired devices, and the Cancel-all-shares action.

> **Implementation (icon + tray):** the Windows binaries embed the application icon (a multi-size `.ico` via the Go linker resource object `cmd/lanyard/rsrc_windows_amd64.syso`, regenerated from `icons/icon.png` with `tools/mkicon` and `rsrc`). The same image is used as the browser favicon and the header logo, and as the **system-tray icon** (Windows): left-click opens the window, right-click shows **Open LANyard** / **Quit**; `--no-tray` disables it. macOS/Linux have no tray yet.

---

## 11. Settings

A dedicated **Settings page** in the UI (gear icon in the header), backed by `config.json` (§12) and also reachable from the CLI (`lanyard settings get|set …`). Unless noted, changes apply immediately; port changes need a restart and drive mounting connects or disconnects on the spot.

| Setting | Values | Default | Applies |
| :--- | :--- | :--- | :--- |
| Device name | text | hostname | live |
| **Device ID (unique ID)** | text, unique | generated | live |
| **Theme** | `Light` / `Dark` / `System` | `System` | live, no reload flash |
| **Speed unit** | `MB/s` / `Mbps` | `MB/s` | live |
| **Sound on transfer complete** | on / off + sound choice | off | live |
| **Start on login** (auto start with device) | on / off | off | live (writes the OS entry: HKCU Run key / LaunchAgent / XDG autostart; starts with `--no-browser`) |
| **Mount as share drive** | off / on + peer + drive letter / mount point + read-only | off | live (mount / unmount) |
| Default download folder | path | Downloads | live |
| Inbox folder | path | Inbox | live |
| Peer Service port | 1024–65535 | 47800 | restart |
| Bandwidth limit | unlimited / N MB/s | unlimited | live |
| Paired devices | list | — | view / revoke |
| **Cancel all shares** | action | — | immediate |

> **Implementation note (M6a/M6b):** the Settings page and `GET/PUT /api/settings` cover **device name, Device ID label, theme, speed unit, completion sound, start on login, default download folder, Inbox folder, peer port, bandwidth limit, paired devices and Cancel all shares**. The Device ID label is advertised in the mDNS `did` TXT key and returned by `/hello` (with `fingerprint`); changing the name or label re-announces over mDNS immediately and over the beacon on its next tick. **Mount as share drive** (§11.2) is implemented. The CLI implements `peers`, `share add`, `get`, `mount add|list|remove`, `settings get|set` and `shares cancel-all`; `pair` remains UI-only.
>
> **Hardening:** a paired device's permissions (`browse`, `push`, `ask_over`, `push_max_bytes`) can be edited after pairing from the Settings page (`POST /api/trust/{fp}/permissions`), which takes effect on the next request without re-pairing. The development trust-store bypass (`--dev-allow-all`) has been **removed**; the shipped server always authorizes through the trust store, and the end-to-end scripts pair first.

### 11.1 Device ID (unique ID)
* The user may replace the auto-generated Device ID with their own unique value. This is a **label**, not the cryptographic identity.
* The **certificate fingerprint** (§3.1) remains the identity used by the trust store and the TLS verifier, so a cloned Device ID still cannot pass mutual TLS.
* Discovery advertises the fingerprint prefix in `id` for self-filtering and the custom Device ID in `did` (§5.1); `/hello` returns both.
* Validation: 3–32 characters from `A–Z a–z 0–9 _ - .`. Reject if it collides with an entry in the trust store or with a device currently in discovery (warn and require confirmation otherwise).
* Changing it never touches the certificate, the fingerprint or existing pairings.

### 11.2 Mount as share drive
* Mounts the **contents of a paired peer** as a drive letter (Windows) or mount point (macOS/Linux). Only **paired** peers can be mounted; Connect/one-off sessions cannot.
* Pure-Go implementation: LANyard runs a loopback-only **WebDAV proxy** that maps every request onto the paired peer's authenticated `/api/v1/shares` API over the live mTLS connection (§7). The OS's own WebDAV client mounts `http://127.0.0.1:<port>/<mount-secret>/`.
  * **Windows:** WebClient redirector (`net use`); a persistent drive letter may require elevation.
  * **macOS:** `mount_webdav`.
  * **Linux:** `davfs2`. This dependency lives on the user's machine; the shipped binary stays dependency-free.
* **Implementation (M6b):** `lanyard mount add <peer> [Z:]` or the "Mount as drive" button. One loopback WebDAV server serves every mount at `http://127.0.0.1:<port>/<48-hex secret>/`; only `OPTIONS`, `GET`, `HEAD` and `PROPFIND` are accepted (everything else is `405`), the `Host` header must be loopback, and the server root answers WebClient's probes with an empty collection that never mentions a secret. Shares appear as top-level entries (a file share is the file itself); reads are `Range` requests, and the stream is opened at `open` time so a refusal surfaces as an error instead of a half-finished download. Listings are cached for 5 s. A mount disappears when the device is unpaired (checked on every request and every 5 s). Mounts are remembered in `config.json` and restored at startup. Windows mounts with `net use <letter>: <url> /persistent:no` and needs the **WebClient** service running; if it is stopped the app says so instead of failing with error 67.
* **Read-only by default.** Permissions, revocation and lifetimes are inherited: unpairing or stopping/expiring a share removes its files from the mount (or drops the mount).
* Files stream on demand with `Range`; nothing is cached to disk beyond what the OS itself does.
* The endpoint is loopback-bound and gated by a random per-mount secret in the path (§9.2); the secret is never logged.

### 11.3 Cancel all shares
* One action that immediately stops **every** share (persistent, until-stopped, timed and one-time) and invalidates all in-progress transfers in both directions.
* No expiry grace period (§6.2): transfers end at once, and new requests receive `410 Gone`.
* Partial downloads on the receiving side are left as `.lanpart` unless the user opts to delete them; they stay resumable if the share is re-added with a matching validator.
* Requires a confirmation dialog. Takes effect immediately.

---

## 12. Persistence

A single `config.json` (atomic write: temp file, fsync, rename) containing device identity metadata (including the custom Device ID), all settings (§11), the trust store, persistent shares, mount definitions, and unfinished transfer jobs. No database dependency. Certificates and the key live as files beside it. Sidecar `.lanstate` files live next to each `.lanpart`.

---

## 13. Technology and Build

* **Language:** Go (current stable, ≥ 1.24 for `os.Root`).
* **Standard library:** `crypto/tls`, `crypto/ed25519`, `crypto/x509`, `net/http` (HTTP/2 is automatic with TLS), `embed`, `os`, `encoding/json`, `log/slog`.
* **Third-party (pure Go only):**
  * an mDNS library, maintained fork of `zeroconf` (for instance `libp2p/zeroconf/v2`; verify it is still active when starting) behind a `Discoverer` interface so it can be swapped,
  * a WebSocket library for UI pushes (`coder/websocket`), or use Server-Sent Events from the standard library and avoid the dependency,
  * a native file-picker is **not** needed: the UI uses the browser's directory listing served by the loopback server (local folder chooser), so there is no cgo dependency.
* **Build:**

```bash
# Frontend first (output embedded by go:embed)
cd ui && npm ci && npm run build && cd ..

# Static binaries, ~10–15 MB each
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -trimpath -ldflags="-s -w -H=windowsgui" -o dist/lanyard-win-x64.exe ./cmd/lanyard
CGO_ENABLED=0 GOOS=darwin  GOARCH=arm64 go build -trimpath -ldflags="-s -w" -o dist/lanyard-mac-arm64 ./cmd/lanyard
CGO_ENABLED=0 GOOS=linux   GOARCH=amd64 go build -trimpath -ldflags="-s -w" -o dist/lanyard-linux-x64 ./cmd/lanyard
```

  `-H=windowsgui` removes the console window on Windows, and logs then go to a file. Provide a console build for CLI use, or detect and attach to the parent console. Windows binaries embed the app icon from `cmd/lanyard/rsrc_windows_amd64.syso` (a Go linker resource object built from `icons/icon.ico`).
* **Code signing / notarization** (Windows SmartScreen, macOS Gatekeeper) is recommended before distribution, and unsigned binaries will trigger warnings.

### 13.1 Project layout
```
cmd/lanyard/          main, flags, CLI subcommands
internal/identity/    keypair, cert, fingerprint, SAS
internal/trust/       trust store, permissions, session bindings
internal/discovery/   Discoverer interface; mdns, udp-beacon, manual; PeerRegistry
internal/peerapi/     mTLS server + client, handlers (§7)
internal/shares/      share model, lifetimes, scheduler, os.Root access
internal/transfer/    planner, workers, resume state, hashing, progress/ETA
internal/config/      atomic JSON store
internal/mount/       loopback WebDAV proxy + OS mount/unmount (§11.2)
internal/uiserver/    loopback server, token/Host/Origin checks, REST + SSE/WS
ui/                   SPA source; ui/dist is embedded
```

---

## 14. Test Plan

* **Unit:** SAS determinism; lifetime state machine (including expiry across restart); path sanitiser (traversal, symlink, reserved names, ADS, `..name`); ETA/speed smoothing.
* **Integration (two instances on loopback with different ports/config dirs):** discovery, connect, pair, browse, pull file, pull folder, push, revoke.
* **Resume:** kill the sender or receiver at random byte offsets (including mid-flush); drop the connection; change the source file mid-transfer; fill the disk; verify the final SHA-256 always matches.
* **Security:** unpaired cert hitting every protected endpoint (all `401/403`); expired share (`410`); MITM simulation (different certs per side give different SAS); prompt flooding; DNS-rebinding and CSRF against the UI port.
* **Settings:** Device ID uniqueness check and that changing it does not break existing pairings; theme and speed-unit persistence across restarts; completion sound; autostart entry created and removed; **Cancel all shares** immediately invalidates in-progress transfers (no grace); drive mount reads through correctly, is read-only, and drops on unpair/stop.
* **Large data:** 50 GB single file; 200 000 tiny files; unicode and very long paths.
* **Cross-platform:** Windows 11, macOS, Ubuntu; firewall on; Wi-Fi with and without mDNS (fallback beacon verifies).

---

## 15. Milestones

1. **M1 Core + discovery:** identity, mTLS, mDNS + beacon, device list in a minimal UI. *Exit: two machines see each other.*
2. **M2 Shares + pull:** share model, browse, single-file download with `Range`, progress/speed/ETA.
3. **M3 Resume + folders:** manifest, `.lanpart` state, hash verification, retries, pause/resume.
4. **M4 Connect + Pair:** SAS flow, sessions, trust store, permissions, push + Inbox.
5. **M5 Lifetimes:** timed / one-time / until-stopped / persistent, countdowns, expiry rules.
6. **M6 Settings + polish:** the dedicated settings page (§11 — Device ID, theme, speed unit, completion sound, start on login, folders, port, bandwidth, paired devices, Cancel all shares), CLI, firewall help, signed builds, docs. **Mount as share drive** (§11.2) ships with the settings page where the OS supports it; browsers-based fallback otherwise.

---

## 16. Future Work
At-rest encryption of the Inbox; OS keystore for the private key; auto-download subscriptions and folder sync; tray mode; QR-code/invite pairing; mobile clients; relay-assisted transfer beyond the LAN; auto-update.

## 17. Open Questions (defaults assumed above)
1. Default port `47800` TCP, with random free-port fallback if taken (advertised via discovery). UDP beacon `47801` is shared where possible, and disabled if unavailable. (decided)
2. Connect mode closes after one transfer by default (with a "Keep connected" option). Okay?
3. Timed-share presets and custom seconds/minutes/hours. (decided)
4. Unpaired peers see only what is offered to them; paired peers browse active shares. (decided)
5. Device ID is a user-definable unique label; the certificate fingerprint remains the cryptographic identity. (decided)
6. Mount as share drive is read-only by default; read-write is an explicit per-mount opt-in. (decided)
7. Theme defaults to `System`, completion sound to `off`, speed unit to `MB/s`; Cancel all shares is always available and bypasses the expiry grace. (decided)
