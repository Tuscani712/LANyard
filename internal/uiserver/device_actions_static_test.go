package uiserver

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"testing"
)

// The device page renders its controls from deviceActions. This runs the real
// function under node and pins the canonical order, section, labels and the
// disabled-with-reason decisions so a control can never silently become
// clickable when the device is offline or lacks the permission.
func TestAppJSDeviceActionGating(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed; skipping JS-level device action check")
	}
	js := readAppJS(t)
	block := extractBlock(t, js,
		"// ---- device action gating (extracted verbatim by device_actions_static_test.go) ----",
		"// ---- end device action gating ----")

	script := block + `
const out = {
  paired: deviceActions({ paired: true, online: true, allowsPush: true, allowsBrowse: true, allowsText: true, mounts: true }),
  offline: deviceActions({ paired: true, online: false, allowsPush: true, allowsBrowse: true, allowsText: true, mounts: true }),
  noPush: deviceActions({ paired: true, online: true, allowsPush: false, allowsBrowse: true, allowsText: true, mounts: true }),
  noBrowse: deviceActions({ paired: true, online: true, allowsPush: true, allowsBrowse: false, allowsText: true, mounts: true }),
  noText: deviceActions({ paired: true, online: true, allowsPush: true, allowsBrowse: true, allowsText: false, mounts: true }),
  noMount: deviceActions({ paired: true, online: true, allowsPush: true, allowsBrowse: true, allowsText: true, mounts: false }),
  session: deviceActions({ session: true, online: true, allowsPush: true, allowsBrowse: true, allowsText: true, mounts: true }),
  discover: deviceActions({ online: false }),
};
console.log(JSON.stringify(out));
`
	dir := t.TempDir()
	file := filepath.Join(dir, "device_actions.mjs")
	if err := os.WriteFile(file, []byte(script), 0o600); err != nil {
		t.Fatalf("write node script: %v", err)
	}
	raw := runNode(t, node, file)

	type act struct {
		Key     string `json:"key"`
		Label   string `json:"label"`
		Section string `json:"section"`
		Enabled bool   `json:"enabled"`
		Reason  string `json:"reason"`
	}
	var got map[string][]act
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("parse node output %q: %v", raw, err)
	}

	labels := func(k string) []string {
		out := make([]string, 0, len(got[k]))
		for _, a := range got[k] {
			out = append(out, a.Label)
		}
		return out
	}
	equal := func(gotL, want []string) bool {
		if len(gotL) != len(want) {
			return false
		}
		for i := range want {
			if gotL[i] != want[i] {
				return false
			}
		}
		return true
	}
	action := func(k, key string) act {
		for _, a := range got[k] {
			if a.Key == key {
				return a
			}
		}
		t.Fatalf("%s has no action %q", k, key)
		return act{}
	}
	sections := func(k string) map[string]string {
		out := map[string]string{}
		for _, a := range got[k] {
			out[a.Key] = a.Section
		}
		return out
	}

	// Canonical order and labels for a paired, online device with all grants.
	wantPaired := []string{"Send files\u2026", "Send folder\u2026", "Send text", "Browse their shares", "Mount as drive", "Rename\u2026", "Unpair this device"}
	if gotL := labels("paired"); !equal(gotL, wantPaired) {
		t.Errorf("paired labels = %v, want %v", gotL, wantPaired)
	}
	for _, a := range got["paired"] {
		if !a.Enabled {
			t.Errorf("paired %s should be enabled, reason %q", a.Key, a.Reason)
		}
	}
	sec := sections("paired")
	for _, k := range []string{"send-files", "send-folder", "send-text", "browse-shares"} {
		if sec[k] != "Send" {
			t.Errorf("%s section = %q, want Send", k, sec[k])
		}
	}
	for _, k := range []string{"mount", "rename", "unpair"} {
		if sec[k] != "Manage" {
			t.Errorf("%s section = %q, want Manage", k, sec[k])
		}
	}

	// Offline: send/browse controls stay visible but disabled with the reason;
	// the local manage controls stay enabled.
	for _, key := range []string{"send-files", "send-folder", "send-text", "browse-shares"} {
		if a := action("offline", key); a.Enabled || a.Reason != "This device is offline." {
			t.Errorf("offline %s = enabled:%v reason:%q, want disabled with the offline reason", key, a.Enabled, a.Reason)
		}
	}
	for _, key := range []string{"mount", "rename", "unpair"} {
		if a := action("offline", key); !a.Enabled {
			t.Errorf("offline %s should stay enabled, reason %q", key, a.Reason)
		}
	}

	// Missing push permission: the file send controls are disabled with the
	// push reason, but text and browsing still work.
	for _, key := range []string{"send-files", "send-folder"} {
		if a := action("noPush", key); a.Enabled || a.Reason != "This device did not allow you to send files to it." {
			t.Errorf("noPush %s = enabled:%v reason:%q, want the push reason", key, a.Enabled, a.Reason)
		}
	}
	if a := action("noPush", "send-text"); !a.Enabled {
		t.Error("send-text should stay enabled when only push is denied")
	}
	if a := action("noPush", "browse-shares"); !a.Enabled {
		t.Error("browse should stay enabled when only push is denied")
	}

	// Missing browse permission: only Browse their shares is disabled.
	if a := action("noBrowse", "browse-shares"); a.Enabled || a.Reason != "This device did not allow you to browse its shares." {
		t.Errorf("noBrowse browse = enabled:%v reason:%q, want the browse reason", a.Enabled, a.Reason)
	}
	if a := action("noBrowse", "send-files"); !a.Enabled {
		t.Error("send should stay enabled when only browse is denied")
	}

	// Missing text permission: only Send text is disabled.
	if a := action("noText", "send-text"); a.Enabled || a.Reason != "This device did not allow you to send text to it." {
		t.Errorf("noText send-text = enabled:%v reason:%q, want the text reason", a.Enabled, a.Reason)
	}

	// Mount is desktop-only: it disappears when the platform reports no support.
	for _, a := range got["noMount"] {
		if a.Key == "mount" {
			t.Error("mount should be hidden when mounts are unsupported")
		}
	}

	// A live Connect session: send + browse + Disconnect, no Rename/Unpair/Mount.
	wantSession := []string{"Send files\u2026", "Send folder\u2026", "Send text", "Browse their shares", "Disconnect"}
	if gotL := labels("session"); !equal(gotL, wantSession) {
		t.Errorf("session labels = %v, want %v", gotL, wantSession)
	}

	// An unpaired, offline device: Connect/Pair are disabled with the reason.
	for _, key := range []string{"connect", "pair"} {
		if a := action("discover", key); a.Enabled || a.Reason != "This device is offline." {
			t.Errorf("discover %s = enabled:%v reason:%q, want disabled", key, a.Enabled, a.Reason)
		}
	}
}
