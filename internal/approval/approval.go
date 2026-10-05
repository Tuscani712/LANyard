// Package approval holds incoming transfers that wait for a person to accept
// them: every push from a Connect session (spec §4.2) and, for paired peers,
// pushes larger than the ask-over threshold (spec §4.4). The peer service blocks
// the sender's offer on Ask while the local UI shows the request.
package approval

import (
	"context"
	"crypto/rand"
	"encoding/hex"
	"errors"
	"sort"
	"sync"
	"time"
)

const (
	// DefaultTimeout is how long a request waits for an answer.
	DefaultTimeout = 2 * time.Minute
	// maxPerPeer and maxTotal bound how many prompts a peer can stack up.
	maxPerPeer = 3
	maxTotal   = 20
	// rememberFor lets a resumed push (same files) skip a second prompt.
	rememberFor   = 30 * time.Minute
	maxFilesShown = 100
)

var (
	ErrTooMany = errors.New("too many pending requests")
	ErrTimeout = errors.New("no answer in time")
)

// File is one file in a request, shown to the person.
type File struct {
	Path string `json:"path"`
	Size int64  `json:"size"`
}

// Request is one transfer waiting for a decision. Names and paths come from the
// sender and must be shown as plain text only.
type Request struct {
	ID        string    `json:"id"`
	PeerFP    string    `json:"peer_fp"`
	PeerName  string    `json:"peer_name"`
	Reason    string    `json:"reason"` // "connect" | "large"
	Files     []File    `json:"files"`  // at most the first 100
	Count     int       `json:"count"`
	Total     int64     `json:"total"`
	CreatedAt time.Time `json:"created_at"`
	ExpiresAt time.Time `json:"expires_at"`
}

type entry struct {
	req Request
	ch  chan bool
	key string
}

// Manager tracks pending requests.
type Manager struct {
	onChange func()
	timeout  time.Duration

	mu      sync.Mutex
	pending map[string]*entry
	granted map[string]time.Time // key -> expiry of a remembered "accept"
}

// New creates a manager; onChange is called whenever the pending list changes.
func New(onChange func()) *Manager {
	if onChange == nil {
		onChange = func() {}
	}
	return &Manager{onChange: onChange, timeout: DefaultTimeout, pending: map[string]*entry{}, granted: map[string]time.Time{}}
}

// SetTimeout changes how long a request waits (tests).
func (m *Manager) SetTimeout(d time.Duration) { m.timeout = d }

// Ask registers req and blocks until it is accepted, rejected, times out, or
// ctx ends (the sender gave up). key identifies "the same transfer" so a
// resumed push that was already accepted is not asked again; empty disables it.
func (m *Manager) Ask(ctx context.Context, req Request, key string) (bool, error) {
	now := time.Now()
	m.mu.Lock()
	for k, exp := range m.granted {
		if now.After(exp) {
			delete(m.granted, k)
		}
	}
	if key != "" {
		if exp, ok := m.granted[key]; ok && now.Before(exp) {
			m.mu.Unlock()
			return true, nil
		}
	}
	perPeer := 0
	for _, e := range m.pending {
		if e.req.PeerFP == req.PeerFP {
			perPeer++
		}
	}
	if perPeer >= maxPerPeer || len(m.pending) >= maxTotal {
		m.mu.Unlock()
		return false, ErrTooMany
	}
	b := make([]byte, 6)
	_, _ = rand.Read(b)
	req.ID = "a_" + hex.EncodeToString(b)
	req.CreatedAt = now
	req.ExpiresAt = now.Add(m.timeout)
	if len(req.Files) > maxFilesShown {
		req.Files = req.Files[:maxFilesShown]
	}
	e := &entry{req: req, ch: make(chan bool, 1), key: key}
	m.pending[req.ID] = e
	m.mu.Unlock()
	m.onChange()

	defer func() {
		m.mu.Lock()
		delete(m.pending, req.ID)
		m.mu.Unlock()
		m.onChange()
	}()
	timer := time.NewTimer(m.timeout)
	defer timer.Stop()
	select {
	case ok := <-e.ch:
		if ok && key != "" {
			m.mu.Lock()
			m.granted[key] = time.Now().Add(rememberFor)
			m.mu.Unlock()
		}
		return ok, nil
	case <-timer.C:
		return false, ErrTimeout
	case <-ctx.Done():
		return false, ctx.Err()
	}
}

// Pending lists waiting requests, oldest first.
func (m *Manager) Pending() []Request {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Request, 0, len(m.pending))
	for _, e := range m.pending {
		out = append(out, e.req)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].CreatedAt.Before(out[j].CreatedAt) })
	return out
}

// Decide answers a pending request. It reports whether the request existed.
func (m *Manager) Decide(id string, accept bool) bool {
	m.mu.Lock()
	e, ok := m.pending[id]
	m.mu.Unlock()
	if !ok {
		return false
	}
	select {
	case e.ch <- accept:
	default:
	}
	return true
}
