package uiserver

import (
	"encoding/json"
	"net/http/httptest"
	"testing"

	"lanyard/internal/diag"
)

// The UI needs the port the peer service actually bound, not the preferred
// one, so a fallback port can raise the firewall banner.
func TestSelfEndpointSurfacesBoundPeerPort(t *testing.T) {
	s := New(Deps{Self: func() SelfInfo {
		return SelfInfo{Name: "me", PeerPort: 47801, PeerPortRequested: 47800, PeerPortFallback: true}
	}})
	rr := httptest.NewRecorder()
	s.handleSelf(rr, httptest.NewRequest("GET", "/api/self", nil))
	if rr.Code != 200 {
		t.Fatalf("code = %d, want 200", rr.Code)
	}
	var got SelfInfo
	if err := json.Unmarshal(rr.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if got.PeerPort != 47801 {
		t.Fatalf("peer_port = %d, want the bound port 47801", got.PeerPort)
	}
}

// A non-default bound port or a firewall check that is not ok/skip must raise
// the banner condition; the default port with a clean firewall must not.
func TestFirewallBannerNeeded(t *testing.T) {
	fw := func(s diag.Status) []diag.Check { return []diag.Check{{ID: "firewall", Status: s}} }
	cases := []struct {
		name   string
		self   SelfInfo
		checks []diag.Check
		want   bool
	}{
		{"default port, firewall ok", SelfInfo{PeerPort: 47800}, fw(diag.StatusOK), false},
		{"default port, firewall skipped", SelfInfo{PeerPort: 47800}, fw(diag.StatusSkip), false},
		{"fallback port", SelfInfo{PeerPort: 47801}, fw(diag.StatusOK), true},
		{"firewall warn", SelfInfo{PeerPort: 47800}, fw(diag.StatusWarn), true},
		{"firewall fail", SelfInfo{PeerPort: 47800}, fw(diag.StatusFail), true},
		{"fallback port and firewall warn", SelfInfo{PeerPort: 47801}, fw(diag.StatusWarn), true},
	}
	for _, c := range cases {
		if got := firewallBannerNeeded(c.self, c.checks); got != c.want {
			t.Errorf("%s: firewallBannerNeeded = %v, want %v", c.name, got, c.want)
		}
	}
}
