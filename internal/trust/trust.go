// Package trust owns the persistent trust store (paired devices, §3.2) and the
// in-memory Connect/Pair sessions (§4.2, §4.3). It answers the one question the
// peer service needs: what is this certificate allowed to do?
package trust

import (
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"log/slog"
	"sort"
	"strings"
	"sync"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/identity"
	"lanyard/internal/xferlog"
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
	// PairInviteTTL is how long a QR pairing invite nonce stays valid.
	PairInviteTTL = 2 * time.Minute
)

// Permission is one action's permission, per direction. It is tri-state:
//
//	Allow  yes, do it silently
//	Ask    yes, but prompt the person first
//	Never  no (the only state that produces a hard 403)
//
// The zero value ("") is treated as Never at authorization time, but never
// reaches a fresh pairing: new grants default to Ask (see the pairing UI), and
// legacy entries are migrated on load (see Load).
type Permission string

const (
	Allow Permission = "allow"
	Ask   Permission = "ask"
	Never Permission = "never"
)

// Allows reports the "do it silently" state.
func (p Permission) Allows() bool { return p == Allow }

// Asks reports the "prompt the person" state.
func (p Permission) Asks() bool { return p == Ask }

// Denies reports a hard refusal. The unset zero value counts as a refusal.
func (p Permission) Denies() bool { return p != Allow && p != Ask }

// Valid reports whether p is one of the three defined states.
func (p Permission) Valid() bool { return p == Allow || p == Ask || p == Never }

// Permissions are what one side allows the other to do (per direction). Each
// action is tri-state (Allow/Ask/Never); a missing mode is treated as Never.
//
// Wire encoding (backward compatible): the JSON carries the legacy boolean
// fields (true iff Allow) *and* the tri-state mode fields plus a
// `"perms":"tristate"` marker. An old peer reads the booleans and never sees a
// mode it does not understand; a new peer sees the marker and trusts the modes,
// which is how Ask is negotiated without breaking old builds.
type Permissions struct {
	Browse       Permission `json:"browse"`
	Push         Permission `json:"push"`
	Text         Permission `json:"text"`
	PushMaxBytes int64      `json:"push_max_bytes,omitempty"`
	AskOver      int64      `json:"ask_over,omitempty"`

	// triState is set when the JSON being decoded carried the tristate marker.
	// Entries without it are legacy and are migrated on load.
	triState bool
}

// permissionsWire is the on-the-wire/persisted JSON shape. It always emits the
// legacy booleans so an old peer can read a grant, plus the tri-state modes.
type permissionsWire struct {
	Browse       bool       `json:"browse"`
	Push         bool       `json:"push"`
	Text         bool       `json:"text"`
	BrowseMode   Permission `json:"browse_mode,omitempty"`
	PushMode     Permission `json:"push_mode,omitempty"`
	TextMode     Permission `json:"text_mode,omitempty"`
	Perms        string     `json:"perms,omitempty"`
	PushMaxBytes int64      `json:"push_max_bytes,omitempty"`
	AskOver      int64      `json:"ask_over,omitempty"`
}

const triStateMarker = "tristate"

// MarshalJSON emits both the legacy booleans (allow else deny) and the
// tri-state modes, tagged with the marker so a new peer knows the modes are
// authoritative.
func (p Permissions) MarshalJSON() ([]byte, error) {
	return json.Marshal(permissionsWire{
		Browse: p.Browse.Allows(), Push: p.Push.Allows(), Text: p.Text.Allows(),
		BrowseMode: p.Browse, PushMode: p.Push, TextMode: p.Text,
		Perms: triStateMarker, PushMaxBytes: p.PushMaxBytes, AskOver: p.AskOver,
	})
}

