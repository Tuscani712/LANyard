package config

import (
	"os"
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

// A replace that cannot succeed at all must return an error (and leave no temp
// file behind), never quietly discard the failure.
func TestWriteFileAtomicFinalFailureReturnsError(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	// Make the destination a non-empty directory: replacing it is impossible on
	// every platform.
	if err := os.Mkdir(path, 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(filepath.Join(path, "keep"), []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}

	if err := WriteFileAtomic(path, []byte("new"), 0o600); err == nil {
		t.Fatal("WriteFileAtomic returned nil for an impossible replace")
	}

	entries, err := os.ReadDir(dir)
	if err != nil {
		t.Fatal(err)
	}
	for _, e := range entries {
		if e.Name() != "config.json" {
			t.Errorf("temp file %q was left behind", e.Name())
		}
	}
}

// A normal replace succeeds and yields the new contents.
func TestWriteFileAtomicReplaces(t *testing.T) {
	path := filepath.Join(t.TempDir(), "config.json")
	if err := os.WriteFile(path, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := WriteFileAtomic(path, []byte("new"), 0o600); err != nil {
		t.Fatalf("WriteFileAtomic: %v", err)
	}
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "new" {
		t.Fatalf("contents = %q, want %q", got, "new")
	}
}
