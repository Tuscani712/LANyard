package uiserver

import (
	"os"
	"strings"
	"testing"
)

// The non-QR pairing dialog must show the peer's short fingerprint next to the
// SAS code, so both people can compare name + code + fingerprint on both
// screens.
func TestAppJSPairShowsPeerFingerprint(t *testing.T) {
	src, err := os.ReadFile("web/app.js")
	if err != nil {
		t.Fatalf("read app.js: %v", err)
	}
	want := `if (v.peer_fp) sasBox.appendChild(el("div", "muted", "Device fingerprint: " + v.peer_fp.slice(0, 16) + "\u2026"));`
	if !strings.Contains(string(src), want) {
		t.Errorf("app.js is missing the non-QR fingerprint line: %q", want)
	}
}
