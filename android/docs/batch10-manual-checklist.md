# Batch 10 — background transfers: manual checklist

An emulator cannot exercise Doze or the Recents swipe reliably, so these are
checked on two physical devices on the same Wi-Fi. Everything here was
implemented in Batch 10; the automatic suite covers the policy
(`ForegroundTransferPolicy`, `InterruptedTransferRetry`, `TransferBoard`), this
list covers what only a real device can prove.

## Setup (both devices)

1. Pair the two phones (Devices → scan the other's QR).
2. On the **receiver**, choose a writable download folder in Settings.
3. Keep a large file at hand (a few hundred MB, so a transfer lasts minutes).
4. Leave the screen to time out at its normal short setting (e.g. 30 s).

## 1. Screen off — send, pull and receive

For each direction:

| Direction | How to start |
|---|---|
| Send | Transfers → pick the peer → Send file |
| Pull (download) | Transfers → pick the peer → browse a share → Download |
| Receive | Send a file *to* this phone from the other device and Accept |

For each, once bytes start moving:

- [ ] Turn the screen off. The transfer keeps going (progress advances in the
      ongoing notification).
- [ ] The ongoing notification shows the direction ("Sending…" / "Receiving…")
      with a Cancel action and a progress bar.
- [ ] Turn the screen back on: the row still shows live progress; no gap.
- [ ] When it finishes, the notification is replaced by the "Transfer finished"
      one and the row reads Done.

## 2. Recents swipe-away keeps the service alive

- [ ] Start a long send (and separately a pull) and let it get going.
- [ ] Swipe the app away from Recents.
- [ ] The transfer **keeps running**: the ongoing notification stays, progress
      advances, and it finishes. (The row is still there when the app is
      reopened, reading Done.)
- [ ] Nothing is cancelled by the swipe; no "Cancelled" row appears.

## 3. Back never cancels

- [ ] Start a transfer, open the Transfers tab, and press system Back.
- [ ] The transfer is **not** cancelled. It keeps running (the foreground
      service and its notification stay).
- [ ] Cancel only happens from the explicit Cancel action (row button or the
      notification action).

## 4. Doze

Force Doze with the screen off and the device still (do not move it, keep it
unplugged so it can enter Doze):

- [ ] Start a long transfer, turn the screen off, set the phone down, and leave
      it untouched for at least 15–30 minutes.
- [ ] The transfer keeps making progress across the whole window. The partial
      file grows.
- [ ] Optionally verify Doze is active: `adb shell dumpsys deviceidle` (state
      `IDLE`) or `adb shell dumpsys battery` on the sending side.
- [ ] If the transfer did stall, note it and check the Troubleshoot report; the
      wake + Wi-Fi locks should have prevented it.

## 5. Interrupted → "Interrupted – will resume" → automatic resume

### 5a. Network change (host stays up)

- [ ] Start a long transfer.
- [ ] Toggle the **transferring** phone's Wi-Fi off, wait ~10 s, then back on.
- [ ] Within the app, the row flips to **"Interrupted – will resume"** (Failed
      styling, but clearly not lost).
- [ ] Once the peer is reachable again (same network), the row returns to live
      progress by itself, resuming from the bytes already on disk (the progress
      jumps to where it stopped, not back to 0). No re-selecting the file.
- [ ] A pull resumes from its `.part`; a receive resumes from its `.lanpart`.

### 5b. Process death

- [ ] Start a long pull (download).
- [ ] Kill the app process (`adb shell am force-stop <pkg>`).
- [ ] Reopen the app: the row reads **"Interrupted – will resume"**.
- [ ] With the peer reachable, the pull restarts from its `.part` on its own.

### 5c. Receive side

- [ ] A receive that drops keeps its `.lanpart`. Re-offer the same file from the
      sender: it resumes from the partial size (the sender does not re-send the
      bytes already received).

## 6. Battery-optimization warning

- [ ] Troubleshoot screen → the "Battery optimization" check warns when the app
      is restricted, and reads Ok once LANyard is set to Unrestricted.
- [ ] With the restriction in place, the Doze test (section 4) may still be
      paused by the OS; the warning is the honest signal, the locks are the
      mitigation.

## Known limits (honest notes)

- A **receive** cannot be restarted by the receiver alone: its `.lanpart` is
  kept and the resume happens when the sender retries (the peer is "reachable"
  again). The row still says "Interrupted – will resume" and is cleared when a
  fresh push from that peer supersedes it.
- A **send** resumes automatically while the app process is alive (the file
  handles are in memory). After a full process death a send is marked
  "Interrupted – will resume" and resumes when the person re-initiates it; a
  pull does resume on its own because its recipe is persisted.
