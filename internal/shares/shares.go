// Package shares owns shared files and folders: the model, their lifetimes,
// the expiry scheduler, and confined (os.Root) read access.
//
// Remote peers only ever see a share's ID, label and relative paths; absolute
// local paths never leave the machine.
package shares

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"time"

	"lanyard/internal/config"
)

// Lifecycle types (spec §6.2).
const (
	LifetimePersistent   = "persistent"
	LifetimeUntilStopped = "until_stopped"
	LifetimeTimed        = "timed"
	LifetimeOneTime      = "one_time"
)

// OneTimeSafetyExpiry is the fallback expiry applied to one-time shares.
const OneTimeSafetyExpiry = 24 * time.Hour

const (
	// GraceMax is how long, after a share ends, transfers that were already in
	// progress may keep going (spec §6.2).
	GraceMax = 10 * time.Minute
	// ActiveWindow is how recently a peer must have moved data to count as
	// "in progress" (it covers back-off between retries).
	ActiveWindow = 60 * time.Second
	// goneKeep is how long we remember an ended share so peers that used it get
	// "410 expired" instead of a puzzling "404".
	goneKeep = time.Hour
)

// ManifestCap bounds a recursive listing so a hostile or huge tree cannot
// exhaust memory. The spec targets 200k tiny files; this leaves headroom.
const ManifestCap = 1_000_000

type Lifetime struct {
	Type        string     `json:"type"`
	DurationSec int        `json:"duration_sec,omitempty"`
	ExpiresAt   *time.Time `json:"expires_at,omitempty"`
}

type Options struct {
	IncludeHidden  bool `json:"include_hidden"`
	FollowSymlinks bool `json:"follow_symlinks"`
}

// Share is one file or one folder.
type Share struct {
	ShareID        string    `json:"share_id"`
	Path           string    `json:"path"`
	Kind           string    `json:"kind"` // "file" | "folder"
	Label          string    `json:"label"`
	Visibility     string    `json:"visibility"` // "paired" | "specific"
	AllowedDevices []string  `json:"allowed_devices,omitempty"`
	Lifetime       Lifetime  `json:"lifetime"`
	CreatedAt      time.Time `json:"created_at"`
	Options        Options   `json:"options"`

	mu         sync.RWMutex `json:"-"`
	root       *os.Root     `json:"-"`
	rootRel    string       `json:"-"` // "" for folders, base name for files
	consumedAt time.Time    `json:"-"` // one-time share finished by a verified download
	consumedBy string       `json:"-"`

	actMu      sync.Mutex           `json:"-"`
	seen       map[string]time.Time `json:"-"` // peer -> last request of any kind (for "410 expired")
	xfer       map[string]time.Time `json:"-"` // peer -> last file/hash activity (for the grace rule)
	flights    map[int]flight       `json:"-"` // streams being served right now
	nextFlight int                  `json:"-"`
}

type flight struct {
	peer   string
	cancel context.CancelFunc
}

type goneInfo struct {
	reason string
	at     time.Time
	peers  map[string]bool
}

// Summary is the peer-visible view of a share (spec §6.1).
type Summary struct {
	ShareID   string     `json:"share_id"`
	Label     string     `json:"label"`
	Name      string     `json:"name,omitempty"` // file base name for file shares
	Kind      string     `json:"kind"`
	Size      int64      `json:"size,omitempty"`
	Lifetime  string     `json:"lifetime"`
	ExpiresAt *time.Time `json:"expires_at,omitempty"`
}

// Entry is one item in a directory listing (spec §7 tree).
type Entry struct {
	Name    string    `json:"name"`
	Path    string    `json:"path"`
	IsDir   bool      `json:"is_dir"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"mtime"`
	ETag    string    `json:"etag,omitempty"`
}

// ManifestEntry is one file in a recursive listing (spec §7 manifest).
type ManifestEntry struct {
	Path    string    `json:"path"`
	Name    string    `json:"name"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"mtime"`
	ETag    string    `json:"etag"`
}

// Manager holds every share and its scheduler.
type Manager struct {
	cfg      *config.Store
	selfID   string
	now      func() time.Time
	onChange func()

	graceMax     time.Duration
	activeWindow time.Duration

	mu     sync.RWMutex
	shares map[string]*Share
	gone   map[string]*goneInfo
}

