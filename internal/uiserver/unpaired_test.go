package uiserver

import (
	"context"
	"encoding/json"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
	"lanyard/internal/trust"
)

// peerStatus keeps a peer's 403 a 403 so the UI takes the "not paired" branch
// instead of prefixing it with "Could not reach"; every other failure is 502.
func TestPeerStatusPreservesForbidden(t *testing.T) {
	if got := peerStatus(&peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "not paired"}); got != http.StatusForbidden {
		t.Errorf("peerStatus(403) = %d, want 403", got)
	}
	if got := peerStatus(&peerapi.StatusError{Code: http.StatusNotFound, Status: "404 Not Found", Msg: "nope"}); got != http.StatusBadGateway {
		t.Errorf("peerStatus(404) = %d, want 502", got)
	}
	if got := peerStatus(context.DeadlineExceeded); got != http.StatusBadGateway {
		t.Errorf("peerStatus(dial) = %d, want 502", got)
	}
}

// A generic 403 from a peer we believe is paired means the peer unpaired us:
// the stale local entry is dropped. A specific 403 (a permission denial) must
// leave the pairing alone.
func TestPeerRefusedPairingRemovesLocalPairing(t *testing.T) {
	newSrv := func(t *testing.T) (*Server, *trust.Store) {
		t.Helper()
		cfg, err := config.Open(t.TempDir())
		if err != nil {
			t.Fatalf("config.Open: %v", err)
		}
		tr := trust.New(cfg, "self-fp", nil)
		return New(Deps{Trust: tr}), tr
	}

	s, tr := newSrv(t)
	tr.Pair(trust.Entry{DeviceID: "phone", Name: "Phone", Fingerprint: "peer-fp"})
	if !s.peerRefusedPairing("peer-fp", &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "not paired", Pinned: true}) {
		t.Fatal("a pinned exact 403 should be treated as the peer unpaired us")
	}
	if _, ok := tr.Entry("peer-fp"); ok {
		t.Fatal("the stale local pairing should have been removed")
	}

	s2, tr2 := newSrv(t)
	tr2.Pair(trust.Entry{DeviceID: "phone", Name: "Phone", Fingerprint: "peer-fp"})
	if s2.peerRefusedPairing("peer-fp", &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "The other device declined the transfer.", Pinned: true}) {
		t.Fatal("a specific 403 must not be read as an unpair")
	}
	if _, ok := tr2.Entry("peer-fp"); !ok {
		t.Fatal("the pairing should survive a permission denial")
	}
}

// Every non-"not paired" 403 leaves the pairing intact, including the permission
// refusals a phone now prompts for, an empty/odd body, and a message that merely
// contains "paired".
func TestPeerRefusedPairingIgnoresPermissionDenials(t *testing.T) {
	for _, msg := range []string{
		"pull not permitted",
		"push not permitted",
		"not permitted",
		"forbidden",
		"",
		"<html>go away</html>",
		"The other device declined the transfer.",
		"peer is not paired yet",
		"unpaired",
	} {
		cfg, err := config.Open(t.TempDir())
		if err != nil {
			t.Fatalf("config.Open: %v", err)
		}
		tr := trust.New(cfg, "self-fp", nil)
		tr.Pair(trust.Entry{DeviceID: "phone", Name: "Phone", Fingerprint: "peer-fp"})
		s := New(Deps{Trust: tr})
		if s.peerRefusedPairing("peer-fp", &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: msg, Pinned: true}) {
			t.Errorf("%q: pairing was removed, want it kept", msg)
		}
		if _, ok := tr.Entry("peer-fp"); !ok {
			t.Errorf("%q: the pairing should survive", msg)
		}
	}
}

