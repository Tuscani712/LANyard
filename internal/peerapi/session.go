package peerapi

import (
	"encoding/json"
	"net/http"
	"strings"
	"sync"
	"time"
	"unicode"

	"lanyard/internal/trust"
)

// rateLimiter is a small sliding-window limiter so unauthenticated handshake
// endpoints cannot be flooded.
type rateLimiter struct {
	mu   sync.Mutex
	hits map[string][]time.Time
}

func (l *rateLimiter) allow(key string, max int, window time.Duration) bool {
	now := time.Now()
	l.mu.Lock()
	defer l.mu.Unlock()
	if l.hits == nil {
		l.hits = map[string][]time.Time{}
	}
	kept := l.hits[key][:0]
	for _, t := range l.hits[key] {
		if now.Sub(t) < window {
			kept = append(kept, t)
		}
	}
	if len(kept) >= max {
		l.hits[key] = kept
		return false
	}
	l.hits[key] = append(kept, now)
	return true
}

type sessionRequestBody struct {
	Mode      string            `json:"mode"`
	Name      string            `json:"name"`
	DeviceID  string            `json:"device_id"`
	Nonce     string            `json:"nonce"`
	Requested trust.Permissions `json:"requested_permissions"`
}

type sessionRequestResp struct {
	SessionID string `json:"session_id"`
	Nonce     string `json:"nonce"`
	Status    string `json:"status"`
}

type sessionStatusResp struct {
	Status  string            `json:"status"`
	Mode    string            `json:"mode"`
	Nonce   string            `json:"nonce"`
	Granted trust.Permissions `json:"granted"`
	Error   string            `json:"error,omitempty"`
}

// handleSessionRequest starts a Connect or Pair handshake. Any certificate may
// reach this endpoint (spec §3.3): the trust decision is made when the peer
// later uses data endpoints.
func (s *Server) handleSessionRequest(w http.ResponseWriter, r *http.Request) {
	if s.trust == nil {
		http.Error(w, "sessions unavailable", http.StatusServiceUnavailable)
		return
	}
	fp := PeerID(r.Context())
	if !s.rl.allow("session:"+fp, 10, time.Minute) {
		http.Error(w, "too many requests", http.StatusTooManyRequests)
		return
	}
	var req sessionRequestBody
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if req.Mode != trust.ModeConnect && req.Mode != trust.ModePair {
		http.Error(w, "mode must be connect or pair", http.StatusBadRequest)
		return
	}
	if len(req.Nonce) < 16 || len(req.Nonce) > 128 {
		http.Error(w, "nonce required", http.StatusBadRequest)
		return
	}
	sess, err := s.trust.CreateIncoming(req.Mode, fp, cleanLabel(req.Name), cleanLabel(req.DeviceID), req.Nonce, req.Requested)
	if err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	self, _ := s.trust.SelfNonce(sess.ID)
	writeJSON(w, sessionRequestResp{SessionID: sess.ID, Nonce: self, Status: sess.Status})
}

// handleSessionStatus lets the requester poll for the responder's nonce and
// decision.
func (s *Server) handleSessionStatus(w http.ResponseWriter, r *http.Request) {
	sess, ok := s.sessionForPeer(w, r)
	if !ok {
		return
	}
	self, _ := s.trust.SelfNonce(sess.ID)
	writeJSON(w, sessionStatusResp{
		Status: sess.Status, Mode: sess.Mode, Nonce: self, Granted: sess.Granted, Error: sess.Error,
	})
}

// handleSessionConfirm is called by the initiator against the responder once
// both users agree the SAS matches. The responder already stored the trust
// entry when it accepted.
func (s *Server) handleSessionConfirm(w http.ResponseWriter, r *http.Request) {
	sess, ok := s.sessionForPeer(w, r)
	if !ok {
		return
	}
	if !sess.Incoming {
		http.Error(w, "only the requester confirms", http.StatusForbidden)
		return
	}
	if sess.Status == trust.StatusPending {
		http.Error(w, "the other device has not accepted yet", http.StatusConflict)
		return
	}
	if _, err := s.trust.ActivateRemote(sess.ID); err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	writeJSON(w, map[string]string{"status": trust.StatusActive})
}

// handleSessionClose ends a session from either side.
func (s *Server) handleSessionClose(w http.ResponseWriter, r *http.Request) {
	sess, ok := s.sessionForPeer(w, r)
	if !ok {
		return
	}
	if r.URL.Query().Get("reason") == "transfer" {
		// The peer finished its transfer: end the session unless we keep it.
		writeJSON(w, map[string]bool{"closed": s.trust.CloseAfterTransfer(sess.ID)})
		return
	}
	s.trust.Close(sess.ID)
	writeJSON(w, map[string]bool{"closed": true})
}

// sessionForPeer resolves {id} and requires the caller to be the session peer.
func (s *Server) sessionForPeer(w http.ResponseWriter, r *http.Request) (trust.Session, bool) {
	if s.trust == nil {
		http.Error(w, "sessions unavailable", http.StatusServiceUnavailable)
		return trust.Session{}, false
	}
	id := r.PathValue("id")
	sess, ok := s.trust.Snapshot(id)
	if !ok {
		http.Error(w, "session not found", http.StatusNotFound)
		return trust.Session{}, false
	}
	if sess.PeerFP != PeerID(r.Context()) {
		http.Error(w, "not your session", http.StatusForbidden)
		return trust.Session{}, false
	}
	return sess, true
}

// cleanLabel truncates and strips control characters from attacker-supplied
// names before they are stored or shown.
func cleanLabel(s string) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, s)
	s = strings.TrimSpace(s)
	if len(s) > 64 {
		s = s[:64]
	}
	return s
}