// New creates a manager. Call Load to restore persisted shares and Start to
// run the expiry scheduler.
func New(cfg *config.Store, selfID string, onChange func()) *Manager {
	if onChange == nil {
		onChange = func() {}
	}
	return &Manager{
		cfg: cfg, selfID: selfID, now: time.Now, onChange: onChange,
		graceMax: GraceMax, activeWindow: ActiveWindow,
		shares: map[string]*Share{}, gone: map[string]*goneInfo{},
	}
}

// Load restores persistable shares (persistent, one-time, and unexpired timed)
// from config. until_stopped shares are never restored.
func (m *Manager) Load() error {
	raw := m.cfg.Get().Shares
	if len(raw) == 0 {
		return nil
	}
	var persisted []*Share
	if err := json.Unmarshal(raw, &persisted); err != nil {
		return fmt.Errorf("shares: %w", err)
	}
	now := m.now()
	dropped := false
	m.mu.Lock()
	for _, s := range persisted {
		if s.Lifetime.Type == LifetimeUntilStopped {
			dropped = true
			continue
		}
		if s.Lifetime.ExpiresAt != nil && now.After(*s.Lifetime.ExpiresAt) {
			dropped = true // timed (or one-time safety) expiry passed while we were closed
			continue
		}
		if err := s.openRoot(); err != nil {
			// Path vanished or became inaccessible; drop it rather than keep a
			// share that can never be served.
			dropped = true
			continue
		}
		m.shares[s.ShareID] = s
	}
	m.mu.Unlock()
	if dropped {
		m.persist() // forget the expired entries on disk too
	}
	return nil
}

// SetClock replaces the time source. It exists for tests.
func (m *Manager) SetClock(now func() time.Time) { m.now = now }

// SetTiming overrides the grace limit and the "active transfer" window. It
// exists for tests.
func (m *Manager) SetTiming(graceMax, activeWindow time.Duration) {
	m.graceMax, m.activeWindow = graceMax, activeWindow
}

// Start runs the expiry scheduler until ctx is cancelled.
func (m *Manager) Start(stop <-chan struct{}) {
	go func() {
		t := time.NewTicker(time.Second)
		defer t.Stop()
		for {
			select {
			case <-stop:
				return
			case <-t.C:
				if m.sweep() {
					m.persist()
					m.onChange()
				}
			}
		}
	}()
}

// sweep retires shares whose time is up. A share that has expired or been
// consumed stops accepting new requests at once, but stays open while peers
// that were already transferring finish (up to graceMax). It returns true if
// anything changed.
func (m *Manager) sweep() bool {
	now := m.now()
	changed := false
	m.mu.Lock()
	for id, s := range m.shares {
		end, reason := s.endTime(now)
		if end.IsZero() {
			continue
		}
		if now.After(end.Add(m.graceMax)) || !s.hasActivity(now, m.activeWindow) {
			m.retireLocked(id, s, reason)
			changed = true
		}
	}
	for id, g := range m.gone {
		if now.Sub(g.at) > goneKeep {
			delete(m.gone, id)
		}
	}
	m.mu.Unlock()
	return changed
}

// Sweep runs the expiry check once, as the scheduler does every few seconds.
// It reports whether any share was retired.
func (m *Manager) Sweep() bool {
	if m.sweep() {
		m.persist()
		m.onChange()
		return true
	}
	return false
}

// retireLocked closes a share for good and remembers who used it. Caller holds
// m.mu for writing.
func (m *Manager) retireLocked(id string, s *Share, reason string) {
	peers := s.cancelFlightsAndPeers()
	s.closeRoot()
	delete(m.shares, id)
	m.gone[id] = &goneInfo{reason: reason, at: m.now(), peers: peers}
}

// endTime reports when the share stopped accepting new requests (zero while
// it is live) and why.
func (s *Share) endTime(now time.Time) (time.Time, string) {
	if !s.consumedAt.IsZero() {
		return s.consumedAt, "completed"
	}
	if s.Lifetime.ExpiresAt != nil && now.After(*s.Lifetime.ExpiresAt) {
		return *s.Lifetime.ExpiresAt, "expired"
	}
	return time.Time{}, ""
}

func (s *Share) isExpired(now time.Time) bool {
	t, _ := s.endTime(now)
	return !t.IsZero()
}

// hasActivity reports whether any peer is moving data right now or did so
// within the window.
func (s *Share) hasActivity(now time.Time, window time.Duration) bool {
	s.actMu.Lock()
	defer s.actMu.Unlock()
	if len(s.flights) > 0 {
		return true
	}
	for _, t := range s.xfer {
		if now.Sub(t) <= window {
			return true
		}
	}
	return false
}

