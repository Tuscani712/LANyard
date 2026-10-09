package peerapi

import (
	"context"
	"encoding/json"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/trust"
)

const testCertFP = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"

// sessionRequestServer builds a full Server with a live trust store so the
// handshake endpoint can create sessions, returning both the server and store.
func sessionRequestServer(t *testing.T) (*Server, *trust.Store) {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	s := NewServer(nil, func() discovery.Hello { return discovery.Hello{} }, nil, tr, AllowAll(), slog.Default())
	return s, tr
}

func postSessionRequest(s *Server, fp, body string) *httptest.ResponseRecorder {
	req := httptest.NewRequest(http.MethodPost, "/api/v1/session/request", strings.NewReader(body))
	req = req.WithContext(context.WithValue(req.Context(), peerIDKey, fp))
	rr := httptest.NewRecorder()
	s.handleSessionRequest(rr, req)
	return rr
}

// A body device_id equal to the caller's certificate fingerprint is accepted
// and opens an ordinary SAS pairing session (no invite => not via QR).
func TestSessionRequestDeviceIDMatchingCertCreatesPendingSAS(t *testing.T) {
	s, tr := sessionRequestServer(t)
	body := `{"mode":"pair","name":"Bob","device_id":"` + testCertFP + `","nonce":"0123456789abcdef","invite":""}`
	rr := postSessionRequest(s, testCertFP, body)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %q", rr.Code, rr.Body.String())
	}
	var resp sessionRequestResp
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	view, ok := tr.View(resp.SessionID)
	if !ok {
		t.Fatalf("no session %q created", resp.SessionID)
	}
	if view.Status != trust.StatusPending {
		t.Errorf("status = %q, want %q", view.Status, trust.StatusPending)
	}
	if view.ViaQR {
		t.Errorf("via_qr = true, want false for an empty invite")
	}
	if view.SAS == "" {
		t.Errorf("SAS is empty, want a non-empty code")
	}
}

// A body device_id that names a different certificate is refused and nothing
// is created.
func TestSessionRequestDeviceIDMismatchRefused(t *testing.T) {
	s, tr := sessionRequestServer(t)
	body := `{"mode":"pair","name":"Bob","device_id":"deadbeef","nonce":"0123456789abcdef"}`
	rr := postSessionRequest(s, testCertFP, body)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400, body = %q", rr.Code, rr.Body.String())
	}
	if got := len(tr.Sessions()); got != 0 {
		t.Fatalf("sessions = %d, want 0", got)
	}
}

// The fingerprint comparison is case-insensitive.
func TestSessionRequestDeviceIDCaseInsensitive(t *testing.T) {
	s, _ := sessionRequestServer(t)
	body := `{"mode":"pair","name":"Bob","device_id":"` + strings.ToUpper(testCertFP) + `","nonce":"0123456789abcdef"}`
	rr := postSessionRequest(s, testCertFP, body)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, body = %q", rr.Code, rr.Body.String())
	}
}
