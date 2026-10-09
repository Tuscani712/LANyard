package uiserver

import (
	"bytes"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"net/url"
	"strconv"
	"strings"
	"sync"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
	"lanyard/internal/trust"
)

func aliasTestServer(t *testing.T) (*Server, *trust.Store) {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	s := New(Deps{Trust: tr})
	return s, tr
}

// Renaming a paired device persists the alias, returns it, and clearing the
// alias reverts to the broadcast name. An unpaired device answers 404 so a
// stale request cannot create an alias for a device that is gone.
func TestHandleTrustAliasSetClearAndUnpaired(t *testing.T) {
	s, tr := aliasTestServer(t)
	tr.Pair(trust.Entry{DeviceID: "peer-dev", Name: "Pixel 8 Pro", Fingerprint: "peer-fp"})

	set := func(fp, alias string) *httptest.ResponseRecorder {
		body, _ := json.Marshal(map[string]string{"alias": alias})
		rr := httptest.NewRecorder()
		req := httptest.NewRequest(http.MethodPost, "/api/trust/"+fp+"/alias", bytes.NewReader(body))
		req.SetPathValue("fp", fp)
		s.handleTrustAlias(rr, req)
		return rr
	}

	rr := set("peer-fp", "  Kitchen Phone  ")
	if rr.Code != http.StatusOK {
		t.Fatalf("set alias status = %d body = %s", rr.Code, rr.Body.String())
	}
	var got trust.Entry
	if err := json.Unmarshal(rr.Body.Bytes(), &got); err != nil {
		t.Fatalf("decode entry: %v", err)
	}
	if got.Alias != "Kitchen Phone" || got.DisplayName() != "Kitchen Phone" {
		t.Fatalf("alias = %q display = %q, want trimmed Kitchen Phone", got.Alias, got.DisplayName())
	}
	// The broadcast name is untouched.
	if got.Name != "Pixel 8 Pro" {
		t.Fatalf("the broadcast name changed to %q", got.Name)
	}
	if e, _ := tr.Entry("peer-fp"); e.Alias != "Kitchen Phone" {
		t.Fatalf("alias not persisted in the store: %+v", e)
	}

	if rr := set("peer-fp", ""); rr.Code != http.StatusOK {
		t.Fatalf("clear alias status = %d", rr.Code)
	}
	if e, _ := tr.Entry("peer-fp"); e.Alias != "" || e.DisplayName() != "Pixel 8 Pro" {
		t.Fatalf("clearing should revert to the broadcast name, got %+v", e)
	}

	if rr := set("ghost-fp", "Ghost"); rr.Code != http.StatusNotFound {
		t.Fatalf("alias on an unpaired device = %d, want 404", rr.Code)
	}
}

// An alias is local-only: a normal pairing request must carry the broadcast name
// and never the alias.
func TestAliasIsNeverSentInAPairingRequest(t *testing.T) {
	var mu sync.Mutex
	var bodies []string
	peerSrv := httptest.NewTLSServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		b, _ := io.ReadAll(io.LimitReader(r.Body, 1<<20))
		mu.Lock()
		bodies = append(bodies, string(b))
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = w.Write([]byte(`{"session_id":"s1","nonce":"n1","status":"pending"}`))
	}))
	defer peerSrv.Close()

	fp := identity.FingerprintOf(peerSrv.Certificate())
	u, _ := url.Parse(peerSrv.URL)
	host, portStr, _ := net.SplitHostPort(u.Host)
	port, _ := strconv.Atoi(portStr)

	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: fp, Name: "Pixel 8 Pro", Fingerprint: fp, Alias: "Secret Alias"})

	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	s := New(Deps{
		Trust:  tr,
		Client: peerapi.NewClient(id),
		Peers: func() []discovery.Peer {
			return []discovery.Peer{{DeviceID: fp, Addrs: []string{host}, Port: port, Verified: true}}
		},
		Self: func() SelfInfo { return SelfInfo{Name: "My Desktop", DeviceID: id.DeviceID} },
	})

	body, _ := json.Marshal(map[string]any{"device": fp, "mode": "connect"})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/sessions/request", bytes.NewReader(body))
	s.handleSessionStart(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("session start status = %d body = %s", rr.Code, rr.Body.String())
	}

	mu.Lock()
	defer mu.Unlock()
	if len(bodies) == 0 {
		t.Fatal("the peer received no request")
	}
	all := strings.Join(bodies, "\n")
	if strings.Contains(all, "Secret Alias") {
		t.Errorf("a peer request carried the local alias: %s", all)
	}
	if !strings.Contains(all, "My Desktop") {
		t.Errorf("the pairing request should carry the broadcast name, got: %s", all)
	}
}