func (s *Share) peerActive(peer string, now time.Time, window time.Duration) bool {
	s.actMu.Lock()
	defer s.actMu.Unlock()
	for _, f := range s.flights {
		if f.peer == peer {
			return true
		}
	}
	t, ok := s.xfer[peer]
	return ok && now.Sub(t) <= window
}

func (s *Share) activePeers(now time.Time, window time.Duration) int {
	s.actMu.Lock()
	defer s.actMu.Unlock()
	set := map[string]bool{}
	for _, f := range s.flights {
		set[f.peer] = true
	}
	for p, t := range s.xfer {
		if now.Sub(t) <= window {
			set[p] = true
		}
	}
	return len(set)
}

// cancelFlightsAndPeers aborts every stream being served and returns the set
// of peers that ever used the share.
func (s *Share) cancelFlightsAndPeers() map[string]bool {
	s.actMu.Lock()
	defer s.actMu.Unlock()
	for _, f := range s.flights {
		f.cancel()
	}
	s.flights = nil
	peers := map[string]bool{}
	for p := range s.seen {
		peers[p] = true
	}
	for p := range s.xfer {
		peers[p] = true
	}
	return peers
}

func (s *Share) openRoot() error {
	abs := s.Path
	if !filepath.IsAbs(abs) {
		a, err := filepath.Abs(abs)
		if err != nil {
			return err
		}
		abs = a
	}
	info, err := os.Stat(abs)
	if err != nil {
		return err
	}
	if info.IsDir() {
		r, err := os.OpenRoot(abs)
		if err != nil {
			return err
		}
		s.root, s.rootRel, s.Kind = r, "", "folder"
	} else {
		r, err := os.OpenRoot(filepath.Dir(abs))
		if err != nil {
			return err
		}
		s.root, s.rootRel, s.Kind = r, filepath.Base(abs), "file"
	}
	s.Path = abs
	return nil
}

func (s *Share) closeRoot() {
	s.mu.Lock()
	if s.root != nil {
		_ = s.root.Close()
		s.root = nil
	}
	s.mu.Unlock()
}

// AddOptions are the caller-supplied parameters for Add.
type AddOptions struct {
	Label          string
	Visibility     string
	AllowedDevices []string
	LifetimeType   string
	DurationSec    int
	IncludeHidden  bool
	FollowSymlinks bool
}

// Add creates a share for an absolute local path.
func (m *Manager) Add(p string, opt AddOptions) (*Share, error) {
	if strings.TrimSpace(p) == "" {
		return nil, errors.New("a path is required")
	}
	s := &Share{
		ShareID:        "s_" + randHex(6),
		Path:           p,
		Label:          strings.TrimSpace(opt.Label),
		Visibility:     opt.Visibility,
		AllowedDevices: append([]string(nil), opt.AllowedDevices...),
		CreatedAt:      m.now(),
		Options:        Options{IncludeHidden: opt.IncludeHidden, FollowSymlinks: opt.FollowSymlinks},
	}
	if s.Visibility == "" {
		s.Visibility = "paired"
	}
	if err := s.openRoot(); err != nil {
		return nil, err
	}
	if s.Label == "" {
		s.Label = filepath.Base(s.Path)
	}
	switch opt.LifetimeType {
	case "", LifetimeUntilStopped:
		s.Lifetime = Lifetime{Type: LifetimeUntilStopped}
	case LifetimePersistent:
		s.Lifetime = Lifetime{Type: LifetimePersistent}
	case LifetimeTimed:
		if opt.DurationSec < 10 {
			s.closeRoot()
			return nil, errors.New("timed share must last at least 10 seconds")
		}
		if opt.DurationSec > 30*24*3600 {
			s.closeRoot()
			return nil, errors.New("timed share must not exceed 30 days")
		}
		exp := m.now().Add(time.Duration(opt.DurationSec) * time.Second)
		s.Lifetime = Lifetime{Type: LifetimeTimed, DurationSec: opt.DurationSec, ExpiresAt: &exp}
	case LifetimeOneTime:
		exp := m.now().Add(OneTimeSafetyExpiry)
		s.Lifetime = Lifetime{Type: LifetimeOneTime, ExpiresAt: &exp}
	default:
		s.closeRoot()
		return nil, fmt.Errorf("unknown lifetime %q", opt.LifetimeType)
	}

	m.mu.Lock()
	m.shares[s.ShareID] = s
	m.mu.Unlock()
	m.persist()
	m.onChange()
	return s, nil
}

