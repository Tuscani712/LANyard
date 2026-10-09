// Package uiserver serves the embedded web UI and its API on loopback only.
// Protections: random per-launch token, Host allow-list (DNS rebinding),
// Origin check on state-changing requests (CSRF).
package uiserver

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"embed"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"net"
	"net/http"
	"net/url"
	"strings"
	"sync"
	"time"

	"lanyard/internal/approval"
	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/inbox"
	"lanyard/internal/mount"
	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
	"lanyard/internal/transfer"
	"lanyard/internal/trust"
	"lanyard/internal/xferlog"
)

//go:embed web
var webFS embed.FS

type SelfInfo struct {
	Name           string `json:"name"`
	DeviceID       string `json:"device_id"`
	Pretty         string `json:"device_id_pretty"`
	GeneratedLabel string `json:"generated_label"`
	DeviceLabel    string `json:"device_label"`
	OS             string `json:"os"`
	PeerPort       int    `json:"peer_port"`
	// PeerPortRequested is the port we tried to bind; PeerPortFallback is true
	// when it was busy and the service moved to PeerPort instead.
	PeerPortRequested int  `json:"peer_port_requested,omitempty"`
	PeerPortFallback  bool `json:"peer_port_fallback,omitempty"`
	// PeerPortFallbackNotice is the person-facing line shown in the banner and
	// Settings when a temporary peer port was bound, e.g. "Using temporary
	// port 51234 because 47800 is in use by other".
	PeerPortFallbackNotice string `json:"peer_port_fallback_notice,omitempty"`
	// BeaconPort is the configured fallback discovery UDP port, used with
	// PeerPort and the fixed mDNS port to generate the firewall commands.
	BeaconPort int    `json:"beacon_port"`
	Version    string `json:"version"`
	// MountsSupported reports whether serving a paired device as a drive is
	// available on this platform/app; the device page only offers "Mount as
	// drive" when it is true (desktop-only).
	MountsSupported bool `json:"mounts_supported"`
}

// Notice is a user-facing notification pushed to every open UI as an SSE
// "notice" event, e.g. a completed push or download. The UI turns it into a
// toast; the fields are structured so the client can format sizes and names.
type Notice struct {
	// Kind is one of: download, send, receive, download-start, send-start,
	// receive-start, download-failed, send-failed.
	Kind  string `json:"kind"`
	Peer  string `json:"peer,omitempty"`
	Files int    `json:"files,omitempty"`
	Total int64  `json:"total,omitempty"`
	Label string `json:"label,omitempty"`
	Error string `json:"error,omitempty"`
}

type Deps struct {
	Self      func() SelfInfo
	Peers     func() []discovery.Peer
	Subscribe func() (<-chan struct{}, func())
	AddPeer   func(ctx context.Context, host string, port int, expectedFP string) (*discovery.Peer, error)
	Log       *slog.Logger

	// Local shares, remote pull, and transfer jobs.
	Shares    *shares.Manager
	Transfers *transfer.Manager
	Client    *peerapi.Client

	// Trust store and pairing/connect sessions.
	Trust  *trust.Store
	SelfFP string

	// Incoming transfers waiting for a person to accept them.
	Approvals *approval.Manager
	// Inbox holds pushes being received (shown with a Cancel button).
	Inbox *inbox.Manager
	// OpenFolder reveals a folder in the OS file manager. Nil uses the platform
	// default (xdg-open / open / explorer).
	OpenFolder func(path string) error
	// Mounts serves paired devices as drives (spec §11.2).
	Mounts *mount.Manager

	// XferLog is the shared four-area diagnostics recorder shown in the
	// diagnostics panel and copied by "Copy log".
	XferLog *xferlog.Recorder

	// Settings.
	Cfg           *config.Store
	ApplySettings func(config.Settings)
	// SetStartOnLogin registers/removes the OS sign-in entry; StartOnLoginEnabled
	// reports what the OS currently has.
	SetStartOnLogin     func(enable bool) error
	StartOnLoginEnabled func() bool
}

type Server struct {
	d     Deps
	token string
	port  int
	srv   *http.Server

	subMu sync.Mutex
	subs  map[chan struct{}]struct{}

	noticeMu   sync.Mutex
	noticeSubs map[chan Notice]struct{}
}

func New(d Deps) *Server {
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	return &Server{d: d, token: hex.EncodeToString(b),
		subs: map[chan struct{}]struct{}{}, noticeSubs: map[chan Notice]struct{}{}}
}

