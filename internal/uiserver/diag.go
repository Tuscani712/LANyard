package uiserver

import (
	"context"
	"errors"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"lanyard/internal/diag"
	"lanyard/internal/discovery"
	"lanyard/internal/inbox"
	"lanyard/internal/lanaddr"
)

// diagEnv adapts the running server to the diagnostics checks.
type diagEnv struct {
	s      *Server
	device string
}

func (e diagEnv) RequestedPeerPort() int {
	if e.s.d.Self == nil {
		return 0
	}
	return e.s.d.Self().PeerPortRequested
}

func (e diagEnv) PeerPort() int {
	if e.s.d.Self == nil {
		return 0
	}
	return e.s.d.Self().PeerPort
}

func (e diagEnv) PeerPortFallback() bool {
	if e.s.d.Self == nil {
		return false
	}
	return e.s.d.Self().PeerPortFallback
}

func (e diagEnv) LocalAddrs() []string { return lanaddr.Addrs() }

func (e diagEnv) Peers() []diag.Peer {
	if e.s.d.Peers == nil {
		return nil
	}
	src := e.s.d.Peers()
	out := make([]diag.Peer, 0, len(src))
	for _, p := range src {
		out = append(out, toDiagPeer(p))
	}
	return out
}

func (e diagEnv) PeerByID(id string) (diag.Peer, bool) {
	p, ok := e.s.peerByID(id)
	if !ok {
		return diag.Peer{}, false
	}
	return toDiagPeer(p), true
}

func toDiagPeer(p discovery.Peer) diag.Peer {
	return diag.Peer{
		DeviceID: p.DeviceID, Name: p.Name, Addrs: p.Addrs,
		Port: p.Port, Source: p.Source, LastSeen: p.LastSeen,
	}
}

func (e diagEnv) TargetDevice() string { return e.device }

func (e diagEnv) DialTCP(ctx context.Context, host string, port int) error {
	d := net.Dialer{Timeout: 3 * time.Second}
	c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return err
	}
	_ = c.Close()
	return nil
}

func (e diagEnv) Probe(ctx context.Context, host string, port int) (string, error) {
	if e.s.d.Client == nil {
		return "", errors.New("peer client unavailable")
	}
	fp, _, err := e.s.d.Client.Probe(ctx, host, port)
	return fp, err
}

func (e diagEnv) FreeSpace(dir string) int64 { return inbox.FreeSpace(dir) }

func (e diagEnv) InboxDir() string {
	if e.s.d.Inbox == nil {
		return ""
	}
	return e.s.d.Inbox.Dir()
}

func (e diagEnv) DownloadDir() string {
	if e.s.d.Cfg == nil {
		return ""
	}
	return e.s.d.Cfg.Get().DefaultDownloadFolder
}

// PeerHelloTime reports that the protocol carries no clock. The wizard then
// marks the clock check as skipped rather than inventing one.
func (e diagEnv) PeerHelloTime() (time.Time, bool) { return time.Time{}, false }

func (e diagEnv) Now() time.Time { return time.Now() }

// handleDiagnostics runs the checks and returns them plus a plain-text report
// with no secrets (no tokens, nonces or full fingerprints).
func (s *Server) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	if s.d.Cfg == nil || s.d.Self == nil {
		http.Error(w, "diagnostics unavailable", http.StatusServiceUnavailable)
		return
	}
	device := strings.TrimSpace(r.URL.Query().Get("device"))
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	checks := diag.Run(ctx, diagEnv{s: s, device: device})
	writeJSON(w, map[string]any{"checks": checks, "report": diag.Report(checks)})
}
