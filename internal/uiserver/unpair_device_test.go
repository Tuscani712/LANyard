package uiserver

import (
	"bytes"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"os"
	"strconv"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
	"lanyard/internal/trust"
)

// unpairServer builds a Server backed by a fresh trust store plus the client
// plumbing handleUnpair needs to notify a peer, with peers read through a
// pointer so a test can make a device "come back" after it was offline.
func unpairServer(t *testing.T, peers *[]discovery.Peer) (*Server, *trust.Store) {
	t.Helper()
	s, tr, _ := unpairServerLogged(t, peers)
	return s, tr
}

// unpairServerLogged is unpairServer plus the captured log buffer, so a test can
// assert whether a retry warned.
func unpairServerLogged(t *testing.T, peers *[]discovery.Peer) (*Server, *trust.Store, *bytes.Buffer) {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	logger, buf := testLogger()
	s := New(Deps{
		Trust:  tr,
		Client: peerapi.NewClient(id),
		Peers:  func() []discovery.Peer { return *peers },
		Log:    logger,
	})
	return s, tr, buf
}

// The device pane's "Unpair this device" posts to the same endpoint the Paired
// page uses. When the peer is offline the local pairing is still removed on the
// first try, and the mutual notification is kept as a pending unpair so it is
// retried once the device is next seen.
func TestHandleUnpairOfflinePeerRemovesAndSchedulesRetry(t *testing.T) {
	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	tr.Pair(trust.Entry{DeviceID: "peer-fp", Name: "Phone", Fingerprint: "peer-fp", Addrs: []string{"127.0.0.1"}, Port: 1})

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/trust/peer-fp/unpair", nil)
	req.SetPathValue("fp", "peer-fp")
	s.handleUnpair(rr, req)

	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d body = %q, want 200", rr.Code, rr.Body.String())
	}
	if _, ok := tr.Entry("peer-fp"); ok {
		t.Fatal("the local pairing should have been removed")
	}
	if !tr.HasPendingUnpair("peer-fp") {
		t.Fatal("an offline peer should leave a pending unpair for retry")
	}
}