// The phone sends its refusal as a JSON error body ({"error":"not paired"}); the
// auto-unpair path must recognize that real wire form, and must not be fooled by
// a JSON permission reason or a lookalike. The pinned-certificate gate still
// applies to the JSON form too.
func TestPeerRefusedPairingMatchesThePhoneJSONBody(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: "phone", Name: "Phone", Fingerprint: "peer-fp"})
	s := New(Deps{Trust: tr})
	if !s.peerRefusedPairing("peer-fp", &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: `{"error":"not paired"}`, Pinned: true}) {
		t.Fatal("the phone's JSON not-paired body must be treated as the peer unpaired us")
	}
	if _, ok := tr.Entry("peer-fp"); ok {
		t.Fatal("the stale local pairing should have been removed")
	}

	for _, tc := range []struct {
		name string
		msg  string
		pin  bool
	}{
		{"json permission", `{"error":"pull not permitted"}`, true},
		{"json not paired yet", `{"error":"not paired yet"}`, true},
		{"json forbidden", `{"error":"forbidden"}`, true},
		{"unpinned json not paired", `{"error":"not paired"}`, false},
	} {
		cfg2, err := config.Open(t.TempDir())
		if err != nil {
			t.Fatalf("%s: config.Open: %v", tc.name, err)
		}
		tr2 := trust.New(cfg2, "self-fp", nil)
		tr2.Pair(trust.Entry{DeviceID: "phone", Name: "Phone", Fingerprint: "peer-fp"})
		s2 := New(Deps{Trust: tr2})
		if s2.peerRefusedPairing("peer-fp", &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: tc.msg, Pinned: tc.pin}) {
			t.Errorf("%s: pairing was removed, want it kept", tc.name)
		}
		if _, ok := tr2.Entry("peer-fp"); !ok {
			t.Errorf("%s: the pairing should survive", tc.name)
		}
	}
}

// The removal path is gated on certificate pinning: an unpinned 403 "not paired"
// (TLS present but the peer cert was never checked against the expected
// fingerprint) must not drop a pairing.
func TestPeerRefusedPairingRequiresPinnedCert(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: "phone", Name: "Phone", Fingerprint: "peer-fp"})
	s := New(Deps{Trust: tr})
	if s.peerRefusedPairing("peer-fp", &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "not paired"}) {
		t.Fatal("an unpinned 403 must not remove the pairing")
	}
	if _, ok := tr.Entry("peer-fp"); !ok {
		t.Fatal("the pairing should survive an unpinned refusal")
	}
}

// When the unpair notification cannot be delivered, revokeRemote records a
// pending-unpair entry so it is retried when the device is next seen. A revoke
// the peer itself initiated (HandleRemoteRevoke) is not retried.
func TestRevokeRemoteRecordsPendingUnpairOnFailure(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	s := New(Deps{
		Trust:  tr,
		Client: peerapi.NewClient(id),
		Peers:  func() []discovery.Peer { return nil },
	})

	// Port 1 is closed, so the notification fails.
	s.revokeRemote("peer-fp", trust.Entry{Fingerprint: "peer-fp", Addrs: []string{"127.0.0.1"}, Port: 1})
	if !tr.HasPendingUnpair("peer-fp") {
		t.Fatal("a failed notification should be kept as a pending unpair")
	}

	tr.ClearPendingUnpair("peer-fp")
	s.HandleRemoteRevoke("peer-fp", true)
	if tr.HasPendingUnpair("peer-fp") {
		t.Fatal("a peer-initiated revoke must not schedule a retry")
	}
}

// RetryPendingUnpair is a no-op unless the device still has a pending record.
func TestRetryPendingUnpairNoopWhenNone(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	s := New(Deps{Trust: tr, Client: peerapi.NewClient(id), Peers: func() []discovery.Peer { return nil }})
	s.RetryPendingUnpair("nobody")
	s.RetryPendingUnpairs()
	if len(tr.PendingUnpairs()) != 0 {
		t.Fatal("no pending records should have been created")
	}
}