// Notify wakes every event stream (shares/transfers changed).
func (s *Server) Notify() {
	s.subMu.Lock()
	defer s.subMu.Unlock()
	for ch := range s.subs {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}

// NotifyUser delivers a one-off notification to every open UI. It is dropped
// for a connection whose buffer is full rather than blocking the caller (an
// SSE stream makes no progress while the window is closed).
func (s *Server) NotifyUser(n Notice) {
	s.noticeMu.Lock()
	defer s.noticeMu.Unlock()
	for ch := range s.noticeSubs {
		select {
		case ch <- n:
		default:
		}
	}
}

func (s *Server) notices() (<-chan Notice, func()) {
	ch := make(chan Notice, 16)
	s.noticeMu.Lock()
	s.noticeSubs[ch] = struct{}{}
	s.noticeMu.Unlock()
	return ch, func() {
		s.noticeMu.Lock()
		delete(s.noticeSubs, ch)
		s.noticeMu.Unlock()
	}
}

func (s *Server) changes() (<-chan struct{}, func()) {
	ch := make(chan struct{}, 1)
	s.subMu.Lock()
	s.subs[ch] = struct{}{}
	s.subMu.Unlock()
	return ch, func() {
		s.subMu.Lock()
		delete(s.subs, ch)
		s.subMu.Unlock()
	}
}

func (s *Server) Token() string { return s.token }
func (s *Server) Port() int     { return s.port }

// URL is the launch URL including the one-time token.
func (s *Server) URL() string {
	return fmt.Sprintf("http://127.0.0.1:%d/?t=%s", s.port, s.token)
}

func (s *Server) Listen(preferred int) (net.Listener, error) {
	ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", preferred))
	if err != nil {
		ln, err = net.Listen("tcp", "127.0.0.1:0")
		if err != nil {
			return nil, err
		}
	}
	s.port = ln.Addr().(*net.TCPAddr).Port
	return ln, nil
}

func (s *Server) Serve(ln net.Listener) error {
	sub, err := fs.Sub(webFS, "web")
	if err != nil {
		return err
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/ping", func(w http.ResponseWriter, r *http.Request) {
		// Deliberately unauthenticated and content-free: lets a second launch
		// detect a running instance.
		writeJSON(w, map[string]string{"app": "lanyard"})
	})
	mux.HandleFunc("GET /api/self", s.auth(s.handleSelf))
	mux.HandleFunc("GET /api/peers", s.auth(func(w http.ResponseWriter, r *http.Request) { writeJSON(w, s.d.Peers()) }))
	mux.HandleFunc("POST /api/peers/add", s.auth(s.handleAdd))
	mux.HandleFunc("GET /api/fs/roots", s.auth(s.handleFSRoots))
	mux.HandleFunc("GET /api/fs/list", s.auth(s.handleFSList))
	mux.HandleFunc("POST /api/fs/pick", s.auth(s.handleFSPick))
	mux.HandleFunc("GET /api/incoming", s.auth(s.handleIncoming))
	mux.HandleFunc("POST /api/incoming/{id}/cancel", s.auth(s.handleIncomingCancel))
	mux.HandleFunc("GET /api/snippets", s.auth(s.handleSnippets))
	mux.HandleFunc("POST /api/snippet", s.auth(s.handleSendSnippet))
	mux.HandleFunc("POST /api/snippets/{id}/dismiss", s.auth(s.handleSnippetDismiss))
	mux.HandleFunc("GET /api/shares", s.auth(s.handleShares))
	mux.HandleFunc("POST /api/shares", s.auth(s.handleShareAdd))
	mux.HandleFunc("POST /api/shares/stop-all", s.auth(s.handleShareStopAll))
	mux.HandleFunc("POST /api/shares/{id}/stop", s.auth(s.handleShareStop))
	mux.HandleFunc("GET /api/remote/shares", s.auth(s.handleRemoteShares))
	mux.HandleFunc("GET /api/remote/tree", s.auth(s.handleRemoteTree))
	mux.HandleFunc("GET /api/transfers", s.auth(s.handleTransfers))
	mux.HandleFunc("POST /api/transfers", s.auth(s.handleTransferCreate))
	mux.HandleFunc("POST /api/transfers/{id}/pause", s.auth(s.handleTransferPause))
	mux.HandleFunc("POST /api/transfers/{id}/resume", s.auth(s.handleTransferResume))
	mux.HandleFunc("POST /api/transfers/{id}/cancel", s.auth(s.handleTransferCancel))
	mux.HandleFunc("POST /api/push", s.auth(s.handlePush))
	mux.HandleFunc("GET /api/mounts", s.auth(s.handleMounts))
	mux.HandleFunc("GET /api/mounts/letters", s.auth(s.handleDriveLetters))
	mux.HandleFunc("POST /api/mounts", s.auth(s.handleMountAdd))
	mux.HandleFunc("POST /api/mounts/{id}/remove", s.auth(s.handleMountRemove))
	mux.HandleFunc("GET /api/approvals", s.auth(s.handleApprovals))
	mux.HandleFunc("POST /api/approvals/{id}/accept", s.auth(s.handleApprovalDecide(true)))
	mux.HandleFunc("POST /api/approvals/{id}/reject", s.auth(s.handleApprovalDecide(false)))
	mux.HandleFunc("GET /api/settings", s.auth(s.handleSettingsGet))
	mux.HandleFunc("PUT /api/settings", s.auth(s.handleSettingsPut))
	mux.HandleFunc("GET /api/diagnostics", s.auth(s.handleDiagnostics))
	mux.HandleFunc("GET /api/update", s.auth(s.handleUpdateStatus))
	mux.HandleFunc("POST /api/update/check", s.auth(s.handleUpdateCheck))
	mux.HandleFunc("POST /api/update/download", s.auth(s.handleUpdateDownload))
	mux.HandleFunc("POST /api/cancel-all", s.auth(s.handleCancelAll))
	mux.HandleFunc("POST /api/transfers/clear-finished", s.auth(s.handleTransfersClear))
	mux.HandleFunc("POST /api/transfers/clear-history", s.auth(s.handleTransfersClearHistory))
	mux.HandleFunc("POST /api/transfers/{id}/retry", s.auth(s.handleTransferRetry))
	mux.HandleFunc("GET /api/trust", s.auth(s.handleTrust))
	mux.HandleFunc("POST /api/trust/{fp}/unpair", s.auth(s.handleUnpair))
	mux.HandleFunc("POST /api/trust/{fp}/permissions", s.auth(s.handleTrustPermissions))
	mux.HandleFunc("POST /api/trust/{fp}/alias", s.auth(s.handleTrustAlias))
	mux.HandleFunc("GET /api/sessions", s.auth(s.handleSessions))
	mux.HandleFunc("GET /api/pair/payload", s.auth(s.handlePairPayload))
	mux.HandleFunc("POST /api/sessions/request", s.auth(s.handleSessionStart))
	mux.HandleFunc("POST /api/sessions/{id}/accept", s.auth(s.handleSessionAccept))
	mux.HandleFunc("POST /api/sessions/{id}/reject", s.auth(s.handleSessionReject))
	mux.HandleFunc("POST /api/sessions/{id}/refresh", s.auth(s.handleSessionRefresh))
	mux.HandleFunc("POST /api/sessions/{id}/confirm", s.auth(s.handleSessionConfirm))
	mux.HandleFunc("POST /api/sessions/{id}/close", s.auth(s.handleSessionClose))
	mux.HandleFunc("POST /api/sessions/{id}/offers", s.auth(s.handleSessionOffers))
	mux.HandleFunc("POST /api/sessions/{id}/keep", s.auth(s.handleSessionKeep))
	mux.HandleFunc("POST /api/inbox/open", s.auth(s.handleInboxOpen))
	mux.HandleFunc("GET /api/events", s.auth(s.handleEvents))
	mux.Handle("GET /", http.FileServerFS(sub))

	s.srv = &http.Server{
		Handler:           s.guard(mux),
		ReadHeaderTimeout: 10 * time.Second,
	}
	err = s.srv.Serve(ln)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

func (s *Server) Shutdown(ctx context.Context) error {
	if s.srv == nil {
		return nil
	}
	return s.srv.Shutdown(ctx)
}

func (s *Server) allowedHost(h string) bool {
	for _, a := range []string{"127.0.0.1", "localhost", "[::1]"} {
		if h == fmt.Sprintf("%s:%d", a, s.port) {
			return true
		}
	}
	return false
}

// guard enforces the Host allow-list, Origin check and the token handshake.
func (s *Server) guard(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if !s.allowedHost(r.Host) {
			http.Error(w, "forbidden host", http.StatusForbidden)
			return
		}
		if r.Method != http.MethodGet && r.Method != http.MethodHead {
			o := r.Header.Get("Origin")
			if o == "" {
				http.Error(w, "origin required", http.StatusForbidden)
				return
			}
			u, err := url.Parse(o)
			if err != nil || !s.allowedHost(u.Host) {
				http.Error(w, "forbidden origin", http.StatusForbidden)
				return
			}
		}
		// Token handshake: ?t=... sets a cookie and redirects to a clean URL.
		if t := r.URL.Query().Get("t"); t != "" && r.Method == http.MethodGet {
			if subtle.ConstantTimeCompare([]byte(t), []byte(s.token)) == 1 {
				http.SetCookie(w, &http.Cookie{
					Name: "lany", Value: s.token, Path: "/",
					HttpOnly: true, SameSite: http.SameSiteStrictMode,
				})
				http.Redirect(w, r, "/", http.StatusSeeOther)
				return
			}
			http.Error(w, "bad token", http.StatusForbidden)
			return
		}
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'self'; style-src 'self' 'unsafe-inline'; frame-ancestors 'none'")
		next.ServeHTTP(w, r)
	})
}

