// Package peerapi is the network-facing HTTPS service: mutual TLS 1.3 with
// self-signed certificates identified by fingerprint. Authorization (trust
// store, sessions) is layered on in later milestones; in M1 only /hello exists.
package peerapi

import (
	"context"
	"crypto/tls"
	"crypto/x509"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"time"

	"lanyard/internal/approval"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/shares"
	"lanyard/internal/trust"
)

// Authorizer decides what an authenticated peer certificate may do.
type Authorizer interface {
	Access(peerFP string) trust.Access
}

type allowAll struct{}

func (allowAll) Access(string) trust.Access {
	return trust.Access{Paired: true, Browse: true, Push: true}
}

// AllowAll bypasses the trust store. It is used only by package tests; the
// shipped server always authorizes through the trust store.
func AllowAll() Authorizer { return allowAll{} }

type ctxKey int

const peerIDKey ctxKey = 1

// PeerID returns the authenticated certificate fingerprint of the caller.
func PeerID(ctx context.Context) string {
	s, _ := ctx.Value(peerIDKey).(string)
	return s
}

type Server struct {
	id        *identity.Identity
	hello     func() discovery.Hello
	shares    *shares.Manager
	trust     *trust.Store
	inbox     *inbox.Manager
	approvals *approval.Manager
	auth      Authorizer
	hashes    hashCache
	rl        rateLimiter
	log       *slog.Logger
	srv       *http.Server
	port      int
}

func NewServer(id *identity.Identity, hello func() discovery.Hello, sh *shares.Manager, tr *trust.Store, auth Authorizer, log *slog.Logger) *Server {
	return &Server{id: id, hello: hello, shares: sh, trust: tr, auth: auth, log: log}
}

func (s *Server) tlsConfig() *tls.Config {
	return &tls.Config{
		Certificates: []tls.Certificate{s.id.Cert},
		MinVersion:   tls.VersionTLS13,
		// Every caller must present a certificate. Which certificates are
		// *allowed* to do what is decided per request, not here.
		ClientAuth: tls.RequireAnyClientCert,
	}
}

// Listen binds the preferred port, falling back to any free port.
func (s *Server) Listen(preferred int) (net.Listener, error) {
	ln, err := net.Listen("tcp", fmt.Sprintf(":%d", preferred))
	if err != nil {
		s.log.Info("preferred port unavailable, choosing a random one", "port", preferred, "err", err)
		ln, err = net.Listen("tcp", ":0")
		if err != nil {
			return nil, err
		}
	}
	s.port = ln.Addr().(*net.TCPAddr).Port
	return ln, nil
}

func (s *Server) Port() int { return s.port }

func (s *Server) Serve(ln net.Listener) error {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/hello", s.handleHello)
	mux.HandleFunc("POST /api/v1/session/request", s.handleSessionRequest)
	mux.HandleFunc("GET /api/v1/session/{id}", s.handleSessionStatus)
	mux.HandleFunc("POST /api/v1/session/{id}/confirm", s.handleSessionConfirm)
	mux.HandleFunc("POST /api/v1/session/{id}/close", s.handleSessionClose)
	mux.HandleFunc("POST /api/v1/trust/revoke", s.handleTrustRevoke)
	mux.HandleFunc("POST /api/v1/push/offer", s.handlePushOffer)
	mux.HandleFunc("POST /api/v1/snippet", s.handleSnippet)
	mux.HandleFunc("PUT /api/v1/push/{id}/file", s.handlePushFile)
	mux.HandleFunc("POST /api/v1/push/{id}/complete", s.handlePushComplete)
	mux.HandleFunc("GET /api/v1/shares", s.handleShareList)
	mux.HandleFunc("GET /api/v1/shares/{id}/tree", s.handleTree)
	mux.HandleFunc("GET /api/v1/shares/{id}/manifest", s.handleManifest)
	mux.HandleFunc("GET /api/v1/shares/{id}/file", s.handleFile)
	mux.HandleFunc("GET /api/v1/shares/{id}/hash", s.handleHash)
	mux.HandleFunc("POST /api/v1/shares/{id}/complete", s.handleComplete)
	mux.HandleFunc("HEAD /api/v1/shares/{id}/file", s.handleFile)

	s.srv = &http.Server{
		Handler:           s.withPeer(mux),
		TLSConfig:         s.tlsConfig(),
		ReadHeaderTimeout: 10 * time.Second,
		IdleTimeout:       2 * time.Minute,
		ErrorLog:          slog.NewLogLogger(s.log.Handler(), slog.LevelDebug),
	}
	err := s.srv.ServeTLS(ln, "", "")
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

// withPeer attaches the caller's certificate fingerprint to the request context.
func (s *Server) withPeer(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.TLS == nil || len(r.TLS.PeerCertificates) == 0 {
			http.Error(w, "client certificate required", http.StatusUnauthorized)
			return
		}
		id := identity.FingerprintOf(r.TLS.PeerCertificates[0])
		next.ServeHTTP(w, r.WithContext(context.WithValue(r.Context(), peerIDKey, id)))
	})
}

func (s *Server) handleHello(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(s.hello())
}

// handleTrustRevoke lets an authenticated peer remove itself from our trust
// store. This is how an unpair on the other device becomes mutual: we only ever
// drop the caller's own entry, so it needs no extra authorization and is safe to
// repeat.
func (s *Server) handleTrustRevoke(w http.ResponseWriter, r *http.Request) {
	fp := PeerID(r.Context())
	if fp == "" {
		http.Error(w, "client certificate required", http.StatusUnauthorized)
		return
	}
	if s.trust != nil {
		s.trust.Unpair(fp)
	}
	writeJSON(w, map[string]bool{"ok": true})
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	_ = json.NewEncoder(w).Encode(v)
}

// --- client side ---

// Client makes mutual-TLS requests to peers. Peer certificates are never
// validated against a CA; callers receive the presented fingerprint and decide.
type Client struct {
	http *http.Client
}

func NewClient(id *identity.Identity) *Client {
	tr := &http.Transport{
		TLSClientConfig: &tls.Config{
			Certificates:       []tls.Certificate{id.Cert},
			MinVersion:         tls.VersionTLS13,
			InsecureSkipVerify: true, // identity is checked by fingerprint below, not by CA
		},
		ForceAttemptHTTP2:   true,
		MaxIdleConnsPerHost: 4,
		IdleConnTimeout:     30 * time.Second,
	}
	return &Client{http: &http.Client{Transport: tr}}
}

// Probe fetches /hello and returns the Device ID from the presented certificate.
func (c *Client) Probe(ctx context.Context, host string, port int) (string, *discovery.Hello, error) {
	u := "https://" + net.JoinHostPort(host, fmt.Sprint(port)) + "/api/v1/hello"
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, u, nil)
	if err != nil {
		return "", nil, err
	}
	resp, err := c.http.Do(req)
	if err != nil {
		return "", nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return "", nil, fmt.Errorf("hello: %s", resp.Status)
	}
	if resp.TLS == nil || len(resp.TLS.PeerCertificates) == 0 {
		return "", nil, errors.New("no peer certificate")
	}
	var leaf *x509.Certificate = resp.TLS.PeerCertificates[0]
	var h discovery.Hello
	if err := json.NewDecoder(http.MaxBytesReader(nil, resp.Body, 4096)).Decode(&h); err != nil {
		return "", nil, err
	}
	return identity.FingerprintOf(leaf), &h, nil
}
