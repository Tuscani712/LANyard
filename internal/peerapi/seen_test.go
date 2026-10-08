package peerapi

import (
	"context"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/trust"
)

// peerSeenServer builds a Server with a trust store so the handshake endpoint is
// reachable, returning a channel that receives every onSeen fingerprint.
func peerSeenServer(t *testing.T) (*Server, <-chan string) {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	seen := make(chan string, 4)
	s := NewServer(nil, func() discovery.Hello { return discovery.Hello{} }, nil, tr, AllowAll(), slog.Default())
	s.SetOnPeerSeen(func(fp string) { seen <- fp })
	return s, seen
}

func waitSeen(t *testing.T, seen <-chan string, want string) {
	t.Helper()
	select {
	case got := <-seen:
		if got != want {
			t.Fatalf("onSeen fp = %q, want %q", got, want)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("onSeen was not called")
	}
}

// A successful /hello proves the peer is reachable, so the host can retry a
// pending unpair for it.
func TestOnPeerSeenFiresOnHello(t *testing.T) {
	s, seen := peerSeenServer(t)
	req := httptest.NewRequest(http.MethodGet, "/api/v1/hello", nil)
	req = req.WithContext(context.WithValue(req.Context(), peerIDKey, "peer-fp"))
	s.handleHello(httptest.NewRecorder(), req)
	waitSeen(t, seen, "peer-fp")
}

// An inbound handshake already proves the peer is online, even before the
// request is validated or accepted, so onSeen fires for it too.
func TestOnPeerSeenFiresOnInboundHandshake(t *testing.T) {
	s, seen := peerSeenServer(t)
	body := `{"mode":"connect","name":"Bob","device_id":"peer-fp","nonce":"0123456789abcdef"}`
	req := httptest.NewRequest(http.MethodPost, "/api/v1/session/request", strings.NewReader(body))
	req = req.WithContext(context.WithValue(req.Context(), peerIDKey, "peer-fp"))
	rr := httptest.NewRecorder()
	s.handleSessionRequest(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("session request status = %d, body=%q", rr.Code, rr.Body.String())
	}
	waitSeen(t, seen, "peer-fp")
}

// A rejected handshake still shows the peer is reachable: onSeen fires before
// the request is declined.
func TestOnPeerSeenFiresOnRejectedHandshake(t *testing.T) {
	s, seen := peerSeenServer(t)
	req := httptest.NewRequest(http.MethodPost, "/api/v1/session/request", strings.NewReader(`{"mode":"bogus"}`))
	req = req.WithContext(context.WithValue(req.Context(), peerIDKey, "peer-fp"))
	s.handleSessionRequest(httptest.NewRecorder(), req)
	waitSeen(t, seen, "peer-fp")
}
