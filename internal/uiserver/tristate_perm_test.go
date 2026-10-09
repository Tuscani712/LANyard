package uiserver

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/trust"
)

// The device page / Settings editor posts the tri-state modes ("allow"/"ask"/
// "never") to the same endpoint; the handler must store them verbatim.
func TestTrustPermissionsAcceptsTriState(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self", nil)
	tr.Pair(trust.Entry{DeviceID: "d", Name: "N", Fingerprint: "fp", Mode: trust.ModePair})

	s := New(Deps{Trust: tr})
	body := `{"browse":"ask","push":"never","text":"allow","ask_over":1048576,"push_max_bytes":2097152}`
	req := httptest.NewRequest(http.MethodPost, "/api/trust/fp/permissions", strings.NewReader(body))
	req.SetPathValue("fp", "fp")
	rr := httptest.NewRecorder()
	s.handleTrustPermissions(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("permissions POST = %d body %q", rr.Code, rr.Body.String())
	}
	e, ok := tr.Entry("fp")
	if !ok {
		t.Fatal("entry gone")
	}
	if !e.Permissions.Browse.Asks() || !e.Permissions.Push.Denies() || !e.Permissions.Text.Allows() {
		t.Fatalf("stored permissions = %+v, want browse ask, push never, text allow", e.Permissions)
	}
	if e.Permissions.AskOver != 1048576 || e.Permissions.PushMaxBytes != 2097152 {
		t.Fatalf("stored sizes = %+v", e.Permissions)
	}
}