// List returns every currently active share as a peer-visible summary.
func (m *Manager) List() []Summary {
	return m.ListVisible(true, "", "", nil)
}

// ListVisible returns the shares a peer may see. For a paired peer, offered is
// nil and a share is visible when it is open to paired peers or lists the peer
// in allowed_devices. For a Connect session, offered is the set of share IDs
// the sharer explicitly offered and nothing else is shown.
func (m *Manager) ListVisible(paired bool, deviceID, fingerprint string, offered map[string]bool) []Summary {
	now := m.now()
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]Summary, 0, len(m.shares))
	for _, s := range m.shares {
		if s.isExpired(now) || !s.visibleTo(paired, deviceID, fingerprint, offered) {
			continue
		}
		sum := Summary{
			ShareID: s.ShareID, Label: s.Label, Kind: s.Kind,
			Lifetime: s.Lifetime.Type, ExpiresAt: s.Lifetime.ExpiresAt,
		}
		if s.root != nil && s.Kind == "file" {
			sum.Name = filepath.Base(s.Path)
			if info, err := s.root.Stat(s.rootRel); err == nil {
				sum.Size = info.Size()
			}
		}
		out = append(out, sum)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Label < out[j].Label })
	return out
}

// GetVisible returns a share only if the peer is allowed to see it.
func (m *Manager) GetVisible(id string, paired bool, deviceID, fingerprint string, offered map[string]bool) (*Share, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.shares[id]
	if !ok || s.isExpired(m.now()) || !s.visibleTo(paired, deviceID, fingerprint, offered) {
		return nil, false
	}
	return s, true
}

// Seen records that a peer used any share endpoint, so it can later be told
// "expired" rather than "not found".
func (m *Manager) Seen(id, peer string) {
	m.mu.RLock()
	s := m.shares[id]
	m.mu.RUnlock()
	if s == nil || peer == "" {
		return
	}
	s.actMu.Lock()
	if s.seen == nil {
		s.seen = map[string]time.Time{}
	}
	s.seen[peer] = m.now()
	s.actMu.Unlock()
}

// GetForTransfer is GetVisible for the data-moving endpoints (file, hash,
// completion). After a share ends it still returns the share to a peer that
// was already transferring, until graceMax runs out (spec §6.2).
func (m *Manager) GetForTransfer(id string, paired bool, deviceID, fingerprint string, offered map[string]bool) (*Share, bool) {
	now := m.now()
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.shares[id]
	if !ok || !s.visibleTo(paired, deviceID, fingerprint, offered) {
		return nil, false
	}
	end, _ := s.endTime(now)
	if end.IsZero() {
		return s, true
	}
	if now.Before(end.Add(m.graceMax)) && s.peerActive(fingerprint, now, m.activeWindow) {
		return s, true
	}
	return nil, false
}

// TrackTransfer registers a stream being served to a peer. cancel is invoked
// if the share is stopped or its grace runs out. Call the returned function
// when the stream ends.
func (m *Manager) TrackTransfer(id, peer string, cancel context.CancelFunc) (release func()) {
	m.mu.RLock()
	s := m.shares[id]
	m.mu.RUnlock()
	if s == nil {
		return func() {}
	}
	s.actMu.Lock()
	if s.flights == nil {
		s.flights = map[int]flight{}
	}
	if s.xfer == nil {
		s.xfer = map[string]time.Time{}
	}
	s.nextFlight++
	n := s.nextFlight
	s.flights[n] = flight{peer: peer, cancel: cancel}
	s.xfer[peer] = m.now()
	s.actMu.Unlock()
	return func() {
		s.actMu.Lock()
		delete(s.flights, n)
		if s.xfer == nil {
			s.xfer = map[string]time.Time{}
		}
		s.xfer[peer] = m.now()
		s.actMu.Unlock()
	}
}

// TouchTransfer marks a peer as actively transferring without a long stream
// (hash and completion requests).
func (m *Manager) TouchTransfer(id, peer string) {
	m.mu.RLock()
	s := m.shares[id]
	m.mu.RUnlock()
	if s == nil || peer == "" {
		return
	}
	s.actMu.Lock()
	if s.xfer == nil {
		s.xfer = map[string]time.Time{}
	}
	s.xfer[peer] = m.now()
	s.actMu.Unlock()
}

