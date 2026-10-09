package uiserver

import (
	"net/http"
	"net/http/httptest"
	"os"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/trust"
)

// Disconnect of a session that no longer exists is a 404; the UI treats that as
// "already gone" rather than an error.
func TestSessionCloseUnknownIs404(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	logger, _ := testLogger()
	s := New(Deps{Trust: trust.New(cfg, "self-fp", nil), Log: logger})

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/sessions/nope/close", nil)
	req.SetPathValue("id", "nope")
	s.handleSessionClose(rr, req)
	if rr.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rr.Code)
	}
}

// Disconnect both ends the session and logs what it did (and that it is only a
// temporary session, not an unpair).
func TestSessionCloseLogsWhatItDoes(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	if _, err := tr.CreateIncoming(trust.ModeConnect, "peer-fp", "Phone", "pd", "nonce-1234567890", trust.Permissions{}); err != nil {
		t.Fatalf("CreateIncoming: %v", err)
	}
	sessions := tr.Sessions()
	if len(sessions) != 1 {
		t.Fatalf("sessions = %d, want 1", len(sessions))
	}
	id := sessions[0].ID

	logger, buf := testLogger()
	s := New(Deps{Trust: tr, Log: logger})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/sessions/"+id+"/close", nil)
	req.SetPathValue("id", id)
	s.handleSessionClose(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d body = %s", rr.Code, rr.Body.String())
	}
	if _, ok := tr.Get(id); ok {
		t.Errorf("session was not closed")
	}
	logs := buf.String()
	if !strings.Contains(logs, "disconnect: ended session") {
		t.Errorf("disconnect was not logged:\n%s", logs)
	}
	if !strings.Contains(logs, "temporary connection") {
		t.Errorf("log should say this is only a temporary session:\n%s", logs)
	}
}

// The JS side must treat a 404 from Disconnect as "already gone", keep only
// Connect-mode sessions as "Connected", re-render the open device pane on a
// session change, show the peer's narrowed grant, and gate push on it.
func TestAppJSDisconnectAndNarrowedGrantWiring(t *testing.T) {
	src, err := os.ReadFile("web/app.js")
	if err != nil {
		t.Fatalf("read app.js: %v", err)
	}
	js := string(src)
	checks := []struct{ name, sub string }{
		{"activeSession requires connect mode", `s.mode === "connect"`},
		{"sessionAction treats 404 as gone", "r.status !== 404"},
		{"disconnect tooltip says it is session-only", "Ends this temporary connection only."},
		{"peer grant is gated for push", "peer_permissions"},
		{"peer grant is displayed", "They allow me:"},
		{"device pane re-renders on session change", `changed && place().kind === "device"`},
	}
	for _, c := range checks {
		if !strings.Contains(js, c.sub) {
			t.Errorf("%s: app.js is missing %q", c.name, c.sub)
		}
	}
}
