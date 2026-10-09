package uiserver

import (
	"context"
	"encoding/json"
	"errors"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"lanyard/internal/lanaddr"
	"lanyard/internal/pairlink"
	"lanyard/internal/peerapi"
	"lanyard/internal/trust"
)

// handlePairPayload returns this device's QR pairing link: a one-time invite
// the scanning device uses to pin our fingerprint and pair without the SAS
// compare. The endpoint is token-gated like the others; the nonce is returned
// in the JSON body (the scanning side never puts it in a request URL).
func (s *Server) handlePairPayload(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "pairing unavailable", http.StatusServiceUnavailable)
		return
	}
	self := s.d.Self()
	// Reuse the invite the panel is already showing while it is still valid, so
	// the QR code stays put; mint a fresh one once it expired or was used.
	nonce := strings.TrimSpace(r.URL.Query().Get("nonce"))
	exp, ok := s.d.Trust.PairInviteValid(nonce)
	if !ok {
		nonce, exp = s.d.Trust.MintPairInvite()
	}
	port := self.PeerPort
	addrs := make([]string, 0, 8)
	for _, a := range lanaddr.Addrs() {
		addrs = append(addrs, net.JoinHostPort(a, strconv.Itoa(port)))
	}
	uri := pairlink.Build(pairlink.Payload{
		Fingerprint: self.DeviceID,
		Name:        self.Name,
		Addrs:       addrs,
		Nonce:       nonce,
	})
	writeJSON(w, map[string]any{
		"uri":        uri,
		"fp":         self.DeviceID,
		"name":       self.Name,
		"addrs":      addrs,
		"nonce":      nonce,
		"expires_at": exp,
	})
}

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
	fp := r.PathValue("fp")
	// Remember the last address we saw the peer answer at before the entry is
	// removed, so we can still ask it to drop us when it is not in the
	// discovery registry right now.
	lastKnown, _ := s.d.Trust.Entry(fp)
	if !s.d.Trust.Unpair(fp) {
		http.Error(w, "not paired", http.StatusNotFound)
		return
	}
	s.dropMountsOf(fp)            // unpairing revokes the drive
	s.revokeRemote(fp, lastKnown) // make the unpair mutual, best effort
	writeJSON(w, map[string]bool{"ok": true})
}

// HandleRemoteRevoke performs the local cleanup when a peer removes this device
// from its trust store over the peer API (/trust/revoke). It mirrors
// handleUnpair's post-removal cleanup: the peer's drive is unmounted and, when
// the peer was actually paired, it is told to drop us too. A repeat revoke from
// an already-unpaired peer is idempotent: the mounts are still dropped and the
// event logged, but no notification is sent (the peer is already gone, and
// notifying back would bounce between the two devices forever).
func (s *Server) HandleRemoteRevoke(fp string, wasPaired bool) {
	s.dropMountsOf(fp)
	if !wasPaired {
		if s.d.Log != nil {
			s.d.Log.Info("unpair: peer was already unpaired; nothing to notify", "fp", fp)
		}
		return
	}
	// The peer has already dropped us, so there is nothing to retry: this is a
	// courtesy notification, not one we owe a retry for.
	if s.d.Trust != nil {
		s.d.Trust.ClearPendingUnpair(fp)
	}
	// peerapi calls this before removing the entry, so the last-known address
	// is still in the trust store for the fallback below.
	var lastKnown trust.Entry
	if s.d.Trust != nil {
		lastKnown, _ = s.d.Trust.Entry(fp)
	}
	s.notifyUnpair(fp, lastKnown, false)
}

// revokeRemote tells the paired device to drop us too, so an unpair on one side
// is reflected on the other. Best effort: the peer may be offline, and the local
// unpair already succeeded. A failed attempt is remembered as a pending unpair
// so it is retried when the peer is next seen.
func (s *Server) revokeRemote(fp string, lastKnown trust.Entry) {
	s.notifyUnpair(fp, lastKnown, true)
}