// Ended explains why a share the peer used is no longer available. ok is false
// for peers that never used it, so an unrelated peer cannot probe for shares.
func (m *Manager) Ended(id, peer string) (reason string, ok bool) {
	now := m.now()
	m.mu.RLock()
	defer m.mu.RUnlock()
	if s, found := m.shares[id]; found {
		if _, r := s.endTime(now); r != "" {
			s.actMu.Lock()
			_, a := s.seen[peer]
			_, b := s.xfer[peer]
			s.actMu.Unlock()
			if a || b {
				return r, true
			}
		}
		return "", false
	}
	if g, found := m.gone[id]; found && g.peers[peer] && now.Sub(g.at) <= goneKeep {
		return g.reason, true
	}
	return "", false
}

// visibleTo decides whether a share is exposed to a peer.
func (s *Share) visibleTo(paired bool, deviceID, fingerprint string, offered map[string]bool) bool {
	if offered != nil {
		return offered[s.ShareID]
	}
	if !paired {
		return false
	}
	if s.Visibility != "specific" {
		return true
	}
	for _, d := range s.AllowedDevices {
		if d == deviceID || d == fingerprint {
			return true
		}
	}
	return false
}

// LocalView is a share as the local UI sees it, including shares that have
// ended but are still letting active transfers finish.
type LocalView struct {
	ShareID         string     `json:"share_id"`
	Path            string     `json:"path"`
	Kind            string     `json:"kind"`
	Label           string     `json:"label"`
	Visibility      string     `json:"visibility"`
	AllowedDevices  []string   `json:"allowed_devices,omitempty"`
	Lifetime        Lifetime   `json:"lifetime"`
	CreatedAt       time.Time  `json:"created_at"`
	Options         Options    `json:"options"`
	State           string     `json:"state"` // "active" | "finishing"
	EndReason       string     `json:"end_reason,omitempty"`
	DrainUntil      *time.Time `json:"drain_until,omitempty"`
	ActiveTransfers int        `json:"active_transfers"`
}

// LocalViews returns every share (active and finishing) for the local UI.
func (m *Manager) LocalViews() []LocalView {
	now := m.now()
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]LocalView, 0, len(m.shares))
	for _, s := range m.shares {
		v := LocalView{
			ShareID: s.ShareID, Path: s.Path, Kind: s.Kind, Label: s.Label,
			Visibility: s.Visibility, AllowedDevices: s.AllowedDevices, Lifetime: s.Lifetime,
			CreatedAt: s.CreatedAt, Options: s.Options, State: "active",
			ActiveTransfers: s.activePeers(now, m.activeWindow),
		}
		if end, reason := s.endTime(now); !end.IsZero() {
			until := end.Add(m.graceMax)
			v.State, v.EndReason, v.DrainUntil = "finishing", reason, &until
		}
		out = append(out, v)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out
}

// Local returns the full share records for the local UI.
func (m *Manager) Local() []*Share {
	m.mu.RLock()
	defer m.mu.RUnlock()
	out := make([]*Share, 0, len(m.shares))
	for _, s := range m.shares {
		out = append(out, s)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out
}

// Get returns the active share with id.
func (m *Manager) Get(id string) (*Share, bool) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	s, ok := m.shares[id]
	if !ok || s.isExpired(m.now()) {
		return nil, false
	}
	return s, true
}

// Stop ends a share immediately (the sharer's "Stop now"): in-flight streams
// are cancelled and nothing keeps running, even during a grace period.
func (m *Manager) Stop(id string) bool {
	m.mu.Lock()
	s, ok := m.shares[id]
	if ok {
		m.retireLocked(id, s, "stopped")
	}
	m.mu.Unlock()
	if ok {
		m.persist()
		m.onChange()
	}
	return ok
}

// StopAll hard-stops every share at once (spec §11.3): no grace.
func (m *Manager) StopAll() int {
	m.mu.Lock()
	n := len(m.shares)
	for id, s := range m.shares {
		m.retireLocked(id, s, "stopped")
	}
	m.mu.Unlock()
	m.persist()
	m.onChange()
	return n
}

