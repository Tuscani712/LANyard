# Batch 11 — tabbed Settings: manual checklist

Batch 11 splits Settings into seven category tabs (Compose `ScrollableTabRow`).
The automatic suite covers the taxonomy and the tab scaffold:

- `:core` `SettingsCategoryTest` — reflects over every `AppSettings` property and
  asserts exactly one `SettingsCategory`, with no stale map keys.
- `:core` `SettingsStoreTest` — `save` now reports success/failure as a `Result`
  instead of throwing.
- `:app` `SettingsTabsTest` (Robolectric + Compose UI) — seven tabs exist, each
  shows its own body, the per-tab "Saved." line appears only on the changed tab,
  a save error is surfaced, the selected tab survives recreation, and Back from a
  sub-screen returns to the same tab.

What only a real device can confirm is below.

## Setup

1. Install the debug build (`./gradlew :app:assembleDebug`, `io.github.tuscani712.lanyard.debug`).
2. Pair at least one device so Pairing & Security and Troubleshoot have content.

## 1. The seven tabs

- [ ] Settings shows a scrollable tab strip with, in order: **General**,
      **Receiving**, **Network & Discovery**, **Notifications**,
      **Pairing & Security**, **Logs & Diagnostics**, **About**.
- [ ] The strip scrolls horizontally on a narrow phone; the last tab (About) is
      reachable.
- [ ] Every setting from before Batch 11 is present on exactly one tab and none
      has been lost.

## 2. Per-tab contents

- [ ] **General**: device name field, Device ID + Fingerprint (copy/share), Theme,
      Speed unit.
- [ ] **Receiving**: default download folder (choose/change/use default),
      bandwidth limit, transfer history actions (Clear history / Cancel all).
- [ ] **Network & Discovery**: Wi-Fi only, the remembered **Listener port**
      (with Reset; "Automatic" when 0), and the discovery note.
- [ ] **Notifications**: Notifications switch, Sound on complete, System
      notification permission row.
- [ ] **Pairing & Security**: paired devices with Unpair, and shared folders.
- [ ] **Logs & Diagnostics**: Troubleshoot (opens the checks), Copy log, Share log.
- [ ] **About**: version, licence/repo, Third-party licenses.

## 3. "Saved." status

- [ ] Change any setting (e.g. toggle Theme). A **"Saved."** line appears at the
      bottom of the tab you changed.
- [ ] Switch to another tab: it has no "Saved." line of its own.
- [ ] Rotate the device: settings persist and the app keeps working.
- [ ] Force a save failure (e.g. `adb shell chmod 000 /data/data/<pkg>/files`) and
      change a setting: an error line is shown instead of "Saved."; the setting
      still applies in memory.

## 4. Tabs and Back

- [ ] Select, say, **Notifications**, press Home, reopen the app: it is still on
      Notifications.
- [ ] Open **Troubleshoot** from Logs & Diagnostics, press Back: it returns to
      **Logs & Diagnostics**, not General.
- [ ] Open **Third-party licenses** from About, press Back: it returns to
      **About**.
- [ ] Rotate while inside Troubleshoot, press Back: still returns to the same
      Settings tab.
- [ ] Kill the app from Recents while on a non-first tab, reopen: the same tab is
      selected.

## 5. Auto-save unchanged

- [ ] Each change persists immediately with no Save button.
- [ ] Force-stop and reopen: the last changes are still in effect.
