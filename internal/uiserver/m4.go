package uiserver

import (
	"context"
	"encoding/json"
	"errors"
	"net/http"
	"strings"
	"time"

	"lanyard/internal/peerapi"
	"lanyard/internal/trust"
)

func (s *Server) handleTrust(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		writeJSON(w, []any{})
		return
	}
	writeJSON(w, s.d.Trust.Paired())
}

func (s *Server) handleUnpair(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "trust unavailable", http.StatusServiceUnavailable)
		return
	}
	if !s.d.Trust.Unpair(r.PathValue("fp")) {
		http.Error(w, "not paired", http.StatusNotFound)
		return
	}
	fp := r.PathValue("fp")
	s.dropMountsOf(fp) // unpairing revokes the drive
	s.revokeRemote(fp) // make the unpair mutual, best effort
	writeJSON(w, map[string]bool{"ok": true})
}

// revokeRemote tells the paired device to drop us too, so an unpair on one side
// is reflected on the other. Best effort: the peer may be offline, and the local
// unpair already succeeded.
func (s *Server) revokeRemote(fp string) {
	if s.d.Client == nil || s.d.Peers == nil {
		return
	}
	var host string
	var port int
	for _, p := range s.d.Peers() {
		if p.DeviceID == fp && len(p.Addrs) > 0 {
			host, port = p.Addrs[0], p.Port
			break
		}
	}
	if host == "" || port == 0 {
		return
	}
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := s.d.Client.RevokePairing(ctx, host, port, fp); err != nil && s.d.Log != nil {
		s.d.Log.Debug("unpair: could not notify the peer", "fp", fp, "err", err)
	}
}

