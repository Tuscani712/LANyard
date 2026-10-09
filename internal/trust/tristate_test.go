package trust

import (
	"encoding/json"
	"fmt"
	"testing"

	"lanyard/internal/config"
)

// The wire/persisted encoding always carries the legacy booleans (true iff
// Allow) plus the tri-state modes and the marker, so an old peer reads a grant
// while a new peer trusts the modes.
func TestPermissionsWireEncoding(t *testing.T) {
	p := Permissions{Browse: Allow, Push: Ask, Text: Never, AskOver: 7, PushMaxBytes: 99}
	b, err := json.Marshal(p)
	if err != nil {
		t.Fatalf("Marshal: %v", err)
	}
	var got map[string]any
	if err := json.Unmarshal(b, &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got["browse"] != true || got["push"] != false || got["text"] != false {
		t.Errorf("legacy booleans = browse:%v push:%v text:%v, want allow-else-deny", got["browse"], got["push"], got["text"])
	}
	if got["browse_mode"] != "allow" || got["push_mode"] != "ask" || got["text_mode"] != "never" {
		t.Errorf("modes = %v/%v/%v", got["browse_mode"], got["push_mode"], got["text_mode"])
	}
	if got["perms"] != "tristate" {
		t.Errorf("marker = %v, want tristate", got["perms"])
	}

	// Round trip through the tri-state encoding preserves every state.
	var back Permissions
	if err := json.Unmarshal(b, &back); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}
	if back.Browse != Allow || back.Push != Ask || back.Text != Never || back.AskOver != 7 || back.PushMaxBytes != 99 {
		t.Fatalf("round trip = %+v", back)
	}
}

// An old peer's bool-only grant decodes as allow-else-deny: a false is a hard
// Never, never an Ask (an old build has no prompt to ask with).
func TestPermissionsLegacyBoolDecoding(t *testing.T) {
	var p Permissions
	if err := json.Unmarshal([]byte(`{"browse":true,"push":false}`), &p); err != nil {
		t.Fatalf("Unmarshal: %v", err)
	}
	if p.Browse != Allow || p.Push != Never {
		t.Fatalf("legacy decode = %+v, want browse allow, push never", p)
	}
	if !p.Text.Denies() {
		t.Fatalf("absent text should refuse, got %q", p.Text)
	}
}

// Upgrading a legacy store turns the old boolean denials into the new default
// Ask, and leaves explicit Allows alone. A store already carrying the
// tri-state marker is not migrated, so an explicit Never survives.
func TestLoadMigratesLegacyFalseToAsk(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	legacy := `[{"device_id":"d","name":"N","cert_fingerprint":"fp","mode":"pair","permissions":{"browse":false,"push":true},"peer_permissions":{"browse":true,"push":true}}]`
	if err := cfg.Update(func(st *config.Settings) { st.Trust = []byte(legacy) }); err != nil {
		t.Fatalf("seed legacy trust: %v", err)
	}
	st := New(cfg, "self", nil)
	if err := st.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	e, ok := st.Entry("fp")
	if !ok {
		t.Fatal("legacy entry not loaded")
	}
	if e.Permissions.Browse != Ask || e.Permissions.Push != Allow || e.Permissions.Text != Allow {
		t.Fatalf("migrated permissions = %+v, want ask/allow/allow", e.Permissions)
	}
	if !e.PeerPermissions.Browse.Allows() || !e.PeerPermissions.Push.Allows() {
		t.Fatalf("peer grant should stay allowed: %+v", e.PeerPermissions)
	}

	// A fresh store written back carries the marker, so a reload does not
	// re-migrate an explicit Never into Ask.
	if !st.UpdatePermissions("fp", Permissions{Browse: Ask, Push: Never, Text: Ask}) {
		t.Fatal("UpdatePermissions failed")
	}
	st2 := New(cfg, "self", nil)
	if err := st2.Load(); err != nil {
		t.Fatalf("reload: %v", err)
	}
	if e2, _ := st2.Entry("fp"); e2.Permissions.Push != Never {
		t.Fatalf("explicit Never was re-migrated: %+v", e2.Permissions)
	}
}

// A legacy entry has no text field at all. Its text permission must inherit
// the legacy push grant — an allowed push was an allowed text (Allow), a denied
// push becomes the new default Ask — not always Ask.
func TestLoadMigratesLegacyTextFromPush(t *testing.T) {
	cases := []struct {
		name     string
		push     bool
		wantText Permission
	}{
		{"push true inherits Allow", true, Allow},
		{"push false becomes Ask", false, Ask},
	}
	for _, tc := range cases {
		t.Run(tc.name, func(t *testing.T) {
			cfg, err := config.Open(t.TempDir())
			if err != nil {
				t.Fatalf("config.Open: %v", err)
			}
			legacy := fmt.Sprintf(`[{"device_id":"d","name":"N","cert_fingerprint":"fp","mode":"pair","permissions":{"browse":false,"push":%t}}]`, tc.push)
			if err := cfg.Update(func(st *config.Settings) { st.Trust = []byte(legacy) }); err != nil {
				t.Fatalf("seed legacy trust: %v", err)
			}
			st := New(cfg, "self", nil)
			if err := st.Load(); err != nil {
				t.Fatalf("Load: %v", err)
			}
			e, ok := st.Entry("fp")
			if !ok {
				t.Fatal("legacy entry not loaded")
			}
			if e.Permissions.Text != tc.wantText {
				t.Fatalf("legacy push=%v migrated text = %q, want %q", tc.push, e.Permissions.Text, tc.wantText)
			}
		})
	}
}

// Access exposes the tri-state for each action, with the unset zero value
// refusing.
func TestAccessCarriesTriState(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	st := New(cfg, "self", nil)
	st.Pair(Entry{DeviceID: "d", Name: "N", Fingerprint: "fp", Mode: ModePair,
		Permissions: Permissions{Browse: Ask, Push: Allow}})
	a := st.Access("fp")
	if !a.Browse.Asks() || !a.Push.Allows() || !a.Text.Denies() {
		t.Fatalf("access = %+v, want browse ask, push allow, text never", a)
	}
}