// UnmarshalJSON accepts every historical encoding: a bool (old peer) plus the
// optional tri-state modes. When the tristate marker is present a valid mode
// wins; otherwise a bool maps true->Allow and false->Never (an old peer that
// denies cannot ask).
func (p *Permissions) UnmarshalJSON(b []byte) error {
	var w struct {
		Browse       *json.RawMessage `json:"browse"`
		Push         *json.RawMessage `json:"push"`
		Text         *json.RawMessage `json:"text"`
		BrowseMode   string           `json:"browse_mode"`
		PushMode     string           `json:"push_mode"`
		TextMode     string           `json:"text_mode"`
		Perms        string           `json:"perms"`
		PushMaxBytes int64            `json:"push_max_bytes"`
		AskOver      int64            `json:"ask_over"`
	}
	if err := json.Unmarshal(b, &w); err != nil {
		return err
	}
	tri := w.Perms == triStateMarker
	*p = Permissions{
		Browse:       decodePermission(w.Browse, w.BrowseMode, tri),
		Push:         decodePermission(w.Push, w.PushMode, tri),
		Text:         decodePermission(w.Text, w.TextMode, tri),
		PushMaxBytes: w.PushMaxBytes, AskOver: w.AskOver,
		triState: tri,
	}
	return nil
}

// decodePermission resolves one field: a valid mode when the tristate marker
// was present, else the legacy boolean. An absent field yields "" (Never).
func decodePermission(raw *json.RawMessage, mode string, tri bool) Permission {
	if tri {
		if m := Permission(mode); m.Valid() {
			return m
		}
	}
	if raw == nil {
		return ""
	}
	var b bool
	if err := json.Unmarshal(*raw, &b); err == nil {
		if b {
			return Allow
		}
		return Never
	}
	var s string
	if err := json.Unmarshal(*raw, &s); err == nil {
		if m := Permission(s); m.Valid() {
			return m
		}
	}
	return ""
}

// migrateLegacy upgrades a pre-tri-state grant: the old boolean had no Ask
// state, so anything that was not an explicit Allow becomes the new default,
// Ask. Text predates its own permission, so an entry with no text field
// inherits the legacy push grant instead: an allowed push meant allowed text
// (Allow), a denied push becomes the new default Ask. It is never silently
// always-Ask.
func migrateLegacy(p Permissions) Permissions {
	legacyPush := p.Push
	if p.Browse != Allow {
		p.Browse = Ask
	}
	if p.Push != Allow {
		p.Push = Ask
	}
	if p.Text != Allow {
		if legacyPush == Allow {
			p.Text = Allow
		} else {
			p.Text = Ask
		}
	}
	return p
}

// Entry is one paired device. Permissions describe what that peer may do to us.
// Addrs/Port remember the last address it answered at, so the desktop can probe
// it directly when mDNS is silent.
type Entry struct {
	DeviceID    string      `json:"device_id"`
	Name        string      `json:"name"`
	Fingerprint string      `json:"cert_fingerprint"`
	Mode        string      `json:"mode"`
	Permissions Permissions `json:"permissions"`
	// Alias is a purely local display name the person sets for this peer. It is
	// persisted with the entry but is NEVER sent to the peer (nor announced);
	// Name stays the peer's broadcast name. Clearing it reverts to Name, and an
	// unpair (or a fresh re-pair) drops it.
	Alias string `json:"alias,omitempty"`
	// PeerPermissions is what the peer granted this device (the opposite
	// direction from Permissions): it is what we may do on the peer, e.g.
	// browse its shares or push files to it. It is recorded at pairing time
	// from the peer's own narrowed grant and is display/UI only: Access still
	// authorizes the peer's requests to us with Permissions.
	PeerPermissions Permissions `json:"peer_permissions,omitempty"`
	Addrs           []string    `json:"addrs,omitempty"`
	Port            int         `json:"port,omitempty"`
	CreatedAt       time.Time   `json:"created_at"`
}

// Access is the authorization decision for a peer certificate. Each action
// carries its tri-state Permission; callers distinguish Allow (silent) from Ask
// (prompt) and Never (refuse).
type Access struct {
	Paired       bool
	DeviceID     string
	Browse       Permission
	Push         Permission
	Text         Permission
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

	Status    string      `json:"status"`
	Error     string      `json:"error,omitempty"`
	Granted   Permissions `json:"granted"`   // what we allow the peer
	Requested Permissions `json:"requested"` // what we requested of the peer
	// PeerGranted is the peer's answer to our request: the (possibly narrowed)
	// permissions the peer actually granted us. An initiator learns it by
	// polling the responder's session status; it is what we may do on the peer.
	PeerGranted   Permissions `json:"peer_granted,omitempty"`
	KeepConnected bool        `json:"keep_connected"`
	// ViaQR is set when pairing was authenticated by a QR invite nonce rather
	// than the SAS compare; the UI then skips the code check for that session.
	ViaQR  bool     `json:"via_qr,omitempty"`
	Offers []string `json:"offers,omitempty"`

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
	PeerGranted   Permissions `json:"peer_granted"`
	KeepConnected bool        `json:"keep_connected"`
	ViaQR         bool        `json:"via_qr,omitempty"`
	Offers        []string    `json:"offers"`
	CreatedAt     time.Time   `json:"created_at"`
	UpdatedAt     time.Time   `json:"updated_at"`
}

