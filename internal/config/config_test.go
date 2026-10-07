package config

import (
	"path/filepath"
	"testing"
)

func TestNotificationsDefaultOn(t *testing.T) {
	var s Settings
	if !s.NotificationsEnabled() {
		t.Fatal("notifications should default to on when unset")
	}
	off := false
	s.Notifications = &off
	if s.NotificationsEnabled() {
		t.Fatal("an explicit off should disable notifications")
	}
	on := true
	s.Notifications = &on
	if !s.NotificationsEnabled() {
		t.Fatal("an explicit on should enable notifications")
	}
}

// Updates must be off and unconfigured by default, so a fresh install never
// contacts the internet. The UI hides the whole Updates block until a URL is set.
func TestUpdatesOffByDefault(t *testing.T) {
	var s Settings
	if s.AutoUpdate {
		t.Fatal("AutoUpdate must default to off")
	}
	if s.UpdateURL != "" {
		t.Fatal("UpdateURL must default to empty")
	}
}

// With no Inbox folder configured, received files go to ~/LANyard (a visible
// folder next to Downloads), not a hidden directory under the data dir.
func TestDefaultInboxDir(t *testing.T) {
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("USERPROFILE", home) // Windows

	got, err := DefaultInboxDir()
	if err != nil {
		t.Fatalf("DefaultInboxDir: %v", err)
	}
	if want := filepath.Join(home, "LANyard"); got != want {
		t.Fatalf("DefaultInboxDir = %q, want %q", got, want)
	}
}