// Consume ends a one-time share after a verified download by peer. It reports
// whether this call consumed it. The share stops accepting new requests at
// once; anyone already transferring may finish (same rule as expiry).
func (m *Manager) Consume(id, peer string) bool {
	m.mu.Lock()
	s, ok := m.shares[id]
	done := false
	if ok && s.Lifetime.Type == LifetimeOneTime && s.consumedAt.IsZero() && !s.isExpired(m.now()) {
		s.consumedAt, s.consumedBy = m.now(), peer
		done = true
		// The consumer has finished, so it no longer counts as transferring. If
		// nobody else is mid-transfer the share retires right away instead of
		// lingering for the activity window.
		s.actMu.Lock()
		if s.seen == nil {
			s.seen = map[string]time.Time{}
		}
		s.seen[peer] = m.now() // keep "completed" explainable to the consumer
		delete(s.xfer, peer)
		s.actMu.Unlock()
		if !s.hasActivity(m.now(), m.activeWindow) {
			m.retireLocked(id, s, "completed")
		}
	}
	m.mu.Unlock()
	if done {
		m.persist()
		m.onChange()
	}
	return done
}

func (m *Manager) persist() {
	m.mu.RLock()
	var keep []*Share
	now := m.now()
	for _, s := range m.shares {
		if s.isExpired(now) {
			continue // ended or consumed: only finishing transfers remain
		}
		switch s.Lifetime.Type {
		case LifetimePersistent, LifetimeOneTime, LifetimeTimed:
			keep = append(keep, s)
		}
	}
	m.mu.RUnlock()
	raw, err := json.Marshal(keep)
	if err != nil {
		return
	}
	_ = m.cfg.Update(func(st *config.Settings) { st.Shares = raw })
}

// --- read access ---

// ErrBadPath is returned for a client-supplied relative path that is not
// acceptable (absolute, contains "..", a drive letter, backslash, NUL or ":").
var ErrBadPath = errors.New("invalid path")

// CleanRel validates a client-supplied relative path.
func CleanRel(p string) (string, error) {
	if strings.ContainsRune(p, 0) {
		return "", ErrBadPath
	}
	if strings.Contains(p, "\\") {
		return "", ErrBadPath
	}
	if strings.HasPrefix(p, "/") || filepath.IsAbs(p) || filepath.VolumeName(p) != "" {
		return "", ErrBadPath
	}
	var segs []string
	for _, s := range strings.Split(p, "/") {
		if s == "" || s == "." {
			continue
		}
		if s == ".." {
			return "", ErrBadPath
		}
		if strings.ContainsRune(s, ':') { // NTFS ADS / drive-relative
			return "", ErrBadPath
		}
		segs = append(segs, s)
	}
	return path.Join(segs...), nil
}

// OpenFile opens rel inside the share, confined by os.Root.
func (s *Share) OpenFile(rel string) (*os.File, os.FileInfo, error) {
	clean, err := CleanRel(rel)
	if err != nil {
		return nil, nil, err
	}
	s.mu.RLock()
	root, rootRel, follow := s.root, s.rootRel, s.Options.FollowSymlinks
	s.mu.RUnlock()
	if root == nil {
		return nil, nil, errors.New("share is unavailable")
	}
	full := clean
	if rootRel != "" {
		if clean != "" {
			return nil, nil, ErrBadPath
		}
		full = rootRel
	} else if full == "" {
		full = "."
	}
	if !follow {
		if info, err := root.Lstat(full); err == nil && info.Mode()&os.ModeSymlink != 0 {
			return nil, nil, errors.New("symlinks are not followed")
		}
	}
	f, err := root.Open(full)
	if err != nil {
		return nil, nil, err
	}
	info, err := f.Stat()
	if err != nil {
		f.Close()
		return nil, nil, err
	}
	return f, info, nil
}