// pendingUnpair is one queued revoke notification: the paired snapshot captured
// when the device was unpaired, plus the moment it was queued. The timestamp
// lets a delivery that predates a newer pairing be refused, so a stale revoke
// cannot unpair a device the person has since re-paired.
type pendingUnpair struct {
	Entry
	QueuedAt time.Time
}

// Store holds paired entries and live sessions.
type Store struct {
	cfg      *config.Store
	selfFP   string
	onChange func()
	// onIncoming, when set, is invoked for each incoming Connect/Pair request
	// so the host can raise a desktop notification. It is called in its own
	// goroutine and must not block.
	onIncoming func(*Session)

	mu       sync.RWMutex
	paired   map[string]*Entry // key: peer fingerprint
	sessions map[string]*Session
	// pendingUnpair holds devices we unpaired locally but could not yet tell to
	// drop us (the peer was offline). The entry keeps the last-known address so
	// the notification can be retried once discovery sees the peer again.
	pendingUnpair map[string]pendingUnpair
	// unpairDelivered counts successful unpair notifications per fingerprint.
	// A racing retry captures the count before it attempts delivery and refuses
	// to re-arm a pending record once a sibling has bumped it; that stops a
	// duplicate attempt from resurrecting a revoke that already succeeded.
	unpairDelivered map[string]uint64
	// invites maps one-time QR pairing nonces to their expiry.
	invites   map[string]time.Time
	inviteTTL time.Duration

	// xlog records pairing/session lifecycle events in the shared four-area
	// diagnostics log. Nil is a valid no-op.
	xlog *xferlog.Recorder
}

// SetXferLog attaches the shared diagnostics recorder.
func (s *Store) SetXferLog(r *xferlog.Recorder) { s.xlog = r }

// pairLog records one pairing-area event. The recorder carries the desktop
// logger, so no logger is passed here. age is optional (0 unknown).
func (s *Store) pairLog(level xferlog.Level, outcome, peerFP, sessionID, reason string, age time.Duration, err error) {
	if s.xlog == nil {
		return
	}
	e := xferlog.Entry{
		Area: xferlog.AreaPairing, Level: level, Outcome: outcome,
		FP: identity.ShortID(peerFP), Session: sessionID, Reason: reason, Age: age,
	}
	if err != nil {
		e.Error = err.Error()
	}
	s.xlog.Record(nil, e)
}

func New(cfg *config.Store, selfFP string, onChange func()) *Store {
	if onChange == nil {
		onChange = func() {}
	}
	return &Store{cfg: cfg, selfFP: selfFP, onChange: onChange,
		paired: map[string]*Entry{}, sessions: map[string]*Session{},
		pendingUnpair:   map[string]pendingUnpair{},
		unpairDelivered: map[string]uint64{},
		invites:         map[string]time.Time{}, inviteTTL: PairInviteTTL}
}

// SetPairInviteTTL changes how long a pairing invite lives (tests).
func (s *Store) SetPairInviteTTL(d time.Duration) {
	s.mu.Lock()
	s.inviteTTL = d
	s.mu.Unlock()
}

// MintPairInvite creates a one-time pairing nonce (128 bits, hex) that expires
// after the invite TTL, and returns it with its expiry.
func (s *Store) MintPairInvite() (string, time.Time) {
	b := make([]byte, 16)
	_, _ = rand.Read(b)
	token := hex.EncodeToString(b)
	now := time.Now()
	s.mu.Lock()
	exp := now.Add(s.inviteTTL)
	for t, e := range s.invites {
		if now.After(e) {
			delete(s.invites, t)
		}
	}
	s.invites[token] = exp
	s.mu.Unlock()
	return token, exp
}

