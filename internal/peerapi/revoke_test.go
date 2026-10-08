package peerapi

import (
	"bytes"
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/trust"
)

// A peer unpairing us over the peer API must run the same local cleanup as an
// unpair from our own UI, and a repeat from an already-unpaired peer must stay
// idempotent: still 200, still cleanup + logging, but marked as not-paired so
// no notification bounces back.
func TestTrustRevokeIsIdempotentAndCleansUp(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: "phone", Name: "Phone", Fingerprint: "peer-fp", Permissions: trust.Permissions{Browse: true}})

	var buf bytes.Buffer
	logger := slog.New(slog.NewTextHandler(&buf, nil))
	s := &Server{trust: tr, log: logger}
	var calls []bool
	s.SetOnRevoke(func(fp string, wasPaired bool) { calls = append(calls, wasPaired) })

	call := func() int {
		t.Helper()
		rr := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/v1/trust/revoke", nil)
		req = req.WithContext(context.WithValue(req.Context(), peerIDKey, "peer-fp"))
		s.handleTrustRevoke(rr, req)
		return rr.Code
	}

	if code := call(); code != http.StatusOK {
		t.Fatalf("first revoke status = %d, want 200", code)
	}
	if code := call(); code != http.StatusOK {
		t.Fatalf("repeat revoke status = %d, want 200 (idempotent)", code)
	}
	if len(calls) != 2 {
		t.Fatalf("onRevoke calls = %v, want 2 (cleanup must still run on repeat)", calls)
	}
	if !calls[0] {
		t.Errorf("first revoke wasPaired = false, want true")
	}
	if calls[1] {
		t.Errorf("repeat revoke wasPaired = true, want false (nothing left to notify)")
	}
	if _, ok := tr.Entry("peer-fp"); ok {
		t.Errorf("trust entry still present after revoke")
	}
	if logs := buf.String(); !strings.Contains(logs, "requested unpair") {
		t.Errorf("revoke was not logged:\n%s", logs)
	}
}