// notifyUnpair delivers the unpair notification. It prefers the live discovery
// address and falls back to the last address the trust store remembered
// (captured before the entry was removed); if neither is known, or the peer
// cannot be reached, it logs why. When remember is set, a failure records a
// pending-unpair entry so RetryPendingUnpair can try again once the peer is
// seen (discovery, liveness or a paired-probe); a success clears any pending
// record. remember is false for a revoke the peer itself initiated, which needs
// no retry.
func (s *Server) notifyUnpair(fp string, lastKnown trust.Entry, remember bool) {
	// Capture the delivery generation and whether a revoke was already queued
	// before any work. A successful sibling bumps the generation and clears the
	// record, so a failure here must not re-arm it.
	var seen uint64
	hadPending := false
	if s.d.Trust != nil {
		seen = s.d.Trust.UnpairGeneration(fp)
		hadPending = s.d.Trust.HasPendingUnpair(fp)
	}
	// A pending revoke is refused when the device has been paired again with a
	// pairing newer than the revoke: delivering it would unpair the fresh
	// pairing. Drop the stale record instead of retrying it forever.
	if remember && s.d.Trust != nil && s.d.Trust.PendingUnpairSuperseded(fp) {
		s.d.Trust.ClearPendingUnpair(fp)
		if s.d.Log != nil {
			s.d.Log.Info("unpair: stale revoke dropped; the device was paired again", "fp", fp)
		}
		return
	}
	if s.d.Client == nil {
		if s.d.Log != nil {
			s.d.Log.Info("unpair: peer client unavailable; cannot notify the peer", "fp", fp)
		}
		s.rememberPendingUnpair(fp, lastKnown, remember, seen, hadPending)
		return
	}
	var host string
	var port int
	if s.d.Peers != nil {
		for _, p := range s.d.Peers() {
			if p.DeviceID == fp && len(p.Addrs) > 0 {
				host, port = p.Addrs[0], p.Port
				break
			}
		}
	}
	source := "discovery"
	if host == "" || port == 0 {
		// Not in the discovery registry right now (it may be off the network).
		// Fall back to the last address the trust store remembered.
		if len(lastKnown.Addrs) > 0 && lastKnown.Port > 0 {
			host, port = lastKnown.Addrs[0], lastKnown.Port
			source = "last-known address"
		}
	}
	if host == "" || port == 0 {
		if s.d.Log != nil {
			s.d.Log.Info("unpair: peer has no known address; skipping notification", "fp", fp)
		}
		s.rememberPendingUnpair(fp, lastKnown, remember, seen, hadPending)
		return
	}
	addr := net.JoinHostPort(host, strconv.Itoa(port))
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	if err := s.d.Client.RevokePairing(ctx, host, port, fp); err != nil {
		// A pinned 403 "not paired" means the peer has already dropped us: the
		// unpair has achieved its goal, so this is a success, not a failure.
		// Clear the record, stop retrying and do not warn.
		if peerapi.IsNotPaired(err) {
			if s.d.Trust != nil {
				s.d.Trust.MarkUnpairDelivered(fp)
				s.d.Trust.ClearPendingUnpair(fp)
			}
			if s.d.Log != nil {
				s.d.Log.Info("unpair: peer already dropped us; nothing more to do", "fp", fp, "addr", addr)
			}
			return
		}
		if s.d.Log != nil {
			s.d.Log.Warn("unpair: could not notify the peer", "fp", fp, "addr", addr, "via", source, "err", err)
		}
		s.rememberPendingUnpair(fp, lastKnown, remember, seen, hadPending)
		return
	}
	if s.d.Trust != nil {
		s.d.Trust.MarkUnpairDelivered(fp)
		s.d.Trust.ClearPendingUnpair(fp)
	}
	if s.d.Log != nil {
		s.d.Log.Info("unpair: notified the peer", "fp", fp, "addr", addr, "via", source)
	}
}

// rememberPendingUnpair keeps an unpair notification for a later retry, unless
// remember is false, there is nothing to key it on, or a sibling delivery has
// already resolved it (it cleared the record and/or bumped the generation),
// in which case re-arming would resurrect a revoke that already succeeded.
func (s *Server) rememberPendingUnpair(fp string, lastKnown trust.Entry, remember bool, seen uint64, hadPending bool) {
	if !remember || s.d.Trust == nil || fp == "" {
		return
	}
	if hadPending && !s.d.Trust.HasPendingUnpair(fp) {
		if s.d.Log != nil {
			s.d.Log.Info("unpair: duplicate retry ignored; a sibling already notified the peer", "fp", fp)
		}
		return
	}
	e := lastKnown
	e.Fingerprint = fp
	if !s.d.Trust.AddPendingUnpairIfGeneration(fp, seen, e) {
		if s.d.Log != nil {
			s.d.Log.Info("unpair: duplicate retry ignored; the peer was already notified", "fp", fp)
		}
		return
	}
	if !hadPending && s.d.Log != nil {
		s.d.Log.Info("unpair: will retry notifying the peer when it is next seen", "fp", fp)
	}
}

// RetryPendingUnpair re-attempts the unpair notification for a single device
// that has just been seen. It is a no-op when no notification is pending. On
// success the pending record is cleared; on failure it stays for the next sighting.
func (s *Server) RetryPendingUnpair(fp string) {
	if s.d.Trust == nil || fp == "" || !s.d.Trust.HasPendingUnpair(fp) {
		return
	}
	e, _ := s.d.Trust.PendingUnpair(fp)
	s.notifyUnpair(fp, e, true)
}