// The pending unpair is delivered once the peer reappears, and then cleared.
func TestUnpairRetryReachesPeerWhenItReturns(t *testing.T) {
	var hits atomic.Int32
	peerSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/trust/revoke") {
			hits.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"ok":true}`))
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

	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	tr.Pair(trust.Entry{DeviceID: fp, Name: "Phone", Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1})

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/trust/"+url.PathEscape(fp)+"/unpair", nil)
	req.SetPathValue("fp", fp)
	s.handleUnpair(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rr.Code)
	}
	if !tr.HasPendingUnpair(fp) {
		t.Fatal("offline peer should have left a pending unpair")
	}

	// The device comes back: discovery now knows its live address, so the retry
	// can deliver the notification and clear the pending record.
	peers = []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}
	s.RetryPendingUnpair(fp)

	if hits.Load() != 1 {
		t.Fatalf("peer revocations = %d, want 1", hits.Load())
	}
	if tr.HasPendingUnpair(fp) {
		t.Fatal("a delivered unpair should clear the pending record")
	}
}

// A second unpair of an already-unpaired device is a harmless 404, which the UI
// treats as "already unpaired" rather than an error (idempotent from the
// person's point of view).
func TestHandleUnpairAlreadyUnpairedIs404(t *testing.T) {
	var peers []discovery.Peer
	s, _ := unpairServer(t, &peers)

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/trust/ghost-fp/unpair", nil)
	req.SetPathValue("fp", "ghost-fp")
	s.handleUnpair(rr, req)

	if rr.Code != http.StatusNotFound {
		t.Fatalf("status = %d, want 404", rr.Code)
	}
}

// The device pane must offer "Unpair this device", confirm it, and route it
// through the one shared unpair helper (fingerprint-aware, with a result toast
// and the already-unpaired branch) rather than a bespoke request.
func TestAppJSDevicePaneHasUnpairThisDevice(t *testing.T) {
	src, err := os.ReadFile("web/app.js")
	if err != nil {
		t.Fatalf("read app.js: %v", err)
	}
	js := string(src)
	checks := []struct{ name, sub string }{
		{"device pane action label", `btn("Unpair this device", () => unpair(p.device, paired), "ghost")`},
		{"unpair confirms first", "if (!confirm(`Unpair ${name}? Active connections from this device will be rejected immediately.`)) return;"},
		{"unpair keys on the fingerprint", `(entry && entry.cert_fingerprint) || fp`},
		{"unpair posts the shared endpoint", "`/api/trust/${encodeURIComponent(key)}/unpair`"},
		{"unpair reports success", "Unpaired ${name}. Active connections from this device will be rejected."},
		{"unpair mentions the offline retry", "It is offline now; it will be told when it is next seen."},
		{"unpair is idempotent on 404", "if (r.status === 404) { toast(`${name} was already unpaired.`, \"info\"); return; }"},
		{"unpair reports other failures", "Could not unpair ${name}."},
	}
	for _, c := range checks {
		if !strings.Contains(js, c.sub) {
			t.Errorf("%s: app.js is missing %q", c.name, c.sub)
		}
	}
}

// revokePeer starts a TLS peer that counts /trust/revoke hits and answers with
// the given status/body, then returns its fingerprint and host:port.
func revokePeer(t *testing.T, status int, body string) (fp, host string, port int, hits *atomic.Int32, closeFn func()) {
	t.Helper()
	var n atomic.Int32
	hits = &n
	srv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodPost && strings.HasSuffix(r.URL.Path, "/trust/revoke") {
			n.Add(1)
		}
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)
		_, _ = w.Write([]byte(body))
	}))
	fp = identity.FingerprintOf(srv.Certificate())
	u, err := url.Parse(srv.URL)
	if err != nil {
		srv.Close()
		t.Fatalf("parse server url: %v", err)
	}
	host, portStr, err := net.SplitHostPort(u.Host)
	if err != nil {
		srv.Close()
		t.Fatalf("split host: %v", err)
	}
	port, _ = strconv.Atoi(portStr)
	return fp, host, port, hits, srv.Close
}

// A stale pending-unpair that fires after a re-pair must not unpair the freshly
// paired device: re-pairing clears the queued revoke, and even a revoke that
// somehow predates the new pairing is refused.
func TestRePairSurvivesStalePendingUnpair(t *testing.T) {
	fp, host, port, hits, done := revokePeer(t, http.StatusOK, `{"ok":true}`)
	defer done()

	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	// Unpair while the peer is offline (an address nothing answers).
	tr.Pair(trust.Entry{DeviceID: fp, Name: "Phone", Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/trust/"+url.PathEscape(fp)+"/unpair", nil)
	req.SetPathValue("fp", fp)
	s.handleUnpair(rr, req)
	if !tr.HasPendingUnpair(fp) {
		t.Fatal("offline unpair should leave a pending revoke")
	}

	// The person re-pairs the device. This must clear the stale revoke.
	tr.Pair(trust.Entry{DeviceID: fp, Name: "Phone again", Fingerprint: fp, CreatedAt: time.Now()})
	if tr.HasPendingUnpair(fp) {
		t.Fatal("re-pairing must clear the stale pending unpair")
	}

	// The device reconnects: every retry signal must be a no-op and the fresh
	// pairing must survive.
	peers = []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}
	s.RetryPendingUnpair(fp)
	s.RetryPendingUnpairs()
	if hits.Load() != 0 {
		t.Fatalf("stale revoke was delivered %d time(s), want 0", hits.Load())
	}
	e, ok := tr.Entry(fp)
	if !ok || e.Name != "Phone again" {
		t.Fatalf("the fresh pairing must survive, got %+v ok=%v", e, ok)
	}
}

// Even if a queued revoke record survives alongside a newer pairing, the retry
// path must refuse to deliver a revoke that predates the pairing's pairedAt.
func TestRetryRefusesRevokePredatingPairing(t *testing.T) {
	fp, host, port, hits, done := revokePeer(t, http.StatusOK, `{"ok":true}`)
	defer done()

	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	// Pair with a pairedAt in the future, then queue a revoke (queuedAt now):
	// the revoke predates the pairing and must be refused.
	tr.Pair(trust.Entry{DeviceID: fp, Fingerprint: fp, Name: "Phone", CreatedAt: time.Now().Add(time.Hour)})
	tr.AddPendingUnpair(trust.Entry{Fingerprint: fp})
	peers = []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}

	s.RetryPendingUnpair(fp)

	if hits.Load() != 0 {
		t.Fatalf("a revoke predating the pairing was delivered %d time(s), want 0", hits.Load())
	}
	if tr.HasPendingUnpair(fp) {
		t.Fatal("a superseded revoke must be discarded, not retried forever")
	}
	if _, ok := tr.Entry(fp); !ok {
		t.Fatal("the pairing must survive the refused revoke")
	}
}

// On the retry path a pinned 403 "not paired" is not a failure: the peer has
// already dropped us, which is exactly what the unpair wanted. The pending
// record must be cleared and no warning logged.
func TestRetryNotPaired403ClearsPendingWithoutWarning(t *testing.T) {
	fp, host, port, _, done := revokePeer(t, http.StatusForbidden, "not paired")
	defer done()

	var peers []discovery.Peer
	s, tr, buf := unpairServerLogged(t, &peers)
	tr.Pair(trust.Entry{DeviceID: fp, Fingerprint: fp, Name: "Phone", Addrs: []string{"127.0.0.1"}, Port: 1})
	tr.AddPendingUnpair(trust.Entry{Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1})
	peers = []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}

	s.RetryPendingUnpair(fp)

	if tr.HasPendingUnpair(fp) {
		t.Fatal("a pinned 403 not paired means already unpaired: the record must be cleared")
	}
	if strings.Contains(buf.String(), "could not notify the peer") {
		t.Fatalf("already-unpaired must not warn as a delivery failure:\n%s", buf.String())
	}
}

// A 403 that is any other message is a permission refusal, not a dropped
// pairing: the retry must keep the record and warn.
func TestRetry403OtherMessageKeepsPending(t *testing.T) {
	fp, host, port, _, done := revokePeer(t, http.StatusForbidden, "forbidden")
	defer done()

	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	tr.AddPendingUnpair(trust.Entry{Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1})
	peers = []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}

	s.RetryPendingUnpair(fp)

	if !tr.HasPendingUnpair(fp) {
		t.Fatal("a non-\"not paired\" 403 must keep the pending record for another try")
	}
}

// The phone answers with a JSON error body ({"error":"not paired"}), so the
// retry must recognise that form too: the peer already dropped us, the pending
// record is cleared, and no delivery failure is warned.
func TestRetryNotPaired403JSONClearsPendingWithoutWarning(t *testing.T) {
	fp, host, port, _, done := revokePeer(t, http.StatusForbidden, `{"error":"not paired"}`)
	defer done()

	var peers []discovery.Peer
	s, tr, buf := unpairServerLogged(t, &peers)
	tr.Pair(trust.Entry{DeviceID: fp, Fingerprint: fp, Name: "Phone", Addrs: []string{"127.0.0.1"}, Port: 1})
	tr.AddPendingUnpair(trust.Entry{Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1})
	peers = []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}

	s.RetryPendingUnpair(fp)

	if tr.HasPendingUnpair(fp) {
		t.Fatal("a pinned 403 JSON not paired means already unpaired: the record must be cleared")
	}
	if strings.Contains(buf.String(), "could not notify the peer") {
		t.Fatalf("already-unpaired must not warn as a delivery failure:\n%s", buf.String())
	}
}

// A JSON 403 with a permission reason is not an unpair: it must keep the
// pending record, exactly like the plain-text permission denials.
func TestRetry403JSONPermissionKeepsPending(t *testing.T) {
	fp, host, port, _, done := revokePeer(t, http.StatusForbidden, `{"error":"forbidden"}`)
	defer done()

	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	tr.AddPendingUnpair(trust.Entry{Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1})
	peers = []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}

	s.RetryPendingUnpair(fp)

	if !tr.HasPendingUnpair(fp) {
		t.Fatal("a JSON permission 403 must keep the pending record for another try")
	}
}

// A duplicate retry that captured its token before a sibling delivered must not
// re-arm the pending record once the sibling has cleared it. This is the
// 200-then-failure race: the losing attempt's "remember" is refused.
func TestRacingRetryDoesNotRearmAfterSiblingSucceeds(t *testing.T) {
	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	fp := "peer-fp"
	tr.Pair(trust.Entry{DeviceID: fp, Fingerprint: fp, Name: "Phone", Addrs: []string{"127.0.0.1"}, Port: 1})
	tr.AddPendingUnpair(trust.Entry{Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1})

	// Both racing retries capture the token before either finishes.
	seen := tr.UnpairGeneration(fp)
	hadPending := tr.HasPendingUnpair(fp)

	// Sibling A succeeds: it clears the pending record and marks the delivery.
	tr.ClearPendingUnpair(fp)
	tr.MarkUnpairDelivered(fp)

	// Sibling B fails and tries to remember; the guard must refuse.
	s.rememberPendingUnpair(fp, trust.Entry{Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1}, true, seen, hadPending)

	if tr.HasPendingUnpair(fp) {
		t.Fatal("a racing retry must not re-arm a revoke a sibling already delivered")
	}
}

// A scheduled (initial) unpair whose notification fails on a lone attempt is
// still remembered, so the mutuality is retried when the peer is next seen.
func TestInitialUnpairFailureStillArmsWhenNoSiblingSucceeded(t *testing.T) {
	var peers []discovery.Peer
	s, tr := unpairServer(t, &peers)
	fp := "peer-fp"
	seen := tr.UnpairGeneration(fp)
	s.rememberPendingUnpair(fp, trust.Entry{Fingerprint: fp, Addrs: []string{"127.0.0.1"}, Port: 1}, true, seen, false)
	if !tr.HasPendingUnpair(fp) {
		t.Fatal("a lone failed unpair must be remembered for retry")
	}
}
