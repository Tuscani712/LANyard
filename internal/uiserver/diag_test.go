package uiserver

import (
	"encoding/json"
	"net/http/httptest"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/diag"
	"lanyard/internal/discovery"
)

func TestDiagnosticsEndpointReturnsChecks(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	s := New(Deps{
		Self:  func() SelfInfo { return SelfInfo{Name: "me", DeviceID: "self", PeerPort: 47800} },
		Peers: func() []discovery.Peer { return nil },
		Cfg:   cfg,
	})
	rr := httptest.NewRecorder()
	s.handleDiagnostics(rr, httptest.NewRequest("GET", "/api/diagnostics", nil))
	if rr.Code != 200 {
		t.Fatalf("code = %d, want 200 (body %q)", rr.Code, rr.Body.String())
	}
	var resp struct {
		Checks []diag.Check `json:"checks"`
		Report string       `json:"report"`
	}
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if len(resp.Checks) != 7 {
		t.Fatalf("got %d checks, want 7", len(resp.Checks))
	}
	if resp.Report == "" {
		t.Fatal("expected a plain-text report")
	}
}

func TestDiagnosticsUnavailableWithoutConfig(t *testing.T) {
	s := New(Deps{})
	rr := httptest.NewRecorder()
	s.handleDiagnostics(rr, httptest.NewRequest("GET", "/api/diagnostics", nil))
	if rr.Code != 503 {
		t.Fatalf("code = %d, want 503", rr.Code)
	}
}