// RetryPendingUnpairs re-attempts every outstanding unpair notification. It is
// useful at startup or after a network change; RetryPendingUnpair is the
// per-device form used when discovery sees a particular device return.
func (s *Server) RetryPendingUnpairs() {
	if s.d.Trust == nil {
		return
	}
	for _, e := range s.d.Trust.PendingUnpairs() {
		s.notifyUnpair(e.Fingerprint, e, true)
	}
}

// handleTrustPermissions edits a paired device's permissions without re-pairing.
func (s *Server) handleTrustPermissions(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "trust unavailable", http.StatusServiceUnavailable)
		return
	}
	var perms trust.Permissions
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&perms); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if perms.PushMaxBytes < 0 || perms.AskOver < 0 {
		http.Error(w, "sizes must not be negative", http.StatusBadRequest)
		return
	}
	fp := r.PathValue("fp")
	if !s.d.Trust.UpdatePermissions(fp, perms) {
		http.Error(w, "not paired", http.StatusNotFound)
		return
	}
	e, _ := s.d.Trust.Entry(fp)
	s.Notify()
	writeJSON(w, e)
}

// handleTrustAlias sets or clears a paired device's local alias. The alias is
// stored locally and is never sent to the peer: it only changes the name this
// device shows. An empty alias clears it, reverting to the broadcast name.
func (s *Server) handleTrustAlias(w http.ResponseWriter, r *http.Request) {
	if s.d.Trust == nil {
		http.Error(w, "trust unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Alias string `json:"alias"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 4096)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	fp := r.PathValue("fp")
	if !s.d.Trust.SetAlias(fp, cleanText(req.Alias, 64)) {
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
		// Invite is a one-time QR pairing nonce, if this pair came from a link.
		Invite string `json:"invite"`
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
		Mode: req.Mode, Name: self.Name, DeviceID: self.DeviceID,
		Nonce: nonce, Requested: req.Permissions, Invite: req.Invite,
	})
	if err != nil {
		s.d.Trust.Close(sess.ID)
		msg, _ := pairingStartMessage(err)
		http.Error(w, msg, http.StatusBadGateway)
		return
	}
	s.d.Trust.SetRemote(sess.ID, resp.SessionID, resp.Nonce)
	if req.Invite != "" {
		s.d.Trust.MarkViaQR(sess.ID) // QR pairing: skip the SAS compare on our side
	}
	if req.KeepConnected {
		s.d.Trust.SetKeepConnected(sess.ID, true)
	}
	view, _ := s.d.Trust.View(sess.ID)
	writeJSON(w, view)
}

// pairingStartMessage turns a failure to reach a peer's session endpoint into a
// person-readable line. A 404 means the peer speaks the discovery protocol but
// does not run the pairing service yet (for example the current Android app),
// which is a different situation from a device that cannot be reached at all.
// A 403 is a pairing/permission refusal, not an unreachable device.
func pairingStartMessage(err error) (string, bool) {
	var se *peerapi.StatusError
	if errors.As(err, &se) {
		switch se.Code {
		case http.StatusNotFound:
			return "This device can't accept pairing yet. Pair from it instead: scan this computer's QR code.", true
		case http.StatusForbidden:
			return peerapi.UserMessage(err), true
		}
	}
	return "could not reach device: " + err.Error(), false
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
					// The responder may narrow what we asked for; record the
					// actual grant so we never treat the requested set as if
					// the peer had allowed it.
					s.d.Trust.SetPeerGranted(id, st.Granted)
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
			s.peerRefusedPairing(sess.PeerFP, err)
			var se *peerapi.StatusError
			if errors.As(err, &se) {
				http.Error(w, peerUIMessage(err), se.Code)
				return
			}
			http.Error(w, peerErrorMessage(err), peerStatus(err))
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
	// Disconnect ends the live session only; it never removes a pairing. Say so
	// in the log so a person reading the diagnostics knows why the device is
	// still paired afterwards.
	kind := "temporary connection"
	if sess.Mode == trust.ModePair {
		kind = "pairing handshake"
	}
	notified := false
	if sess.RemoteID != "" && s.d.Client != nil {
		if p, ok := s.peerByID(sess.PeerFP); ok {
			if host, port, ok := peerAddr(p); ok {
				if err := s.d.Client.CloseSession(r.Context(), host, port, sess.PeerFP, sess.RemoteID); err != nil {
					if s.d.Log != nil {
						s.d.Log.Warn("disconnect: could not tell the peer to close", "session", id, "fp", sess.PeerFP, "err", err)
					}
				} else {
					notified = true
				}
			}
		}
	}
	s.d.Trust.Close(id)
	if s.d.Log != nil {
		s.d.Log.Info("disconnect: ended session", "session", id, "mode", sess.Mode, "kind", kind, "peer_notified", notified)
	}
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