func (s *Server) auth(h http.HandlerFunc) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		c, err := r.Cookie("lany")
		if err != nil || subtle.ConstantTimeCompare([]byte(c.Value), []byte(s.token)) != 1 {
			http.Error(w, "unauthorized", http.StatusUnauthorized)
			return
		}
		h(w, r)
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// handleSelf returns this device's identity and the peer service's actually
// bound port (peer_port), which the UI uses for the firewall banner when it is
// not the default 47800.
func (s *Server) handleSelf(w http.ResponseWriter, r *http.Request) {
	writeJSON(w, s.d.Self())
}

// peerErrorMessage maps a failure to reach or be accepted by a peer to the
// line the UI shows. A 403 is a pairing/permission refusal, not an unreachable
// device, so it must never be reported as "could not reach device".
//
// This is the desktop-side mapping for the "not paired" wording (task c):
// internal/uiserver/uiserver.go:peerErrorMessage, with the shared decision in
// internal/peerapi/errors.go:peerapi.UserMessage.
func peerErrorMessage(err error) string {
	if err == nil {
		return ""
	}
	var se *peerapi.StatusError
	if errors.As(err, &se) && se.Code == http.StatusForbidden {
		return peerapi.UserMessage(err)
	}
	return "could not reach device: " + err.Error()
}

