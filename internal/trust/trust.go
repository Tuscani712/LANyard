// Package trust owns the persistent trust store (paired devices, §3.2) and the
// in-memory Connect/Pair sessions (§4.2, §4.3). It answers the one question the
// peer service needs: what is this certificate allowed to do?
package trust

import (
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"sort"
	"sync"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/identity"
)

// Modes and session states.
const (
	ModeConnect = "connect"
	ModePair    = "pair"

	StatusPending  = "pending"
	StatusAccepted = "accepted"
	StatusActive   = "active"
	StatusRejected = "rejected"
	StatusClosed   = "closed"
	StatusExpired  = "expired"
)

// Timings (spec §4.2, §4.3).
const (
	// PairingTTL is how long an unanswered Connect/Pair prompt lives.
	PairingTTL = 2 * time.Minute
	// SessionInactivity closes an idle Connect session.
	SessionInactivity = 15 * time.Minute
	// MaxPendingPerPeer caps prompts so one source cannot flood the UI.
	MaxPendingPerPeer = 1
	// MaxSessions is a global cap on live/pending sessions.
	MaxSessions = 64
)

// Permissions are what one side allows the other to do (per direction).
type Permissions struct {
	Browse       bool  `json:"browse"`
	Push         bool  `json:"push"`
	PushMaxBytes int64 `json:"push_max_bytes,omitempty"`
	AskOver      int64 `json:"ask_over,omitempty"`
}

// Entry is one paired device. Permissions describe what that peer may do to us.
type Entry struct {
	DeviceID    string      `json:"device_id"`
	Name        string      `json:"name"`
	Fingerprint string      `json:"cert_fingerprint"`
	Mode        string      `json:"mode"`
	Permissions Permissions `json:"permissions"`
	CreatedAt   time.Time   `json:"created_at"`
}

// Access is the authorization decision for a peer certificate.
type Access struct {
	Paired       bool
	DeviceID     string
	Browse       bool
	Push         bool
	MaxPushBytes int64
	AskOver      int64 // paired peers: ask a person before accepting pushes larger than this (0 = never)
	SessionID    string
	Offered      map[string]bool // non-nil for a Connect session: only these share IDs
}

type Session struct {
	ID         string `json:"id"`
	RemoteID   string `json:"remote_id,omitempty"` // the other side's session ID
	Mode       string `json:"mode"`
	Incoming   bool   `json:"incoming"`
	PeerFP     string `json:"peer_fp"`
	PeerName   string `json:"peer_name"`
	PeerDevice string `json:"peer_device"`

	selfNonce string
	PeerNonce string `json:"peer_nonce,omitempty"`

	Status        string      `json:"status"`
	Error         string      `json:"error,omitempty"`
	Granted       Permissions `json:"granted"`   // what we allow the peer
	Requested     Permissions `json:"requested"` // what the peer allows us
	KeepConnected bool        `json:"keep_connected"`
	Offers        []string    `json:"offers,omitempty"`

	CreatedAt time.Time `json:"created_at"`
	UpdatedAt time.Time `json:"updated_at"`
}

// View is the JSON shape handed to the local UI.
type View struct {
	ID            string      `json:"id"`
	RemoteID      string      `json:"remote_id,omitempty"`
	Mode          string      `json:"mode"`
	Incoming      bool        `json:"incoming"`
	PeerFP        string      `json:"peer_fp"`
	PeerName      string      `json:"peer_name"`
	PeerDevice    string      `json:"peer_device"`
	Status        string      `json:"status"`
	Error         string      `json:"error,omitempty"`
	SAS           string      `json:"sas,omitempty"`
	Granted       Permissions `json:"granted"`
	Requested     Permissions `json:"requested"`
	KeepConnected bool        `json:"keep_connected"`
	Offers        []string    `json:"offers"`
	CreatedAt     time.Time   `json:"created_at"`
	UpdatedAt     time.Time   `json:"updated_at"`
}

// Store holds paired entries and live sessions.
type Store struct {
	cfg      *config.Store
	selfFP   string
	onChange func()

	mu       sync.RWMutex
	paired   map[string]*Entry // key: peer fingerprint
	sessions map[string]*Session
}