// ConsumePairInvite reports whether token is a valid, unexpired invite. A token
// that matches a stored invite is removed on this first attempt whether or not
// it is still valid, so a reused or expired invite can never be retried or
// downgraded. Comparison is constant time.
func (s *Store) ConsumePairInvite(token string) bool {
	if token == "" {
		return false
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	var found string
	var exp time.Time
	for t, e := range s.invites {
		if subtle.ConstantTimeCompare([]byte(t), []byte(token)) == 1 {
			found, exp = t, e
		}
	}
	if found == "" {
		return false
	}
	delete(s.invites, found)
	return now.Before(exp)
}

// PairInviteValid reports the expiry of an existing invite that is still valid,
// without consuming it. It is used to keep showing the same QR code until it
// expires or is used, instead of minting a new one on every page refresh.
func (s *Store) PairInviteValid(token string) (time.Time, bool) {
	if token == "" {
		return time.Time{}, false
	}
	now := time.Now()
	s.mu.Lock()
	defer s.mu.Unlock()
	for t, e := range s.invites {
		if subtle.ConstantTimeCompare([]byte(t), []byte(token)) == 1 {
			if now.Before(e) {
				return e, true
			}
			delete(s.invites, t)
			return time.Time{}, false
		}
	}
	return time.Time{}, false
}

// MarkViaQR records that a session was authenticated by a QR invite nonce.
func (s *Store) MarkViaQR(id string) bool {
	s.mu.Lock()
	sess := s.sessions[id]
	if sess != nil {
		sess.ViaQR = true
		sess.UpdatedAt = time.Now()
	}
	s.mu.Unlock()
	if sess != nil {
		s.onChange()
	}
	return sess != nil
}

// SetOnIncoming registers a callback invoked for each incoming Connect/Pair
// request. The callback runs in its own goroutine and receives a copy.
func (s *Store) SetOnIncoming(fn func(*Session)) {
	s.mu.Lock()
	s.onIncoming = fn
	s.mu.Unlock()
}

// Load restores paired entries from config. Entries saved before tri-state
// permissions are migrated: the legacy boolean had no Ask state, so every
// non-Allow permission becomes the new default Ask.
func (s *Store) Load() error {
	raw := s.cfg.Get().Trust
	if len(raw) == 0 {
		return nil
	}
	var entries []*Entry
	if err := json.Unmarshal(raw, &entries); err != nil {
		return fmt.Errorf("trust: %w", err)
	}
	migrated := false
	s.mu.Lock()
	for _, e := range entries {
		if !e.Permissions.triState {
			e.Permissions = migrateLegacy(e.Permissions)
			e.Permissions.triState = true
			migrated = true
		}
		if !e.PeerPermissions.triState && peerGrantSet(e.PeerPermissions) {
			e.PeerPermissions = migrateLegacy(e.PeerPermissions)
			e.PeerPermissions.triState = true
			migrated = true
		}
		s.paired[e.Fingerprint] = e
	}
	s.mu.Unlock()
	if migrated {
		// Write the migrated store straight back so the upgrade happens once.
		s.persist()
	}
	return nil
}

// peerGrantSet reports whether a peer grant was recorded at all. An older
// pairing did not record one, leaving the zero value; migrating that to Ask
// would invent a grant the peer never made.
func peerGrantSet(p Permissions) bool {
	return p.Browse != "" || p.Push != "" || p.Text != "" || p.PushMaxBytes != 0 || p.AskOver != 0
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
	if err := s.cfg.Update(func(st *config.Settings) { st.Trust = raw }); err != nil {
		// The trust store could not be saved; never discard the failure.
		slog.Warn("trust: could not persist paired devices", "err", err)
	}
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

// DisplayName is the local alias when one is set, otherwise the peer's
// broadcast name. Callers use it for every person-facing surface (lists,
// transfers, notifications, logs); the broadcast name itself is never changed
// by setting an alias.
func (e Entry) DisplayName() string {
	if a := strings.TrimSpace(e.Alias); a != "" {
		return a
	}
	return e.Name
}

// SetAlias sets or clears the local alias for a paired device. It is local
// only: the alias is never sent to the peer. An empty alias clears it, so the
// display reverts to the broadcast name. It reports whether the device was
// paired. Clearing and setting are no-ops when the value is unchanged.
func (s *Store) SetAlias(fp, alias string) bool {
	alias = strings.TrimSpace(alias)
	s.mu.Lock()
	e, ok := s.paired[fp]
	if !ok {
		s.mu.Unlock()
		return false
	}
	changed := e.Alias != alias
	e.Alias = alias
	s.mu.Unlock()
	if changed {
		s.persist()
		s.onChange()
	}
	return true
}

// SetPeerAddr remembers the last address a paired device answered at, so the
// desktop can probe it directly when mDNS is silent. It reports whether the
// stored address changed.
func (s *Store) SetPeerAddr(fp string, addrs []string, port int) bool {
	if fp == "" || port <= 0 || len(addrs) == 0 {
		return false
	}
	s.mu.Lock()
	e, ok := s.paired[fp]
	changed := false
	if ok && (e.Port != port || !sameAddrs(e.Addrs, addrs)) {
		e.Addrs = append([]string(nil), addrs...)
		e.Port = port
		changed = true
	}
	s.mu.Unlock()
	if !changed {
		return false
	}
	s.persist()
	s.onChange()
	return true
}

func sameAddrs(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// Pair upserts a paired entry. A successful pairing with a fingerprint also
// clears any pending-unpair revoke for it: the device has just been paired
// again, so a queued "drop us" from the previous unpair is stale and must never
// be delivered (it would unpair the fresh pairing).
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
	cleared := false
	if e.Fingerprint != "" {
		_, cleared = s.pendingUnpair[e.Fingerprint]
		delete(s.pendingUnpair, e.Fingerprint)
	}
	s.mu.Unlock()
	if cleared {
		s.pairLog(xferlog.LevelInfo, "pair", e.Fingerprint, "", "pairing supersedes a pending unpair", 0, nil)
	}
	s.persist()
	s.onChange()
}

// Unpair removes a paired entry. Future requests from that fingerprint fail.
func (s *Store) Unpair(fp string) bool {
	s.mu.Lock()
	_, ok := s.paired[fp]
	delete(s.paired, fp)
	// Drop any live session with the same peer too.
	dropped := 0
	for id, sess := range s.sessions {
		if sess.PeerFP == fp {
			delete(s.sessions, id)
			dropped++
		}
	}
	s.mu.Unlock()
	reason := "unpaired"
	if !ok {
		reason = "not paired"
	} else if dropped > 0 {
		reason = fmt.Sprintf("unpaired; %d live session(s) closed", dropped)
	}
	s.pairLog(xferlog.LevelInfo, "unpair", fp, "", reason, 0, nil)
	if ok {
		s.persist()
		s.onChange()
	}
	return ok
}

// AddPendingUnpair records a device we unpaired locally but could not yet tell
// to drop us, so the notification is retried when the device is next seen. The
// entry's address is the last-known one and may be empty; discovery can still
// supply a live address on the retry. Each record is stamped with the moment it
// was queued (preserved if one is already queued), so a later pairing can be
// recognised as newer than the revoke.
func (s *Store) AddPendingUnpair(e Entry) {
	if e.Fingerprint == "" {
		return
	}
	s.mu.Lock()
	queuedAt := time.Now()
	if prev, ok := s.pendingUnpair[e.Fingerprint]; ok {
		queuedAt = prev.QueuedAt
	}
	s.pendingUnpair[e.Fingerprint] = pendingUnpair{Entry: e, QueuedAt: queuedAt}
	s.mu.Unlock()
}

// ClearPendingUnpair forgets a pending unpair notification. It reports whether
// one was present.
func (s *Store) ClearPendingUnpair(fp string) bool {
	s.mu.Lock()
	_, ok := s.pendingUnpair[fp]
	delete(s.pendingUnpair, fp)
	s.mu.Unlock()
	return ok
}

// MarkUnpairDelivered records that an unpair notification for fp has been
// delivered (or found already-unpaired) and returns the new generation. It must
// be called on every successful notification, with or without a pending record:
// it is what lets a racing duplicate retry detect that its sibling already
// finished and refuse to re-arm the pending record.
func (s *Store) MarkUnpairDelivered(fp string) uint64 {
	if fp == "" {
		return 0
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	s.unpairDelivered[fp]++
	return s.unpairDelivered[fp]
}

// UnpairGeneration reports how many unpair notifications for fp have been
// delivered (or found already-unpaired) so far. A retry captures this before it
// attempts delivery and passes it back to AddPendingUnpairIfGeneration, so a
// sibling's success forbids it re-arming the pending record.
func (s *Store) UnpairGeneration(fp string) uint64 {
	if fp == "" {
		return 0
	}
	s.mu.RLock()
	defer s.mu.RUnlock()
	return s.unpairDelivered[fp]
}

// AddPendingUnpairIfGeneration records a pending unpair only when no sibling has
// completed a delivery since the caller captured seen. It reports whether the
// record was stored. This is what stops a duplicate/racing retry from
// resurrecting a revoke that already succeeded.
func (s *Store) AddPendingUnpairIfGeneration(fp string, seen uint64, e Entry) bool {
	if fp == "" {
		return false
	}
	e.Fingerprint = fp
	s.mu.Lock()
	if s.unpairDelivered[fp] != seen {
		s.mu.Unlock()
		return false
	}
	queuedAt := time.Now()
	if prev, ok := s.pendingUnpair[fp]; ok {
		queuedAt = prev.QueuedAt
	}
	s.pendingUnpair[fp] = pendingUnpair{Entry: e, QueuedAt: queuedAt}
	s.mu.Unlock()
	return true
}

// PendingUnpair returns the pending-unpair record for a fingerprint.
func (s *Store) PendingUnpair(fp string) (Entry, bool) {
	s.mu.RLock()
	defer s.mu.RUnlock()
	e, ok := s.pendingUnpair[fp]
	return e.Entry, ok
}

// PendingUnpairSuperseded reports whether fp is paired again with a pairing
// newer than its queued revoke. Delivering that revoke would unpair the device
// the person just re-paired, so it must be refused and discarded.
func (s *Store) PendingUnpairSuperseded(fp string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	p, ok := s.pendingUnpair[fp]
	if !ok {
		return false
	}
	e, paired := s.paired[fp]
	return paired && e.CreatedAt.After(p.QueuedAt)
}

// HasPendingUnpair reports whether fp still needs its unpair delivered.
func (s *Store) HasPendingUnpair(fp string) bool {
	s.mu.RLock()
	defer s.mu.RUnlock()
	_, ok := s.pendingUnpair[fp]
	return ok
}

// PendingUnpairs lists the devices still waiting to be told to drop us.
func (s *Store) PendingUnpairs() []Entry {
	s.mu.RLock()
	defer s.mu.RUnlock()
	out := make([]Entry, 0, len(s.pendingUnpair))
	for _, e := range s.pendingUnpair {
		out = append(out, e.Entry)
	}
	return out
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
		return Access{Paired: true, DeviceID: e.DeviceID, Browse: e.Permissions.Browse, Push: e.Permissions.Push, Text: e.Permissions.Text, MaxPushBytes: e.Permissions.PushMaxBytes, AskOver: e.Permissions.AskOver}
	}
	if sess := s.activeLocked(fp); sess != nil {
		sess.UpdatedAt = time.Now()
		offered := map[string]bool{}
		for _, id := range sess.Offers {
			offered[id] = true
		}
		return Access{Browse: Allow, Push: Allow, Text: Allow, SessionID: sess.ID, Offered: offered}
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
		err := errors.New("too many sessions")
		s.pairLog(xferlog.LevelWarn, "refuse", peerFP, "", "session limit reached", 0, err)
		return nil, err
	}
	// A fresh pair request replaces a leftover pending pairing from the same
	// device (for example a re-pair after a stale or abandoned handshake).
	// Refusing would trap the person: the old prompt may be long gone from their
	// screen while the session lingers. Connect requests keep the flood cap.
	if mode == ModePair {
		for id, sess := range s.sessions {
			if sess.PeerFP == peerFP && sess.Status == StatusPending && sess.Mode == ModePair {
				delete(s.sessions, id)
				s.pairLog(xferlog.LevelInfo, "supersede", peerFP, id, "replaced by a newer pair request", now.Sub(sess.CreatedAt), nil)
			}
		}
	}
	pending := 0
	var pendingID string
	var pendingAge time.Duration
	for id, sess := range s.sessions {
		if sess.PeerFP == peerFP && sess.Status == StatusPending {
			pending++
			pendingID, pendingAge = id, now.Sub(sess.CreatedAt)
		}
	}
	if pending >= MaxPendingPerPeer {
		err := errors.New("a request from this device is already waiting")
		s.pairLog(xferlog.LevelWarn, "refuse", peerFP, pendingID, "request already waiting", pendingAge, err)
		return nil, err
	}
	sess := &Session{
		ID: sessionID(), Mode: mode, Incoming: true,
		PeerFP: peerFP, PeerName: peerName, PeerDevice: peerDevice,
		selfNonce: nonce(), PeerNonce: peerNonce,
		Status: StatusPending, Requested: requested,
		CreatedAt: now, UpdatedAt: now,
	}
	s.sessions[sess.ID] = sess
	s.pairLog(xferlog.LevelInfo, "request", peerFP, sess.ID, mode+" requested", 0, nil)
	if fn := s.onIncoming; fn != nil {
		snap := *sess
		go fn(&snap)
	}
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
	s.pairLog(xferlog.LevelInfo, "request", peerFP, sess.ID, mode+" requested", 0, nil)
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
		err := errors.New("no such request")
		s.pairLog(xferlog.LevelWarn, "refuse", "", id, "no such request", 0, err)
		return nil, err
	}
	if sess.Status != StatusPending {
		s.mu.Unlock()
		err := fmt.Errorf("request is %s", sess.Status)
		s.pairLog(xferlog.LevelWarn, "refuse", sess.PeerFP, id, "request not pending", 0, err)
		return nil, err
	}
	sess.Granted = granted
	sess.Status = StatusAccepted
	sess.UpdatedAt = time.Now()
	fp := sess.PeerFP
	snap := *sess
	s.mu.Unlock()
	s.pairLog(xferlog.LevelInfo, "accept", fp, id, "granted "+permString(granted), 0, nil)
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
		err := errors.New("no such session")
		s.pairLog(xferlog.LevelWarn, "confirm", "", id, "no such session", 0, err)
		return nil, err
	}
	if sess.Status == StatusPending {
		s.mu.Unlock()
		err := errors.New("the request was not accepted")
		s.pairLog(xferlog.LevelWarn, "confirm", sess.PeerFP, id, "not accepted yet", 0, err)
		return nil, err
	}
	// A replayed confirm (the initiator retried, or a duplicate request) is a
	// no-op success: the session is already active and the pairing entry was
	// recorded once, so do not pair or log a second time.
	if sess.Status == StatusActive {
		snap := *sess
		s.mu.Unlock()
		return &snap, nil
	}
	sess.Status = StatusActive
	sess.UpdatedAt = time.Now()
	fp, mode := sess.PeerFP, sess.Mode
	snap := *sess
	s.mu.Unlock()
	if mode == ModePair {
		s.Pair(Entry{
			DeviceID: sess.PeerDevice, Name: sess.PeerName, Fingerprint: fp,
			Mode: ModePair, Permissions: sess.Granted, PeerPermissions: sess.Requested,
		})
	}
	s.pairLog(xferlog.LevelInfo, "confirm", fp, id, "confirmed "+mode, 0, nil)
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
		err := errors.New("no such session")
		s.pairLog(xferlog.LevelWarn, "confirm", "", id, "no such session", 0, err)
		return nil, err
	}
	if sess.Status != StatusAccepted && sess.Status != StatusActive {
		s.mu.Unlock()
		err := fmt.Errorf("session is %s", sess.Status)
		s.pairLog(xferlog.LevelWarn, "confirm", sess.PeerFP, id, "session not accepted", 0, err)
		return nil, err
	}
	// A replayed confirm is a no-op success: the session is already active and
	// the pairing entry was recorded once, so do not pair or log a second time.
	if sess.Status == StatusActive {
		snap := *sess
		s.mu.Unlock()
		return &snap, nil
	}
	sess.Status = StatusActive
	sess.UpdatedAt = time.Now()
	fp, mode := sess.PeerFP, sess.Mode
	snap := *sess
	s.mu.Unlock()
	if mode == ModePair {
		s.Pair(Entry{
			DeviceID: sess.PeerDevice, Name: sess.PeerName, Fingerprint: fp,
			Mode: ModePair, Permissions: sess.Requested, PeerPermissions: sess.PeerGranted,
		})
	}
	s.pairLog(xferlog.LevelInfo, "confirm", fp, id, "confirmed "+mode, 0, nil)
	s.onChange()
	return &snap, nil
}