// peerUIMessage is peerErrorMessage without the "could not reach device"
// prefix, for handlers that already returned a bare error string. It still
// maps a 403 to the "not paired" wording instead of passing the raw status.
func peerUIMessage(err error) string {
	if err == nil {
		return ""
	}
	var se *peerapi.StatusError
	if errors.As(err, &se) && se.Code == http.StatusForbidden {
		return peerapi.UserMessage(err)
	}
	return err.Error()
}

// peerStatus is the HTTP status the local UI should receive for a failed peer
// request. A 403 refusal from the peer stays a 403 so the web UI takes the
// "not paired" branch instead of prefixing the message with "Could not reach";
// anything else is a gateway failure.
func peerStatus(err error) int {
	var se *peerapi.StatusError
	if errors.As(err, &se) && se.Code == http.StatusForbidden {
		return http.StatusForbidden
	}
	return http.StatusBadGateway
}

// peerRefusedPairing reacts to a peer we believe we are paired with answering a
// request with the exact 403 "not paired" over a certificate-pinned connection:
// the peer has unpaired us, so the stale local pairing is removed and the
// person is told. It reports whether a local pairing was dropped. A specific 403
// (a permission denial) is left alone: it means the pairing is intact but the
// action is not allowed. The removal only runs when peerapi.IsNotPaired holds,
// which requires the peer's certificate to have been verified against its
// pinned fingerprint; an unpinned answer can never drop a pairing.
func (s *Server) peerRefusedPairing(fp string, err error) bool {
	if s.d.Trust == nil || fp == "" || !peerapi.IsNotPaired(err) {
		return false
	}
	e, ok := s.d.Trust.Entry(fp)
	if !ok {
		return false
	}
	s.d.Trust.Unpair(fp)
	s.d.Trust.ClearPendingUnpair(fp)
	s.dropMountsOf(fp)
	if s.d.Log != nil {
		s.d.Log.Info("peer refused a request as not paired; removed the local pairing", "fp", fp, "name", e.Name)
	}
	s.NotifyUser(Notice{Kind: "peer-unpaired", Peer: firstNonEmpty(e.DisplayName(), fp)})
	return true
}