func New(cfg *config.Store, selfFP string, onChange func()) *Store {
	if onChange == nil {
		onChange = func() {}
	}
	return &Store{cfg: cfg, selfFP: selfFP, onChange: onChange,
		paired: map[string]*Entry{}, sessions: map[string]*Session{}}
}

// Load restores paired entries from config.
func (s *Store) Load() error {
	raw := s.cfg.Get().Trust
	if len(raw) == 0 {
		return nil
	}
	var entries []*Entry
	if err := json.Unmarshal(raw, &entries); err != nil {
		return fmt.Errorf("trust: %w", err)
	}
	s.mu.Lock()
	for _, e := range entries {
		s.paired[e.Fingerprint] = e
	}
	s.mu.Unlock()
	return nil
}

func (s *Store) persist() {
	s.mu.RLock()
	entries := make([]*Entry, 0, len(s.paired))
	for _, e := range s.paired {
		entries = append(entries, e)
	}
	s.mu.RUnlock()
	raw, err := json.Marshal(entries)
	if err != nil {
		return
	}
	_ = s.cfg.Update(func(st *config.Settings) { st.Trust = raw })
}

// Paired lists paired devices, oldest first.
func (s *Store) Paired() []Entry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Entry, 0, len(s.paired))
	for _, e := range s.paired {
		out = append(out, *e)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out
}

// Entry returns a paired entry by fingerprint.
func (s *Store) Entry(fp string) (Entry, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	e, ok := s.paired[fp]
	if !ok {
		return Entry{}, false
	}
	return *e, true
}

// Pair upserts a paired entry.
func (s *Store) Pair(e Entry) {
	if e.Mode == "" {
		e.Mode = ModePair
	}
	if e.CreatedAt.IsZero() {
		e.CreatedAt = time.Now()
	}
	s.mu.Lock()
	c := e
	s.paired[e.Fingerprint] = &c
	s.mu.Unlock()
	s.persist()
	s.onChange()
}

// Unpair removes a paired entry. Future requests from that fingerprint fail.
func (s *Store) Unpair(fp string) bool {
	s.mu.Lock()
	_, ok := s.paired[fp]
	delete(s.paired, fp)
	// Drop any live session with the same peer too.
	for id, sess := range s.sessions {
		if sess.PeerFP == fp {
			delete(s.sessions, id)
		}
	}
	s.mu.Unlock()
	if ok {
		s.persist()
		s.onChange()
	}
	return ok
}

// UpdatePermissions changes what a paired peer may do without re-pairing. It
// takes effect on the next request (and for live sessions on their next call).
func (s *Store) UpdatePermissions(fp string, perms Permissions) bool {
	s.mu.Lock()
	e, ok := s.paired[fp]
	if ok {
		e.Permissions = perms
	}
	s.mu.Unlock()
	if ok {
		s.persist()
		s.onChange()
	}
	return ok
}

// Access answers whether and how a peer certificate may act.
func (s *Store) Access(fp string) Access {
	s.mu.Lock()
	defer s.mu.Unlock()
	if e, ok := s.paired[fp]; ok {
		return Access{Paired: true, DeviceID: e.DeviceID, Browse: e.Permissions.Browse, Push: e.Permissions.Push, MaxPushBytes: e.Permissions.PushMaxBytes, AskOver: e.Permissions.AskOver}
	}
	if sess := s.activeLocked(fp); sess != nil {
		sess.UpdatedAt = time.Now()
		offered := map[string]bool{}
		for _, id := range sess.Offers {
			offered[id] = true
		}
		return Access{Browse: true, SessionID: sess.ID, Offered: offered}
	}
	return Access{}
}

// activeLocked returns an accepted/active session for the peer. Caller holds mu.
func (s *Store) activeLocked(fp string) *Session {
	now := time.Now()
	var best *Session
	for _, sess := range s.sessions {
		if sess.PeerFP != fp {
			continue
		}
		if sess.Mode != ModeConnect {
			continue
		}
		if sess.Status != StatusAccepted && sess.Status != StatusActive {
			continue
		}
		if now.Sub(sess.UpdatedAt) > SessionInactivity {
			continue
		}
		if best == nil || sess.UpdatedAt.After(best.UpdatedAt) {
			best = sess
		}
	}
	return best
}