// handleTrustPermissions edits a paired device's permissions without re-pairing.
func (s *Server) handleTrustPermissions(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "trust unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Browse       bool  `json:"browse"`
		Push         bool  `json:"push"`
		PushMaxBytes int64 `json:"push_max_bytes"`
		AskOver      int64 `json:"ask_over"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if req.PushMaxBytes < 0 || req.AskOver < 0 {
		http.Error(w, "sizes must not be negative", http.StatusBadRequest)
		return
	}
	fp := r.PathValue("fp")
	perms := trust.Permissions{Browse: req.Browse, Push: req.Push, PushMaxBytes: req.PushMaxBytes, AskOver: req.AskOver}
	if !s.d.Trust.UpdatePermissions(fp, perms) {
		http.Error(w, "not paired", http.StatusNotFound)
		return
	}
	e, _ := s.d.Trust.Entry(fp)
	s.Notify()
	writeJSON(w, e)
}

func (s *Server) handleSessions(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		writeJSON(w, []any{})
		return
	}
	writeJSON(w, s.d.Trust.Sessions())
}

// handleSessionStart initiates Connect or Pair with a discovered device.
func (s *Server) handleSessionStart(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil || s.d.Client == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Device        string            `json:"device"`
		Mode          string            `json:"mode"`
		Permissions   trust.Permissions `json:"permissions"`
		KeepConnected bool              `json:"keep_connected"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if req.Mode != trust.ModeConnect && req.Mode != trust.ModePair {
		http.Error(w, "mode must be connect or pair", http.StatusBadRequest)
		return
	}
	p, ok := s.peerByID(req.Device)
	if !ok {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	host, port, ok := peerAddr(p)
	if !ok {
		http.Error(w, "device has no reachable address", http.StatusBadGateway)
		return
	}
	self := s.d.Self()
	sess := s.d.Trust.CreateOutgoing(req.Mode, p.DeviceID, firstNonEmpty(p.Name, req.Device), self.Name, req.Permissions)
	nonce, _ := s.d.Trust.SelfNonce(sess.ID)
	resp, err := s.d.Client.StartSession(r.Context(), host, port, p.DeviceID, peerapi.SessionRequestPayload{
		Mode: req.Mode, Name: self.Name, DeviceID: self.Name,
		Nonce: nonce, Requested: req.Permissions,
	})
	if err != nil {
		s.d.Trust.Close(sess.ID)
		http.Error(w, "could not reach device: "+err.Error(), http.StatusBadGateway)
		return
	}
	s.d.Trust.SetRemote(sess.ID, resp.SessionID, resp.Nonce)
	if req.KeepConnected {
		s.d.Trust.SetKeepConnected(sess.ID, true)
	}
	view, _ := s.d.Trust.View(sess.ID)
	writeJSON(w, view)
}

func (s *Server) handleSessionAccept(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Permissions   trust.Permissions `json:"permissions"`
		KeepConnected bool              `json:"keep_connected"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	id := r.PathValue("id")
	if _, err := s.d.Trust.Accept(id, req.Permissions); err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	if req.KeepConnected {
		s.d.Trust.SetKeepConnected(id, true)
	}
	view, _ := s.d.Trust.View(id)
	writeJSON(w, view)
}

func (s *Server) handleSessionReject(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	if !s.d.Trust.Reject(r.PathValue("id")) {
		http.Error(w, "no such session", http.StatusNotFound)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

// handleSessionRefresh polls the responder's view of an outgoing session so the
// initiator learns when it was accepted (the responder does not push).
func (s *Server) handleSessionRefresh(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil || s.d.Client == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	id := r.PathValue("id")
	sess, ok := s.d.Trust.Snapshot(id)
	if !ok || sess.Incoming {
		http.Error(w, "no such session", http.StatusNotFound)
		return
	}
	if sess.RemoteID != "" && (sess.Status == trust.StatusPending || sess.Status == trust.StatusAccepted) {
		if p, ok := s.peerByID(sess.PeerFP); ok {
			if host, port, ok := peerAddr(p); ok {
				if st, err := s.d.Client.SessionStatus(r.Context(), host, port, sess.PeerFP, sess.RemoteID); err == nil && st.Status != "" {
					s.d.Trust.SetStatus(id, st.Status, st.Error)
				}
			}
		}
	}
	view, _ := s.d.Trust.View(id)
	writeJSON(w, view)
}

// handleSessionConfirm is the initiator confirming the SAS, which for a Pair
// also stores the trust entry locally and activates the session.
func (s *Server) handleSessionConfirm(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil || s.d.Client == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	id := r.PathValue("id")
	sess, ok := s.d.Trust.Snapshot(id)
	if !ok || sess.Incoming {
		http.Error(w, "no such session", http.StatusNotFound)
		return
	}
	// Do not confirm the peer until we know it accepted; otherwise the peer
	// activates while our own session is still pending.
	if sess.Status == trust.StatusPending {
		http.Error(w, "the other device has not accepted yet", http.StatusConflict)
		return
	}
	p, ok := s.peerByID(sess.PeerFP)
	if !ok {
		http.Error(w, "device is not visible", http.StatusBadGateway)
		return
	}
	host, port, ok := peerAddr(p)
	if !ok {
		http.Error(w, "device has no reachable address", http.StatusBadGateway)
		return
	}
	if sess.RemoteID != "" {
		if err := s.d.Client.ConfirmSession(r.Context(), host, port, sess.PeerFP, sess.RemoteID); err != nil {
			var se *peerapi.StatusError
			if errors.As(err, &se) {
				http.Error(w, se.Msg, se.Code)
				return
			}
			http.Error(w, err.Error(), http.StatusBadGateway)
			return
		}
	}
	view, err := s.d.Trust.Confirm(id)
	if err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	out, _ := s.d.Trust.View(view.ID)
	writeJSON(w, out)
}

func (s *Server) handleSessionClose(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	id := r.PathValue("id")
	sess, ok := s.d.Trust.Snapshot(id)
	if !ok {
		http.Error(w, "no such session", http.StatusNotFound)
		return
	}
	if sess.RemoteID != "" && s.d.Client != nil {
		if p, ok := s.peerByID(sess.PeerFP); ok {
			if host, port, ok := peerAddr(p); ok {
				_ = s.d.Client.CloseSession(r.Context(), host, port, sess.PeerFP, sess.RemoteID)
			}
		}
	}
	s.d.Trust.Close(id)
	writeJSON(w, map[string]bool{"ok": true})
}

// handleSessionOffers records which shares are offered into a Connect session.
func (s *Server) handleSessionOffers(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		ShareIDs []string `json:"share_ids"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	ids := make([]string, 0, len(req.ShareIDs))
	for _, id := range req.ShareIDs {
		ids = append(ids, strings.TrimSpace(id))
	}
	if err := s.d.Trust.Offer(r.PathValue("id"), ids); err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	view, _ := s.d.Trust.View(r.PathValue("id"))
	writeJSON(w, view)
}

func (s *Server) handleSessionKeep(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Keep bool `json:"keep"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 1024)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if !s.d.Trust.SetKeepConnected(r.PathValue("id"), req.Keep) {
		http.Error(w, "no such session", http.StatusNotFound)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}