// Tree lists one directory level. rel may be "" for the share root.
func (s *Share) Tree(rel string) ([]Entry, error) {
	clean, err := CleanRel(rel)
	if err != nil {
		return nil, err
	}
	s.mu.RLock()
	root, rootRel, showHidden := s.root, s.rootRel, s.Options.IncludeHidden
	s.mu.RUnlock()
	if root == nil {
		return nil, errors.New("share is unavailable")
	}
	if rootRel != "" {
		if clean != "" {
			return nil, ErrBadPath
		}
		return nil, errors.New("not a folder")
	}
	openName := clean
	if openName == "" {
		openName = "."
	}
	f, err := root.Open(openName)
	if err != nil {
		return nil, err
	}
	defer f.Close()
	info, err := f.Stat()
	if err != nil {
		return nil, err
	}
	if !info.IsDir() {
		return nil, errors.New("not a folder")
	}
	dirents, err := f.ReadDir(-1)
	if err != nil {
		return nil, err
	}
	out := make([]Entry, 0, len(dirents))
	for _, de := range dirents {
		if !showHidden && strings.HasPrefix(de.Name(), ".") {
			continue
		}
		ei, err := de.Info()
		if err != nil {
			continue
		}
		ent := Entry{
			Name: de.Name(), Path: path.Join(clean, de.Name()),
			IsDir: de.IsDir(), Size: ei.Size(), ModTime: ei.ModTime(),
		}
		if !de.IsDir() {
			ent.ETag = Validator(ei)
		}
		out = append(out, ent)
	}
	sort.Slice(out, func(i, j int) bool {
		if out[i].IsDir != out[j].IsDir {
			return out[i].IsDir
		}
		return strings.ToLower(out[i].Name) < strings.ToLower(out[j].Name)
	})
	return out, nil
}

// Manifest recursively lists every file under rel (spec §7).
func (s *Share) Manifest(rel string) ([]ManifestEntry, int64, error) {
	clean, err := CleanRel(rel)
	if err != nil {
		return nil, 0, err
	}
	s.mu.RLock()
	root, rootRel, showHidden, follow := s.root, s.rootRel, s.Options.IncludeHidden, s.Options.FollowSymlinks
	s.mu.RUnlock()
	if root == nil {
		return nil, 0, errors.New("share is unavailable")
	}
	// A file share (rootRel set) or a file selection is a manifest of one.
	if rootRel != "" || clean != "" {
		f, info, err := s.OpenFile(rel)
		if err != nil {
			return nil, 0, err
		}
		defer f.Close()
		if !info.IsDir() {
			e := ManifestEntry{Path: clean, Name: info.Name(), Size: info.Size(), ModTime: info.ModTime(), ETag: Validator(info)}
			if rootRel != "" {
				e.Path = ""
			}
			return []ManifestEntry{e}, info.Size(), nil
		}
	}
	var out []ManifestEntry
	var total int64
	walkRoot := "."
	if clean != "" {
		walkRoot = clean
	}
	err = fs.WalkDir(root.FS(), walkRoot, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if p == "." || p == walkRoot && d.IsDir() {
			return nil
		}
		name := d.Name()
		if !showHidden && strings.HasPrefix(name, ".") {
			if d.IsDir() {
				return fs.SkipDir
			}
			return nil
		}
		if d.IsDir() {
			return nil
		}
		if !follow && d.Type()&os.ModeSymlink != 0 {
			return nil
		}
		info, err := d.Info()
		if err != nil {
			return nil
		}
		if !info.Mode().IsRegular() {
			return nil
		}
		relPath := p
		if clean != "" {
			relPath = strings.TrimPrefix(p, clean+"/")
			if p == clean {
				relPath = ""
			}
		}
		out = append(out, ManifestEntry{Path: relPath, Name: name, Size: info.Size(), ModTime: info.ModTime(), ETag: Validator(info)})
		total += info.Size()
		if len(out) > ManifestCap {
			return errors.New("manifest too large")
		}
		return nil
	})
	if err != nil {
		return nil, 0, err
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Path < out[j].Path })
	return out, total, nil
}

// Validator derives an ETag from size and modification time (spec §8.2). It is
// portable; a filesystem file-id can be folded in later without changing callers.
func Validator(info os.FileInfo) string {
	return fmt.Sprintf("%x-%x", info.Size(), info.ModTime().UnixNano())
}

// Risky returns a human reason when a path is dangerous to share, or "".
func Risky(p string) string {
	abs, err := filepath.Abs(p)
	if err != nil {
		return ""
	}
	clean := filepath.Clean(abs)
	if clean == filepath.Clean(string(filepath.VolumeName(clean)+string(filepath.Separator))) {
		return "a drive root"
	}
	if home, err := os.UserHomeDir(); err == nil && clean == filepath.Clean(home) {
		return "your home directory"
	}
	base := strings.ToLower(filepath.Base(clean))
	for _, risky := range []string{".ssh", ".gnupg", "appdata", "library", ".config"} {
		if base == risky {
			return "a sensitive directory (" + base + ")"
		}
	}
	return ""
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}