// --- sessions ---

func nonce() string {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

func sessionID() string { return "c_" + hex.EncodeToString(randBytes(6)) }

func randBytes(n int) []byte {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return b
}

// CreateIncoming records a Connect/Pair request from a peer.
func (s *Store) CreateIncoming(mode, peerFP, peerName, peerDevice, peerNonce string, requested Permissions) (*Session, error) {
	if peerFP == "" || peerNonce == "" {
		return nil, errors.New("missing peer identity")
	}
	if mode == "" {
		mode = ModeConnect
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(now)
	if len(s.sessions) >= MaxSessions {
		return nil, errors.New("too many sessions")
	}
	pending := 0
	for _, sess := range s.sessions {
		if sess.PeerFP == peerFP && sess.Status == StatusPending {
			pending++
		}
	}
	if pending >= MaxPendingPerPeer {
		return nil, errors.New("a request from this device is already waiting")
	}
	sess := &Session{
		ID: sessionID(), Mode: mode, Incoming: true,
		PeerFP: peerFP, PeerName: peerName, PeerDevice: peerDevice,
		selfNonce: nonce(), PeerNonce: peerNonce,
		Status: StatusPending, Requested: requested,
		CreatedAt: now, UpdatedAt: now,
	}
	s.sessions[sess.ID] = sess
	go s.onChange()
	return sess, nil
}

// CreateOutgoing records a request we are initiating.
func (s *Store) CreateOutgoing(mode, peerFP, peerName, peerDevice string, requested Permissions) *Session {
	now := time.Now()
	sess := &Session{
		ID: sessionID(), Mode: mode, Incoming: false,
		PeerFP: peerFP, PeerName: peerName, PeerDevice: peerDevice,
		selfNonce: nonce(), Status: StatusPending,
		Requested: requested, CreatedAt: now, UpdatedAt: now,
	}
	s.mu.Lock()
	s.sessions[sess.ID] = sess
	s.mu.Unlock()
	s.onChange()
	return sess
}

// SetRemote records the responder's session id and nonce.
func (s *Store) SetRemote(id, remoteID, peerNonce string) bool {
	s.mu.Lock()
	sess := s.sessions[id]
	if sess != nil {
		sess.RemoteID = remoteID
		sess.PeerNonce = peerNonce
		sess.UpdatedAt = time.Now()
	}
	s.mu.Unlock()
	if sess != nil {
		s.onChange()
	}
	return sess != nil
}

// Get returns a session by local id, applying expiry.
func (s *Store) Get(id string) (*Session, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(time.Now())
	sess, ok := s.sessions[id]
	return sess, ok
}

// GetByPeer returns the most recent session with a peer fingerprint.
func (s *Store) GetByPeer(fp string) (*Session, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	var best *Session
	for _, sess := range s.sessions {
		if sess.PeerFP == fp && (best == nil || sess.UpdatedAt.After(best.UpdatedAt)) {
			best = sess
		}
	}
	return best, best != nil
}

// Accept is called by the responder. It records the permissions we grant the
// peer; for a pairing it also stores the trust entry.
func (s *Store) Accept(id string, granted Permissions) (*Session, error) {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	if !ok || !sess.Incoming {
		s.mu.Unlock()
		return nil, errors.New("no such request")
	}
	if sess.Status != StatusPending {
		s.mu.Unlock()
		return nil, fmt.Errorf("request is %s", sess.Status)
	}
	sess.Granted = granted
	sess.Status = StatusAccepted
	sess.UpdatedAt = time.Now()
	snap := *sess
	s.mu.Unlock()
	// The trust entry is created only when the initiator confirms the SAS
	// (see ActivateRemote), so a cancelled or unconfirmed pairing leaves no
	// entry on either side.
	s.onChange()
	return &snap, nil
}

// ActivateRemote is called on the responder when the initiator confirms the
// SAS. It activates the session and, for a pairing, records the trust entry
// with the permissions we granted. Deferring the entry to this point keeps a
// cancelled/unconfirmed pairing from leaving a phantom paired device.
func (s *Store) ActivateRemote(id string) (*Session, error) {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	if !ok || !sess.Incoming {
		s.mu.Unlock()
		return nil, errors.New("no such session")
	}
	if sess.Status == StatusPending {
		s.mu.Unlock()
		return nil, errors.New("the request was not accepted")
	}
	sess.Status = StatusActive
	sess.UpdatedAt = time.Now()
	snap := *sess
	s.mu.Unlock()
	if sess.Mode == ModePair {
		s.Pair(Entry{
			DeviceID: sess.PeerDevice, Name: sess.PeerName, Fingerprint: sess.PeerFP,
			Mode: ModePair, Permissions: sess.Granted,
		})
	}
	s.onChange()
	return &snap, nil
}

// Confirm is called by the initiator after the users match the SAS. For a
// pairing it stores the trust entry with the permissions we granted the peer.
func (s *Store) Confirm(id string) (*Session, error) {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	if !ok || sess.Incoming {
		s.mu.Unlock()
		return nil, errors.New("no such session")
	}
	if sess.Status != StatusAccepted && sess.Status != StatusActive {
		s.mu.Unlock()
		return nil, fmt.Errorf("session is %s", sess.Status)
	}
	sess.Status = StatusActive
	sess.UpdatedAt = time.Now()
	snap := *sess
	s.mu.Unlock()
	if sess.Mode == ModePair {
		s.Pair(Entry{
			DeviceID: sess.PeerDevice, Name: sess.PeerName, Fingerprint: sess.PeerFP,
			Mode: ModePair, Permissions: sess.Requested,
		})
	}
	s.onChange()
	return &snap, nil
}

// SetStatus is used by the responder when the initiator confirms.
func (s *Store) SetStatus(id, status, errMsg string) bool {
	s.mu.Lock()
	sess := s.sessions[id]
	if sess != nil {
		sess.Status = status
		sess.Error = errMsg
		sess.UpdatedAt = time.Now()
	}
	s.mu.Unlock()
	if sess != nil {
		s.onChange()
	}
	return sess != nil
}

// Reject marks an incoming request rejected.
func (s *Store) Reject(id string) bool { return s.SetStatus(id, StatusRejected, "") }

// Close ends a session immediately.
func (s *Store) Close(id string) bool {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	if ok {
		delete(s.sessions, id)
	}
	s.mu.Unlock()
	if ok {
		_ = sess
		s.onChange()
	}
	return ok
}

// EndAfterTransfer closes the live Connect session with a peer after a
// transfer finished (spec §4.2: a Connect session is for a single transfer),
// unless the person chose "Keep connected". It returns the peer's own session
// ID so the other side can be told to close too.
func (s *Store) EndAfterTransfer(fp string) (remoteID string, ended bool) {
	s.mu.Lock()
	sess := s.activeLocked(fp)
	if sess == nil || sess.KeepConnected {
		s.mu.Unlock()
		return "", false
	}
	remoteID = sess.RemoteID
	delete(s.sessions, sess.ID)
	s.mu.Unlock()
	s.onChange()
	return remoteID, true
}

// CloseAfterTransfer is the receiving side of the same rule: the peer says its
// transfer is done; close our end unless we chose "Keep connected".
func (s *Store) CloseAfterTransfer(id string) bool {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	if !ok || sess.KeepConnected {
		s.mu.Unlock()
		return false
	}
	delete(s.sessions, id)
	s.mu.Unlock()
	s.onChange()
	return true
}

// Offer sets the shares offered into a Connect session (responder side).
func (s *Store) Offer(id string, shareIDs []string) error {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	if !ok || !sess.Incoming {
		s.mu.Unlock()
		return errors.New("no such session")
	}
	sess.Offers = append([]string(nil), shareIDs...)
	sess.UpdatedAt = time.Now()
	s.mu.Unlock()
	s.onChange()
	return nil
}

// SetKeepConnected records the keep-alive preference.
func (s *Store) SetKeepConnected(id string, keep bool) bool {
	s.mu.Lock()
	sess := s.sessions[id]
	if sess != nil {
		sess.KeepConnected = keep
		sess.UpdatedAt = time.Now()
	}
	s.mu.Unlock()
	if sess != nil {
		s.onChange()
	}
	return sess != nil
}

// Sessions lists every session, newest first.
func (s *Store) Sessions() []View {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.sweepLocked(time.Now())
	out := make([]View, 0, len(s.sessions))
	for _, sess := range s.sessions {
		out = append(out, s.viewLocked(sess))
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.After(out[j].CreatedAt) })
	return out
}

// View returns one session as the UI sees it.
func (s *Store) View(id string) (View, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.sessions[id]
	if !ok {
		return View{}, false
	}
	return s.viewLocked(sess), true
}

func (s *Store) viewLocked(sess *Session) View {
	v := View{
		ID: sess.ID, RemoteID: sess.RemoteID, Mode: sess.Mode, Incoming: sess.Incoming,
		PeerFP: sess.PeerFP, PeerName: sess.PeerName, PeerDevice: sess.PeerDevice,
		Status: sess.Status, Error: sess.Error, Granted: sess.Granted, Requested: sess.Requested,
		KeepConnected: sess.KeepConnected, Offers: append([]string(nil), sess.Offers...),
		CreatedAt: sess.CreatedAt, UpdatedAt: sess.UpdatedAt,
	}
	if sess.PeerNonce != "" && sess.selfNonce != "" {
		v.SAS = identity.SAS(s.selfFP, sess.PeerFP, sess.selfNonce, sess.PeerNonce)
	}
	return v
}

// Snapshot returns a copy of a session (including its private nonces) under
// lock, so handlers never read fields concurrently with the janitor.
func (s *Store) Snapshot(id string) (Session, bool) {
	s.mu.Lock()
	defer s.mu.Unlock()
	sess, ok := s.sessions[id]
	if !ok {
		return Session{}, false
	}
	return *sess, true
}

// SelfNonce returns our nonce for a session so a handler can hand it to the peer.
func (s *Store) SelfNonce(id string) (string, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	sess, ok := s.sessions[id]
	if !ok {
		return "", false
	}
	return sess.selfNonce, true
}

// peerNonceFor is used by the responder to learn the initiator's nonce.
func (s *Store) peerNonceFor(id string) (string, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	sess, ok := s.sessions[id]
	if !ok {
		return "", false
	}
	return sess.PeerNonce, true
}

// Sweep expires stale sessions.
func (s *Store) Sweep() bool {
	s.mu.Lock()
	changed := s.sweepLocked(time.Now())
	s.mu.Unlock()
	if changed {
		s.onChange()
	}
	return changed
}

func (s *Store) sweepLocked(now time.Time) bool {
	changed := false
	for id, sess := range s.sessions {
		switch sess.Status {
		case StatusPending:
			if now.Sub(sess.CreatedAt) > PairingTTL {
				delete(s.sessions, id)
				changed = true
			}
		case StatusAccepted:
			// A handshake accepted but never confirmed must not linger.
			if now.Sub(sess.UpdatedAt) > PairingTTL {
				delete(s.sessions, id)
				changed = true
			}
		case StatusActive:
			switch sess.Mode {
			case ModeConnect:
				if now.Sub(sess.UpdatedAt) > SessionInactivity {
					delete(s.sessions, id)
					changed = true
				}
			case ModePair:
				// The trust entry outlives the handshake; drop the session itself.
				if now.Sub(sess.UpdatedAt) > PairingTTL {
					delete(s.sessions, id)
					changed = true
				}
			}
		case StatusRejected, StatusClosed, StatusExpired:
			if now.Sub(sess.UpdatedAt) > 5*time.Minute {
				delete(s.sessions, id)
				changed = true
			}
		}
	}
	return changed
}

// Start runs a janitor until stop is closed.
func (s *Store) Start(stop <-chan struct{}) {
	go func() {
		t := time.NewTicker(30 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				s.Sweep()
			}
		}
	}()
}