func (s *Server) handleAdd(w http.ResponseWriter, r *http.Request) {
	var req struct {
		Address     string `json:"address"`
		Fingerprint string `json:"fingerprint"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	addr := strings.TrimSpace(req.Address)
	host, portStr, err := net.SplitHostPort(addr)
	if err != nil {
		host, portStr = addr, "47800"
	}
	var port int
	if _, err := fmt.Sscanf(portStr, "%d", &port); err != nil || port < 1 || port > 65535 || host == "" {
		http.Error(w, "address must be host or host:port", http.StatusBadRequest)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 5*time.Second)
	defer cancel()
	fp := strings.TrimSpace(req.Fingerprint)
	p, err := s.d.AddPeer(ctx, host, port, fp)
	if err != nil {
		s.peerRefusedPairing(fp, err)
		http.Error(w, peerErrorMessage(err), peerStatus(err))
		return
	}
	writeJSON(w, p)
}

// handleEvents streams peers, shares and transfer snapshots (SSE). Sends are
// throttled so very fast transfer progress cannot flood the UI.
func (s *Server) handleEvents(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Connection", "keep-alive")
	peerCh, cancelPeers := s.d.Subscribe()
	defer cancelPeers()
	changeCh, cancelChanges := s.changes()
	defer cancelChanges()
	noticeCh, cancelNotices := s.notices()
	defer cancelNotices()

	send := func() {
		b, _ := json.Marshal(s.d.Peers())
		fmt.Fprintf(w, "event: peers\ndata: %s\n\n", b)
		if s.d.Shares != nil {
			if sb, err := json.Marshal(s.d.Shares.LocalViews()); err == nil {
				fmt.Fprintf(w, "event: shares\ndata: %s\n\n", sb)
			}
		}
		if s.d.Mounts != nil {
			if mb, err := json.Marshal(s.d.Mounts.List()); err == nil {
				fmt.Fprintf(w, "event: mounts\ndata: %s\n\n", mb)
			}
		}
		if s.d.Approvals != nil {
			if ab, err := json.Marshal(s.d.Approvals.Pending()); err == nil {
				fmt.Fprintf(w, "event: approvals\ndata: %s\n\n", ab)
			}
		}
		if ib, err := json.Marshal(s.incoming()); err == nil {
			fmt.Fprintf(w, "event: incoming\ndata: %s\n\n", ib)
		}
		if sb, err := json.Marshal(s.snippets()); err == nil {
			fmt.Fprintf(w, "event: snippets\ndata: %s\n\n", sb)
		}
		if s.d.Transfers != nil {
			if tb, err := json.Marshal(s.d.Transfers.List()); err == nil {
				fmt.Fprintf(w, "event: transfers\ndata: %s\n\n", tb)
			}
		}
		if s.d.Trust != nil {
			if db, err := json.Marshal(s.d.Trust.Paired()); err == nil {
				fmt.Fprintf(w, "event: trust\ndata: %s\n\n", db)
			}
			if sb, err := json.Marshal(s.d.Trust.Sessions()); err == nil {
				fmt.Fprintf(w, "event: sessions\ndata: %s\n\n", sb)
			}
		}
		fl.Flush()
	}
	send()

	keep := time.NewTicker(20 * time.Second)
	defer keep.Stop()
	var throttle <-chan time.Time
	pending := false
	last := time.Now()
	for {
		select {
		case <-r.Context().Done():
			return
		case <-peerCh:
			pending = true
		case n := <-noticeCh:
			if nb, err := json.Marshal(n); err == nil {
				fmt.Fprintf(w, "event: notice\ndata: %s\n\n", nb)
				fl.Flush()
			}
			continue
		case <-changeCh:
			pending = true
		case <-keep.C:
			fmt.Fprint(w, ": keepalive\n\n")
			fl.Flush()
			continue
		case <-throttle:
			pending = true
			throttle = nil
		}
		if pending && throttle == nil {
			if d := 250*time.Millisecond - time.Since(last); d > 0 {
				throttle = time.After(d)
				continue
			}
			pending = false
			last = time.Now()
			send()
		}
	}
}
