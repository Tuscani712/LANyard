package uiserver

import (
	"encoding/json"
	"errors"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/inbox"
	"lanyard/internal/peerapi"
	"lanyard/internal/trust"
)

// A peer that speaks discovery but has no pairing service (the current Android
// app) answers 404. That is a different, actionable situation from an
// unreachable device.
func TestPairingStartMessage(t *testing.T) {
	msg, friendly := pairingStartMessage(&peerapi.StatusError{Code: http.StatusNotFound, Status: "404 Not Found", Msg: "404 page not found"})
	if !friendly {
		t.Errorf("404 should be the friendly case, got friendly=%v msg=%q", friendly, msg)
	}
	if !strings.Contains(msg, "can't accept pairing yet") {
		t.Errorf("404 message = %q", msg)
	}

	msg2, friendly2 := pairingStartMessage(errors.New("dial tcp 10.0.0.5:47800: connect: connection refused"))
	if friendly2 {
		t.Errorf("a dial error must not be the friendly case")
	}
	if !strings.Contains(msg2, "could not reach device") {
		t.Errorf("dial message = %q", msg2)
	}
}

// The QR panel polls with its current nonce; while that invite is still valid
// the server returns the same one, so the code stays put. A stale nonce yields
// a fresh invite.
func TestPairPayloadReusesValidInvite(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	s := New(Deps{
		Trust: tr,
		Self:  func() SelfInfo { return SelfInfo{DeviceID: "self-fp", Name: "Desk", PeerPort: 47800} },
	})

	fetch := func(nonce string) string {
		t.Helper()
		url := "/api/pair/payload"
		if nonce != "" {
			url += "?nonce=" + nonce
		}
		rr := httptest.NewRecorder()
		s.handlePairPayload(rr, httptest.NewRequest(http.MethodGet, url, nil))
		if rr.Code != http.StatusOK {
			t.Fatalf("status = %d body = %s", rr.Code, rr.Body.String())
		}
		var out map[string]any
		if err := json.Unmarshal(rr.Body.Bytes(), &out); err != nil {
			t.Fatalf("decode: %v", err)
		}
		n, _ := out["nonce"].(string)
		if n == "" {
			t.Fatalf("no nonce in %v", out)
		}
		return n
	}

	first := fetch("")
	if again := fetch(first); again != first {
		t.Fatalf("a valid invite changed: %q -> %q", first, again)
	}
	if fresh := fetch("deadbeef"); fresh == first {
		t.Fatalf("a stale nonce should mint a new invite, still %q", fresh)
	}
}

func TestInboxOpenUsesConfiguredOpener(t *testing.T) {
	m := inbox.New(t.TempDir(), nil)
	opened := ""
	s := New(Deps{Inbox: m, OpenFolder: func(p string) error { opened = p; return nil }})

	rr := httptest.NewRecorder()
	s.handleInboxOpen(rr, httptest.NewRequest(http.MethodPost, "/api/inbox/open", nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d body = %s", rr.Code, rr.Body.String())
	}
	if opened != m.Dir() {
		t.Fatalf("opened %q, want %q", opened, m.Dir())
	}

	failing := New(Deps{Inbox: m, OpenFolder: func(string) error { return errors.New("no file manager") }})
	rr2 := httptest.NewRecorder()
	failing.handleInboxOpen(rr2, httptest.NewRequest(http.MethodPost, "/api/inbox/open", nil))
	if rr2.Code != http.StatusInternalServerError {
		t.Fatalf("failing opener status = %d, want 500", rr2.Code)
	}
}