// End to end: listing a paired device's shares, when the peer answers 403
// "not paired", returns a 403 with the friendly wording and drops the stale
// local pairing, so the device pane can show "Not paired with this device".
func TestRemoteSharesForbiddenDropsPairingAndReturns403(t *testing.T) {
	peerSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "not paired", http.StatusForbidden)
	}))
	defer peerSrv.Close()

	fp := identity.FingerprintOf(peerSrv.Certificate())
	u, err := url.Parse(peerSrv.URL)
	if err != nil {
		t.Fatalf("parse server url: %v", err)
	}
	host, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatalf("split host: %v", err)
	}
	port, _ := strconv.Atoi(portStr)

	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: fp, Name: "Phone", Fingerprint: fp})
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	s := New(Deps{
		Trust:  tr,
		Client: peerapi.NewClient(id),
		Peers: func() []discovery.Peer {
			return []discovery.Peer{{DeviceID: fp, ShortID: identity.ShortID(fp), Name: "Phone", Addrs: []string{host}, Port: port, Verified: true}}
		},
	})

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/remote/shares?device="+url.QueryEscape(fp), nil)
	s.handleRemoteShares(rr, req)

	if rr.Code != http.StatusForbidden {
		t.Fatalf("status = %d body = %q, want 403", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), peerapi.NotPairedMessage) {
		t.Fatalf("body = %q, want %q", rr.Body.String(), peerapi.NotPairedMessage)
	}
	if _, ok := tr.Entry(fp); ok {
		t.Fatal("the stale local pairing should have been dropped")
	}
}

// End to end: a peer that denies the pull with 403 "pull not permitted" (the
// phone now prompts) must NOT be read as an unpair; the pairing survives and the
// response is still a 403.
func TestRemoteSharesPullNotPermittedKeepsPairing(t *testing.T) {
	peerSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "pull not permitted", http.StatusForbidden)
	}))
	defer peerSrv.Close()

	fp := identity.FingerprintOf(peerSrv.Certificate())
	u, err := url.Parse(peerSrv.URL)
	if err != nil {
		t.Fatalf("parse server url: %v", err)
	}
	host, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		t.Fatalf("split host: %v", err)
	}
	port, _ := strconv.Atoi(portStr)

	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: fp, Name: "Phone", Fingerprint: fp})
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	s := New(Deps{
		Trust:  tr,
		Client: peerapi.NewClient(id),
		Peers: func() []discovery.Peer {
			return []discovery.Peer{{DeviceID: fp, ShortID: identity.ShortID(fp), Name: "Phone", Addrs: []string{host}, Port: port, Verified: true}}
		},
	})

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodGet, "/api/remote/shares?device="+url.QueryEscape(fp), nil)
	s.handleRemoteShares(rr, req)

	if rr.Code != http.StatusForbidden {
		t.Fatalf("status = %d body = %q, want 403", rr.Code, rr.Body.String())
	}
	if _, ok := tr.Entry(fp); !ok {
		t.Fatal("a pull permission denial must not drop the pairing")
	}
}

// The UI reads the status off the local response; keep the JSON shape valid in
// the happy path too (guards against a regression that writes a text body).
func TestRemoteSharesSuccessShape(t *testing.T) {
	peerSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		_ = json.NewEncoder(w).Encode([]any{})
	}))
	defer peerSrv.Close()

	fp := identity.FingerprintOf(peerSrv.Certificate())
	u, _ := url.Parse(peerSrv.URL)
	host, portStr, _ := net.SplitHostPort(u.Host)
	port, _ := strconv.Atoi(portStr)

	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	s := New(Deps{
		Client: peerapi.NewClient(id),
		Peers: func() []discovery.Peer {
			return []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}
		},
	})
	rr := httptest.NewRecorder()
	s.handleRemoteShares(rr, httptest.NewRequest(http.MethodGet, "/api/remote/shares?device="+url.QueryEscape(fp), nil))
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d body = %q, want 200", rr.Code, rr.Body.String())
	}
}