// SetPeerGranted records the permissions the peer actually granted us, learned
// by polling the responder's session status. It is the narrowed answer to our
// request and is what we may do on the peer.
func (s *Store) SetPeerGranted(id string, granted Permissions) bool {
	s.mu.Lock()
	sess := s.sessions[id]
	if sess != nil {
		sess.PeerGranted = granted
		sess.UpdatedAt = time.Now()
	}
	s.mu.Unlock()
	return sess != nil
}

// SetStatus is used by the responder when the initiator confirms.
func (s *Store) SetStatus(id, status, errMsg string) bool {
	s.mu.Lock()
	sess := s.sessions[id]
	var fp string
	if sess != nil {
		sess.Status = status
		sess.Error = errMsg
		sess.UpdatedAt = time.Now()
		fp = sess.PeerFP
	}
	s.mu.Unlock()
	if sess != nil {
		outcome := "status"
		level := xferlog.LevelInfo
		switch status {
		case StatusRejected:
			outcome, level = "refuse", xferlog.LevelWarn
		case StatusClosed:
			outcome = "close"
		case StatusExpired:
			outcome = "expire"
		}
		var err error
		if errMsg != "" {
			err = errors.New(errMsg)
		}
		s.pairLog(level, outcome, fp, id, "session "+status, 0, err)
		s.onChange()
	}
	return sess != nil
}

