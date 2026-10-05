package autostart

import (
	"strings"
	"testing"

	"golang.org/x/sys/windows/registry"
)

// Round-trips a private Run value so the user's real LANyard entry is untouched.
func TestWindowsRunKeyRoundTrip(t *testing.T) {
	old := valueName
	valueName = "LANyardTest-" + strings.ReplaceAll(t.Name(), "/", "_")
	defer func() { _ = Disable(); valueName = old }()

	if Enabled() {
		t.Fatal("test value should start absent")
	}
	if err := Enable(`C:\Program Files\LANyard\lanyard.exe`, "--no-browser", "--data-dir", `D:\my data`); err != nil {
		t.Fatal(err)
	}
	if !Enabled() {
		t.Fatal("entry should exist after Enable")
	}
	k, err := registry.OpenKey(registry.CURRENT_USER, runKey, registry.QUERY_VALUE)
	if err != nil {
		t.Fatal(err)
	}
	defer k.Close()
	v, _, err := k.GetStringValue(valueName)
	if err != nil {
		t.Fatal(err)
	}
	want := `"C:\Program Files\LANyard\lanyard.exe" --no-browser --data-dir "D:\my data"`
	if v != want {
		t.Errorf("command line = %s, want %s", v, want)
	}
	if err := Disable(); err != nil || Enabled() {
		t.Fatalf("Disable failed: err=%v enabled=%v", err, Enabled())
	}
	if err := Disable(); err != nil {
		t.Errorf("disabling an absent entry must not fail: %v", err)
	}
}
