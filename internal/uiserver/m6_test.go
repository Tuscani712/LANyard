package uiserver

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/config"
)

func TestValidDeviceLabel(t *testing.T) {
	good := []string{"alice", "Alice-PC", "dev_01", "a.b.c", "ABC123"}
	for _, s := range good {
		if !validDeviceLabel(s) {
			t.Errorf("%q should be valid", s)
		}
	}
	bad := []string{"", "ab", "has space", "bad/slash", "emoji😀", "this-label-is-way-too-long-to-be-accepted"}
	for _, s := range bad {
		if validDeviceLabel(s) {
			t.Errorf("%q should be invalid", s)
		}
	}
}

func TestOneOf(t *testing.T) {
	if !oneOf("dark", "light", "dark", "system") {
		t.Error("dark should match")
	}
	if oneOf("sepia", "light", "dark", "system") {
		t.Error("sepia should not match")
	}
}

func TestCleanText(t *testing.T) {
	if got := cleanText("  hi\x00 there  ", 64); got != "hi there" {
		t.Errorf("cleanText = %q", got)
	}
	if got := cleanText("abcdef", 3); got != "abc" {
		t.Errorf("cleanText truncate = %q", got)
	}
}

// settingsTestServer builds a Server wired to a temp config store whose OS
// autostart state is simulated by autostartEnabled.
func settingsTestServer(t *testing.T, cfg *config.Store, autostartEnabled *bool) *Server {
	t.Helper()
	return New(Deps{
		Self:                func() SelfInfo { return SelfInfo{Name: "me", DeviceID: "selfselfselfself", GeneratedLabel: "gen"} },
		Cfg:                 cfg,
		StartOnLoginEnabled: func() bool { return *autostartEnabled },
	})
}

// A failing SetStartOnLogin must not discard the rest of the save: the other
// requested fields are committed, start_on_login keeps its previous value, and
// the response is a 200 carrying a non-fatal warning.
func TestSettingsPutKeepsFieldsWhenStartOnLoginFails(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Update(func(st *config.Settings) {
		st.DeviceName = "tester"
		st.StartOnLogin = true
		st.MinimizeToTray = false
	}); err != nil {
		t.Fatal(err)
	}

	autostartCalls := 0
	autostartEnabled := true // the OS still has the entry, since removal failed
	s := settingsTestServer(t, cfg, &autostartEnabled)
	s.d.SetStartOnLogin = func(enable bool) error {
		autostartCalls++
		return errors.New("registry write denied")
	}

	rr := httptest.NewRecorder()
	req := httptest.NewRequest("PUT", "/api/settings", strings.NewReader(`{"minimize_to_tray":true,"start_on_login":false}`))
	s.handleSettingsPut(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("code = %d, want 200 (body %q)", rr.Code, rr.Body.String())
	}
	var resp struct {
		MinimizeToTray bool   `json:"minimize_to_tray"`
		StartOnLogin   bool   `json:"start_on_login"`
		Warning        string `json:"warning"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if autostartCalls != 1 {
		t.Fatalf("SetStartOnLogin calls = %d, want 1", autostartCalls)
	}
	if resp.Warning == "" {
		t.Fatal("expected a non-fatal warning in the response")
	}
	if !resp.MinimizeToTray {
		t.Fatal("response minimize_to_tray = false, want true persisted despite the autostart failure")
	}
	if !resp.StartOnLogin {
		t.Fatal("response start_on_login = false, want the previous value (true) retained")
	}
	got := cfg.Get()
	if !got.MinimizeToTray {
		t.Fatal("config did not persist minimize_to_tray")
	}
	if !got.StartOnLogin {
		t.Fatal("config start_on_login changed despite a failed autostart write")
	}
}

// A normal save with no autostart error is unchanged: 200, no warning, and the
// fields persist.
func TestSettingsPutNormalSaveHasNoWarning(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Update(func(st *config.Settings) { st.StartOnLogin = false }); err != nil {
		t.Fatal(err)
	}

	autostartEnabled := true // simulated OS state after a successful enable
	s := settingsTestServer(t, cfg, &autostartEnabled)
	s.d.SetStartOnLogin = func(enable bool) error { return nil }

	rr := httptest.NewRecorder()
	req := httptest.NewRequest("PUT", "/api/settings", strings.NewReader(`{"minimize_to_tray":true,"start_on_login":true}`))
	s.handleSettingsPut(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("code = %d, want 200 (body %q)", rr.Code, rr.Body.String())
	}
	var resp struct {
		MinimizeToTray bool   `json:"minimize_to_tray"`
		StartOnLogin   bool   `json:"start_on_login"`
		Warning        string `json:"warning"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.Warning != "" {
		t.Fatalf("warning = %q, want empty on a normal save", resp.Warning)
	}
	if !resp.MinimizeToTray {
		t.Fatal("minimize_to_tray did not persist")
	}
	if !cfg.Get().MinimizeToTray {
		t.Fatal("config did not persist minimize_to_tray")
	}
	if !cfg.Get().StartOnLogin {
		t.Fatal("config did not persist start_on_login")
	}
}

// A save that does not change start_on_login must not touch (or be able to fail
// on) the OS autostart files at all.
func TestSettingsPutUnchangedStartOnLoginDoesNotTouchAutostart(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	if err := cfg.Update(func(st *config.Settings) { st.StartOnLogin = true }); err != nil {
		t.Fatal(err)
	}

	autostartCalls := 0
	autostartEnabled := true
	s := settingsTestServer(t, cfg, &autostartEnabled)
	s.d.SetStartOnLogin = func(enable bool) error {
		autostartCalls++
		return errors.New("autostart must not be touched when start_on_login is unchanged")
	}

	rr := httptest.NewRecorder()
	req := httptest.NewRequest("PUT", "/api/settings", strings.NewReader(`{"minimize_to_tray":true,"start_on_login":true}`))
	s.handleSettingsPut(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("code = %d, want 200 (body %q)", rr.Code, rr.Body.String())
	}
	var resp struct {
		MinimizeToTray bool   `json:"minimize_to_tray"`
		StartOnLogin   bool   `json:"start_on_login"`
		Warning        string `json:"warning"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if autostartCalls != 0 {
		t.Fatalf("SetStartOnLogin calls = %d, want 0 when the value is unchanged", autostartCalls)
	}
	if resp.Warning != "" {
		t.Fatalf("warning = %q, want empty", resp.Warning)
	}
	if !resp.MinimizeToTray || !resp.StartOnLogin {
		t.Fatalf("response = %+v, want minimize_to_tray and start_on_login both true", resp)
	}
	if !cfg.Get().MinimizeToTray {
		t.Fatal("config did not persist minimize_to_tray")
	}
}