// permString renders granted permissions compactly for the log.
func permString(p Permissions) string {
	parts := make([]string, 0, 3)
	add := func(name string, v Permission) {
		switch v {
		case Allow:
			parts = append(parts, name)
		case Ask:
			parts = append(parts, name+"?")
		}
	}
	add("browse", p.Browse)
	add("push", p.Push)
	add("text", p.Text)
	if len(parts) == 0 {
		return "none"
	}
	return strings.Join(parts, "+")
}

// Reject marks an incoming request rejected.
func (s *Store) Reject(id string) bool { return s.SetStatus(id, StatusRejected, "") }

// Close ends a session immediately.
func (s *Store) Close(id string) bool {
	s.mu.Lock()
	sess, ok := s.sessions[id]
	var fp, mode string
	if ok {
		fp, mode = sess.PeerFP, sess.Mode
		delete(s.sessions, id)
	}
	s.mu.Unlock()
	if ok {
		s.pairLog(xferlog.LevelInfo, "close", fp, id, "closed "+mode, 0, nil)
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
	fp, id := sess.PeerFP, sess.ID
	delete(s.sessions, sess.ID)
	s.mu.Unlock()
	s.pairLog(xferlog.LevelInfo, "close", fp, id, "closed after transfer", 0, nil)
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
	fp := sess.PeerFP
	delete(s.sessions, id)
	s.mu.Unlock()
	s.pairLog(xferlog.LevelInfo, "close", fp, id, "closed after transfer", 0, nil)
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
		PeerGranted:   sess.PeerGranted,
		KeepConnected: sess.KeepConnected, ViaQR: sess.ViaQR, Offers: append([]string(nil), sess.Offers...),
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
	expire := func(id string, sess *Session, reason string) {
		age := now.Sub(sess.UpdatedAt)
		s.pairLog(xferlog.LevelInfo, "expire", sess.PeerFP, id, reason, age, nil)
		delete(s.sessions, id)
		changed = true
	}
	for id, sess := range s.sessions {
		switch sess.Status {
		case StatusPending:
			if now.Sub(sess.CreatedAt) > PairingTTL {
				expire(id, sess, "pairing request expired")
			}
		case StatusAccepted:
			// A handshake accepted but never confirmed must not linger.
			if now.Sub(sess.UpdatedAt) > PairingTTL {
				expire(id, sess, "accepted request not confirmed")
			}
		case StatusActive:
			switch sess.Mode {
			case ModeConnect:
				if now.Sub(sess.UpdatedAt) > SessionInactivity {
					expire(id, sess, "connect session idle")
				}
			case ModePair:
				// The trust entry outlives the handshake; drop the session itself.
				if now.Sub(sess.UpdatedAt) > PairingTTL {
					expire(id, sess, "pairing session expired")
				}
			}
		case StatusRejected, StatusClosed, StatusExpired:
			if now.Sub(sess.UpdatedAt) > 5*time.Minute {
				expire(id, sess, "closed session reaped")
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
