// Package inbox receives pushed files from paired or connected peers. Files
// land only inside the Inbox directory, are written as .lanpart, verified by
// SHA-256 and atomically renamed, and never overwrite an existing name.
package inbox

import (
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"
	"unicode"
	"unicode/utf8"

	"lanyard/internal/identity"
	"lanyard/internal/shares"
	"lanyard/internal/xferlog"
)

// DefaultMaxBytes is the limit applied when the peer has none configured: none.
// The sender was allowed to push (paired with push permission, or accepted by a
// person in a Connect session) and free space is still checked on every offer.
const DefaultMaxBytes = int64(1) << 62

// MaxSnippetBytes caps a text snippet (64 KB of UTF-8).
const MaxSnippetBytes = 64 << 10

// maxSnippets bounds how many received snippets are kept in memory.
const maxSnippets = 100

// Snippet is a short text message received from a peer. It is held in memory
// (not written to disk) and shown with Copy and Dismiss.
type Snippet struct {
	ID         string    `json:"id"`
	PeerFP     string    `json:"peer_fp"`
	Text       string    `json:"text"`
	ReceivedAt time.Time `json:"received_at"`
}

// ValidateSnippet enforces the snippet rules: non-empty, a whole number of
// UTF-8 bytes, within the size cap, and free of control characters other than
// newline and tab (so binary or terminal escapes cannot ride along).
func ValidateSnippet(text string) error {
	if text == "" {
		return errors.New("snippet is empty")
	}
	if len(text) > MaxSnippetBytes {
		return fmt.Errorf("snippet exceeds %d bytes", MaxSnippetBytes)
	}
	if !utf8.ValidString(text) {
		return errors.New("snippet is not valid UTF-8")
	}
	for _, r := range text {
		if unicode.IsControl(r) && r != '\n' && r != '\t' {
			return errors.New("snippet contains control characters")
		}
	}
	return nil
}

// syncMin is the size from which a received file is fsynced before it is renamed
// into place. A smaller file lost to a crash is simply sent again.
const syncMin = 1 << 20

const (
	// defaultProgressThrottle caps how often mid-copy byte progress drives an
	// onChange (the SSE "incoming" event), so a fast LAN transfer cannot flood
	// the UI. Roughly four updates a second.
	defaultProgressThrottle = 250 * time.Millisecond
	// defaultStallTimeout is how long a body that is actually being read may
	// make no progress before the reaper treats its connection as dead. It only
	// applies while a body is in flight: a slow 1 GB transfer on the LAN keeps
	// resetting the deadline on every read.
	defaultStallTimeout = 60 * time.Second
	// defaultIdleTimeout is how long a push that has no body in flight may sit
	// untouched before the reaper removes it. Senders legitimately pause between
	// files (hashing the next one can take minutes on a large file), so this is
	// far more generous than the in-flight stall timeout.
	defaultIdleTimeout = 10 * time.Minute
	// defaultSweepInterval is how often the reaper checks for stalled pushes.
	defaultSweepInterval = 5 * time.Second
)

// FileReq is one file in a push offer.
type FileReq struct {
	RelPath string    `json:"rel_path"`
	Size    int64     `json:"size"`
	MTime   time.Time `json:"mtime"`
}

// FileState tracks one file of a push.
type FileState struct {
	RelPath string       `json:"rel_path"`
	Size    int64        `json:"size"`
	MTime   time.Time    `json:"mtime"`
	Done    int64        `json:"done"`
	live    atomic.Int64 // bytes of this file received so far, updated while streaming
	Final   string       `json:"-"`
	Part    string       `json:"-"`
}

// Push is one accepted batch of files.
type Push struct {
	ID        string
	PeerFP    string
	Mode      string
	MaxBytes  int64
	Total     int64
	Files     map[string]*FileState // key: rel path as offered
	CreatedAt time.Time

	cancelled atomic.Bool
	// dead marks a push whose connection died or whose body stalled: reads in
	// flight are aborted, but unlike a receiver Cancel() the .lanpart files are
	// kept so a re-offer can resume.
	dead atomic.Bool
	// inFlight is true while a body is actually being read for this push. The
	// reaper uses it to choose between the short stall timeout (a body that has
	// stopped producing bytes) and the long idle timeout (a push waiting between
	// files or before its first byte).
	inFlight atomic.Bool
	// lastProgress is the UnixNano of the last byte received for this push, or
	// of the last body start/end. The reaper uses it to decide a connection has
	// stalled or a push has gone idle.
	lastProgress atomic.Int64
}

// ErrCancelled is returned to the sender once the receiving person has
// cancelled an accepted push.
var ErrCancelled = errors.New("cancelled by the receiver")

// cancelReader fails reads as soon as the push is cancelled, so a transfer in
// flight stops within one buffer rather than at the end of the request. While
// bytes arrive it touches the push's progress and drives the throttled
// onChange so the receiving UI shows live progress.
type cancelReader struct {
	p  *Push
	st *FileState
	r  io.Reader
	m  *Manager
}

func (c cancelReader) Read(b []byte) (int, error) {
	if c.p.cancelled.Load() || c.p.dead.Load() {
		return 0, ErrCancelled
	}
	n, err := c.r.Read(b)
	if c.st != nil {
		c.st.live.Add(int64(n))
	}
	if n > 0 && c.m != nil {
		c.p.touch(c.m.now())
		c.m.notifyProgress()
	}
	return n, err
}

// beginRead records that a body is now being read for this push, so the reaper
// judges it by the short stall timeout rather than the long idle timeout.
func (p *Push) beginRead(now time.Time) {
	p.inFlight.Store(true)
	p.lastProgress.Store(now.UnixNano())
}

// endRead clears the in-flight marker and resets the idle deadline to now.
func (p *Push) endRead(now time.Time) {
	p.inFlight.Store(false)
	p.lastProgress.Store(now.UnixNano())
}

// touch records fresh activity while a body is being read.
func (p *Push) touch(now time.Time) { p.lastProgress.Store(now.UnixNano()) }

// IncomingView is what the UI shows for a push being received.
type IncomingView struct {
	ID         string    `json:"id"`
	PeerFP     string    `json:"peer_fp"`
	Mode       string    `json:"mode"`
	Total      int64     `json:"total"`
	Done       int64     `json:"done"`
	FilesTotal int       `json:"files_total"`
	FilesDone  int       `json:"files_done"`
	Current    string    `json:"current,omitempty"`
	StartedAt  time.Time `json:"started_at"`
}

// Incoming lists the pushes being received, oldest first.
func (m *Manager) Incoming() []IncomingView {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]IncomingView, 0, len(m.pushes))
	for _, p := range m.pushes {
		v := IncomingView{ID: p.ID, PeerFP: p.PeerFP, Mode: p.Mode, Total: p.Total, FilesTotal: len(p.Files), StartedAt: p.CreatedAt}
		for _, f := range p.Files {
			d := f.Done
			if l := f.live.Load(); l > d {
				d = l
			}
			if d > f.Size {
				d = f.Size
			}
			v.Done += d
			if f.Done >= f.Size {
				v.FilesDone++
			} else if d > 0 && v.Current == "" {
				v.Current = f.RelPath
			}
		}
		out = append(out, v)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.Before(out[j].StartedAt) })
	return out
}

// HasActivePush reports whether a push from peerFP is currently registered
// (accepted, not cancelled or dead). Discovery uses it so a peer sending to us
// is not evicted on transient probe misses.
func (m *Manager) HasActivePush(peerFP string) bool {
	if peerFP == "" {
		return false
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, p := range m.pushes {
		if p.PeerFP == peerFP && !p.cancelled.Load() && !p.dead.Load() {
			return true
		}
	}
	return false
}

// Cancel stops an accepted push. Files already received stay; partial files
// are removed. The sender's next request fails with ErrCancelled.
func (m *Manager) Cancel(id string) bool {
	m.mu.Lock()
	p, ok := m.pushes[id]
	if ok {
		delete(m.pushes, id)
		p.cancelled.Store(true)
		m.gone[id] = struct{}{}
	}
	m.mu.Unlock()
	if !ok {
		return false
	}
	for _, f := range p.Files {
		if f.Done < f.Size || f.Size == 0 {
			_ = os.Remove(f.Part)
		}
	}
	m.onChange()
	return true
}

// WasCancelled reports whether a push was cancelled by the receiving person.
func (m *Manager) WasCancelled(id string) bool {
	m.mu.Lock()
	defer m.mu.Unlock()
	_, ok := m.gone[id]
	return ok
}

// Manager owns pushes for one process.
type Manager struct {
	dir       string
	onChange  func()
	onOffer   func(peerFP string, files int, total int64)
	onDone    func(peerFP string, files []ReceivedFile)
	onSnippet func(peerFP, text string)
	onFail    func(peerFP, reason string)

	// Tunables; overridable before or during use. now is injectable for tests.
	now           func() time.Time
	progressEvery time.Duration
	stallTimeout  time.Duration
	idleTimeout   time.Duration
	sweepInterval time.Duration

	mu         sync.Mutex
	lastNotify time.Time
	pushes     map[string]*Push
	gone       map[string]struct{} // cancelled push ids
	snippets   []*Snippet

	done      chan struct{}
	closeOnce sync.Once

	// xlog records stalled-push removals (the reaper) in the shared four-area
	// diagnostics log. Nil is a valid no-op.
	xlog *xferlog.Recorder
}

// SetXferLog attaches the shared diagnostics recorder.
func (m *Manager) SetXferLog(r *xferlog.Recorder) { m.xlog = r }

// stallLog records a reaper/failure removal of an incoming push.
func (m *Manager) stallLog(peerFP, id, reason string) {
	if m.xlog == nil {
		return
	}
	m.xlog.Record(nil, xferlog.Entry{
		Area: xferlog.AreaPushing, Level: xferlog.LevelWarn, Outcome: "stall",
		FP: identity.ShortID(peerFP), Session: id, Reason: reason,
	})
}

// ReceivedFile describes one file that finished landing in the Inbox, for the
// transfer history entry the receiver records.
type ReceivedFile struct {
	Rel  string `json:"rel"`  // path as offered (relative to the Inbox root)
	Name string `json:"name"` // the name it was saved under
	Size int64  `json:"size"`
}

// FreeSpace reports the bytes available to the current user on the volume
// holding path, or 0 if that cannot be determined. When path does not exist yet
// (an Inbox is only created on the first push), the nearest existing parent is
// measured instead, since it is on the same volume.
func FreeSpace(path string) int64 {
	if n := freeSpace(path); n > 0 {
		return n
	}
	if dir := nearestExistingDir(path); dir != "" {
		return freeSpace(dir)
	}
	return 0
}

// nearestExistingDir returns the closest existing ancestor of path, or "".
func nearestExistingDir(path string) string {
	if strings.TrimSpace(path) == "" {
		return ""
	}
	p := filepath.Clean(path)
	for {
		if info, err := os.Stat(p); err == nil && info.IsDir() {
			return p
		}
		parent := filepath.Dir(p)
		if parent == p { // reached the volume root
			return ""
		}
		p = parent
	}
}

func New(dir string, onChange func()) *Manager {
	if onChange == nil {
		onChange = func() {}
	}
	m := &Manager{
		dir: dir, onChange: onChange, pushes: map[string]*Push{}, gone: map[string]struct{}{},
		now: time.Now, progressEvery: defaultProgressThrottle,
		stallTimeout: defaultStallTimeout, idleTimeout: defaultIdleTimeout,
		sweepInterval: defaultSweepInterval,
		done:          make(chan struct{}),
	}
	m.startReaper()
	return m
}

// Close stops the background reaper. It is safe to call more than once.
func (m *Manager) Close() {
	m.closeOnce.Do(func() { close(m.done) })
}

// notifyProgress drives onChange at most once per progressEvery while bytes are
// arriving. Completion and error paths call onChange directly, unthrottled.
func (m *Manager) notifyProgress() {
	m.mu.Lock()
	now := m.now()
	if now.Sub(m.lastNotify) < m.progressEvery {
		m.mu.Unlock()
		return
	}
	m.lastNotify = now
	m.mu.Unlock()
	m.onChange()
}

// SetOnFail registers a callback for a push that fails before it finishes: the
// connection died or stalled. The reason is for logs and the transfer history.
func (m *Manager) SetOnFail(fn func(peerFP, reason string)) { m.onFail = fn }

// SetStallTimeout sets how long an in-flight body may make no progress before
// the reaper removes the push, and scales the sweep interval to match. Intended
// for tests; a non-positive value is ignored.
func (m *Manager) SetStallTimeout(d time.Duration) {
	if d <= 0 {
		return
	}
	m.mu.Lock()
	m.stallTimeout = d
	m.refreshSweepLocked()
	m.mu.Unlock()
}

// SetIdleTimeout sets how long a push with no body in flight may sit before the
// reaper removes it, and scales the sweep interval to match. Intended for
// tests; a non-positive value is ignored.
func (m *Manager) SetIdleTimeout(d time.Duration) {
	if d <= 0 {
		return
	}
	m.mu.Lock()
	m.idleTimeout = d
	m.refreshSweepLocked()
	m.mu.Unlock()
}

// refreshSweepLocked derives the sweep interval from the shorter of the two
// timeouts so a test-sized timeout is noticed promptly. Caller holds m.mu.
func (m *Manager) refreshSweepLocked() {
	d := m.stallTimeout
	if m.idleTimeout > 0 && m.idleTimeout < d {
		d = m.idleTimeout
	}
	interval := d / 4
	if interval < 10*time.Millisecond {
		interval = 10 * time.Millisecond
	}
	m.sweepInterval = interval
}

func (m *Manager) startReaper() {
	go func() {
		for {
			m.mu.Lock()
			interval := m.sweepInterval
			m.mu.Unlock()
			if interval <= 0 {
				interval = defaultSweepInterval
			}
			t := time.NewTimer(interval)
			select {
			case <-m.done:
				t.Stop()
				return
			case <-t.C:
				m.reapStalled()
			}
		}
	}()
}

// reapStalled removes every push whose body has stopped producing bytes for the
// short stall timeout, or that has sat idle with no body in flight for the much
// longer idle timeout. For example a sender hashing a large next file holds no
// body open, so it gets the idle limit; a body stalled mid-read gets the stall
// limit. Those pushes are surfaced through onFail and the UI is refreshed. The
// .lanpart files are deliberately kept so a re-offer can resume.
func (m *Manager) reapStalled() {
	now := m.now()
	var stalled []*Push
	m.mu.Lock()
	for id, p := range m.pushes {
		limit := m.idleTimeout
		if p.inFlight.Load() {
			limit = m.stallTimeout
		}
		if limit <= 0 {
			continue
		}
		last := time.Unix(0, p.lastProgress.Load())
		if now.Sub(last) > limit {
			delete(m.pushes, id)
			p.dead.Store(true) // abort any read still in flight
			stalled = append(stalled, p)
		}
	}
	m.mu.Unlock()
	if len(stalled) == 0 {
		return
	}
	for _, p := range stalled {
		m.stallLog(p.PeerFP, p.ID, "the connection stalled")
		if m.onFail != nil {
			m.onFail(p.PeerFP, "the connection stalled")
		}
	}
	m.onChange()
}

// failPush removes a push whose body copy failed (a dropped or dead
// connection), so it does not stay listed in /api/incoming forever. Like the
// reaper it keeps the .lanpart files: the sender re-offers on every retry and
// resumes from what already arrived.
func (m *Manager) failPush(id, reason string) bool {
	m.mu.Lock()
	p, ok := m.pushes[id]
	if ok {
		delete(m.pushes, id)
		p.dead.Store(true)
	}
	m.mu.Unlock()
	if !ok {
		return false
	}
	m.stallLog(p.PeerFP, p.ID, reason)
	if m.onFail != nil {
		m.onFail(p.PeerFP, reason)
	}
	m.onChange()
	return true
}

// SetOnOffer registers a callback for each push that starts being received
// (used to notify the person that files are arriving).
func (m *Manager) SetOnOffer(fn func(peerFP string, files int, total int64)) { m.onOffer = fn }

// SetOnDone registers a callback for each push that finishes being received,
// with the files that landed so the caller can record a history entry.
func (m *Manager) SetOnDone(fn func(peerFP string, files []ReceivedFile)) { m.onDone = fn }

// SetOnSnippet registers a callback for each text snippet that is received
// (used to raise a desktop notification).
func (m *Manager) SetOnSnippet(fn func(peerFP, text string)) { m.onSnippet = fn }

// AddSnippet validates and stores a received text snippet.
func (m *Manager) AddSnippet(peerFP, text string) (*Snippet, error) {
	if err := ValidateSnippet(text); err != nil {
		return nil, err
	}
	s := &Snippet{ID: "s_" + randHex(6), PeerFP: peerFP, Text: text, ReceivedAt: time.Now()}
	m.mu.Lock()
	m.snippets = append(m.snippets, s)
	if len(m.snippets) > maxSnippets {
		m.snippets = m.snippets[len(m.snippets)-maxSnippets:]
	}
	m.mu.Unlock()
	m.onChange()
	if m.onSnippet != nil {
		m.onSnippet(peerFP, text)
	}
	return s, nil
}

// Snippets lists received snippets, oldest first.
func (m *Manager) Snippets() []Snippet {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Snippet, 0, len(m.snippets))
	for _, s := range m.snippets {
		out = append(out, *s)
	}
	return out
}

// DismissSnippet removes a received snippet from the list.
func (m *Manager) DismissSnippet(id string) bool {
	m.mu.Lock()
	idx := -1
	for i, s := range m.snippets {
		if s.ID == id {
			idx = i
			break
		}
	}
	if idx >= 0 {
		m.snippets = append(m.snippets[:idx], m.snippets[idx+1:]...)
	}
	m.mu.Unlock()
	if idx < 0 {
		return false
	}
	m.onChange()
	return true
}

func (m *Manager) Dir() string { return m.dir }

// EnsureDir creates the Inbox root if it does not exist yet (pushes create it
// lazily; the settings screen and "Open folder" also call this).
func (m *Manager) EnsureDir() error {
	m.mu.Lock()
	dir := m.dir
	m.mu.Unlock()
	return os.MkdirAll(dir, 0o700)
}

// SetDir changes the Inbox root for future pushes (settings, §11).
func (m *Manager) SetDir(dir string) {
	if strings.TrimSpace(dir) == "" {
		return
	}
	m.mu.Lock()
	m.dir = dir
	m.mu.Unlock()
}

func sanitize(rel string) (string, error) {
	clean, err := shares.CleanRel(rel)
	if err != nil {
		return "", err
	}
	if clean == "" {
		return "", errors.New("empty path")
	}
	segs := strings.Split(clean, "/")
	out := make([]string, 0, len(segs))
	for _, s := range segs {
		s = strings.Map(func(r rune) rune {
			if unicode.IsControl(r) {
				return -1
			}
			return r
		}, s)
		s = strings.TrimRight(s, ". ")
		if s == "" {
			s = "_"
		}
		out = append(out, s)
	}
	return strings.Join(out, "/"), nil
}

// Offer validates and registers a push. It returns per-file resume offsets for
// files already partly received. mode is "paired" or "session".
func (m *Manager) Offer(peerFP, mode string, files []FileReq, maxBytes int64) (*Push, error) {
	if maxBytes <= 0 {
		maxBytes = DefaultMaxBytes
	}
	if err := os.MkdirAll(m.dir, 0o700); err != nil {
		return nil, err
	}
	p := &Push{
		ID: "p_" + randHex(6), PeerFP: peerFP, Mode: mode, MaxBytes: maxBytes,
		Files: map[string]*FileState{}, CreatedAt: time.Now(),
	}
	for _, f := range files {
		if f.Size < 0 {
			return nil, errors.New("negative size")
		}
		rel, err := sanitize(f.RelPath)
		if err != nil {
			return nil, fmt.Errorf("bad file name %q: %w", f.RelPath, err)
		}
		if _, dup := p.Files[rel]; dup {
			return nil, fmt.Errorf("duplicate file %q", rel)
		}
		final := filepath.Join(m.dir, filepath.FromSlash(rel))
		if !pathWithin(m.dir, final) {
			return nil, errors.New("path escapes the Inbox")
		}
		st := &FileState{RelPath: rel, Size: f.Size, MTime: f.MTime, Final: final, Part: final + ".lanpart"}
		if info, err := os.Stat(st.Part); err == nil {
			// Resume: trust only bytes already on disk, never more than the size.
			done := info.Size()
			if done > f.Size {
				done = 0
			}
			st.Done = done
			st.live.Store(done)
		}
		p.Files[rel] = st
		p.Total += f.Size
	}
	if p.Total > maxBytes {
		return nil, fmt.Errorf("push of %d bytes exceeds the limit of %d", p.Total, maxBytes)
	}
	need := freeSpace(m.dir)
	if need > 0 && p.Total > need-(64<<20) {
		return nil, errors.New("insufficient storage")
	}
	m.mu.Lock()
	m.pushes[p.ID] = p
	m.lastNotify = m.now()
	m.mu.Unlock()
	p.lastProgress.Store(m.now().UnixNano())
	m.onChange()
	if m.onOffer != nil {
		m.onOffer(peerFP, len(p.Files), p.Total)
	}
	return p, nil
}

// Get returns a push owned by peerFP.
func (m *Manager) Get(id, peerFP string) (*Push, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	p, ok := m.pushes[id]
	if !ok || p.PeerFP != peerFP {
		return nil, false
	}
	return p, true
}

// WriteChunk appends bytes at offset into a file's .lanpart.
func (m *Manager) WriteChunk(id, peerFP, rel string, offset int64, r io.Reader) (int64, error) {
	p, ok := m.Get(id, peerFP)
	if !ok {
		return 0, errors.New("no such push")
	}
	st, ok := p.Files[rel]
	if !ok {
		return 0, errors.New("no such file in push")
	}
	if offset != st.Done {
		return 0, fmt.Errorf("expected offset %d, got %d", st.Done, offset)
	}
	if err := os.MkdirAll(filepath.Dir(st.Part), 0o700); err != nil {
		return 0, err
	}
	st.live.Store(offset)
	f, err := os.OpenFile(st.Part, os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return 0, err
	}
	p.beginRead(m.now())
	n, err := io.CopyBuffer(f, cancelReader{p: p, st: st, r: io.LimitReader(r, st.Size-offset), m: m}, make([]byte, 256*1024))
	p.endRead(m.now())
	if err != nil {
		f.Close()
		if p.cancelled.Load() {
			// An explicit receiver Cancel() removes the partial.
			_ = os.Remove(st.Part)
			return n, ErrCancelled
		}
		// A read/write error means the connection died; drop the push now but
		// keep the .lanpart so a re-offer resumes from what already arrived.
		m.failPush(id, err.Error())
		return n, err
	}
	if st.Size >= syncMin {
		if err := f.Sync(); err != nil {
			return n, err
		}
	}
	m.mu.Lock()
	st.Done += n
	m.mu.Unlock()
	m.onChange()
	return n, nil
}

// Receive stores a whole file that arrives in a single request together with its
// SHA-256, and finalizes it in the same call: the digest is computed while the
// bytes are written (no second read from disk), the size and digest are checked,
// and the file is renamed into place. It is the fast path for small files; large
// or partly received files use WriteChunk and Complete.
func (m *Manager) Receive(id, peerFP, rel, wantSHA string, r io.Reader) (*FileState, error) {
	p, ok := m.Get(id, peerFP)
	if !ok {
		return nil, errors.New("no such push")
	}
	st, ok := p.Files[rel]
	if !ok {
		return nil, errors.New("no such file in push")
	}
	if err := os.MkdirAll(filepath.Dir(st.Part), 0o700); err != nil {
		return nil, err
	}
	st.live.Store(0)
	f, err := os.OpenFile(st.Part, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, err
	}
	h := sha256.New()
	// Read one byte more than announced so an oversized body is noticed.
	p.beginRead(m.now())
	n, err := io.CopyBuffer(io.MultiWriter(f, h), cancelReader{p: p, st: st, r: io.LimitReader(r, st.Size+1), m: m}, make([]byte, 64*1024))
	p.endRead(m.now())
	if err == nil && st.Size >= syncMin {
		err = f.Sync()
	}
	if cerr := f.Close(); err == nil {
		err = cerr
	}
	fail := func(e error) (*FileState, error) {
		_ = os.Remove(st.Part)
		return nil, e
	}
	if err != nil {
		if p.cancelled.Load() {
			// An explicit receiver Cancel() removes the partial.
			return fail(ErrCancelled)
		}
		// A read/write error means the connection died; drop the push now but
		// keep the .lanpart so a re-offer can resume from what already arrived.
		m.failPush(id, err.Error())
		return nil, err
	}
	if n != st.Size {
		return fail(fmt.Errorf("size mismatch: have %d, expected %d", n, st.Size))
	}
	if !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), wantSHA) {
		return fail(errors.New("checksum mismatch"))
	}
	final := uniqueName(st.Final)
	if err := os.MkdirAll(filepath.Dir(final), 0o700); err != nil {
		return fail(err)
	}
	if err := os.Rename(st.Part, final); err != nil {
		return fail(err)
	}
	if !st.MTime.IsZero() {
		_ = os.Chtimes(final, st.MTime, st.MTime)
	}
	m.mu.Lock()
	st.Final, st.Done = final, st.Size
	m.mu.Unlock()
	m.onChange()
	return st, nil
}

// Complete verifies the whole-file SHA-256 and atomically renames the part in
// place, choosing a non-colliding name. It sets the remote modification time.
func (m *Manager) Complete(id, peerFP, rel, wantSHA string) (*FileState, error) {
	p, ok := m.Get(id, peerFP)
	if !ok {
		return nil, errors.New("no such push")
	}
	st, ok := p.Files[rel]
	if !ok {
		return nil, errors.New("no such file in push")
	}
	gotSHA, gotSize, err := hashFile(st.Part)
	if err != nil {
		return nil, err
	}
	if gotSize != st.Size {
		return nil, fmt.Errorf("size mismatch: have %d, expected %d", gotSize, st.Size)
	}
	if wantSHA != "" && !strings.EqualFold(gotSHA, wantSHA) {
		return nil, errors.New("checksum mismatch")
	}
	final := uniqueName(st.Final)
	if err := os.MkdirAll(filepath.Dir(final), 0o700); err != nil {
		return nil, err
	}
	if err := os.Rename(st.Part, final); err != nil {
		return nil, err
	}
	if !st.MTime.IsZero() {
		_ = os.Chtimes(final, st.MTime, st.MTime)
	}
	st.Final = final
	m.onChange()
	return st, nil
}

// Finish removes a completed push and reports the files that landed.
func (m *Manager) Finish(id, peerFP string) bool {
	var received []ReceivedFile
	m.mu.Lock()
	p, ok := m.pushes[id]
	if ok && p.PeerFP == peerFP {
		for _, st := range p.Files {
			name := filepath.Base(st.Final)
			if st.Final == "" {
				name = filepath.Base(st.RelPath)
			}
			received = append(received, ReceivedFile{Rel: st.RelPath, Name: name, Size: st.Size})
		}
		sort.Slice(received, func(i, j int) bool { return received[i].Rel < received[j].Rel })
		delete(m.pushes, id)
	}
	m.mu.Unlock()
	if ok {
		m.onChange()
		if m.onDone != nil {
			m.onDone(peerFP, received)
		}
	}
	return ok
}

// Count returns how many pushes are in flight.
func (m *Manager) Count() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return len(m.pushes)
}

func hashFile(path string) (string, int64, error) {
	f, err := os.Open(path)
	if err != nil {
		return "", 0, err
	}
	defer f.Close()
	h := sha256.New()
	n, err := io.CopyBuffer(h, f, make([]byte, 256*1024))
	if err != nil {
		return "", 0, err
	}
	return hex.EncodeToString(h.Sum(nil)), n, nil
}

// uniqueName returns path, or "name (1).ext", "name (2).ext", … if taken.
func uniqueName(path string) string {
	if _, err := os.Stat(path); os.IsNotExist(err) {
		return path
	}
	ext := filepath.Ext(path)
	base := strings.TrimSuffix(path, ext)
	for i := 1; i < 10000; i++ {
		cand := fmt.Sprintf("%s (%d)%s", base, i, ext)
		if _, err := os.Stat(cand); os.IsNotExist(err) {
			return cand
		}
	}
	return path
}

// pathWithin reports whether child is inside dir.
func pathWithin(dir, child string) bool {
	rel, err := filepath.Rel(dir, child)
	if err != nil {
		return false
	}
	return rel != ".." && !strings.HasPrefix(rel, ".."+string(filepath.Separator))
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// SortedOffsets returns the resume offsets for an offer (used in the response).
func (p *Push) SortedOffsets() []struct {
	RelPath string `json:"rel_path"`
	Offset  int64  `json:"offset"`
} {
	keys := make([]string, 0, len(p.Files))
	for k := range p.Files {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	out := make([]struct {
		RelPath string `json:"rel_path"`
		Offset  int64  `json:"offset"`
	}, 0, len(keys))
	for _, k := range keys {
		out = append(out, struct {
			RelPath string `json:"rel_path"`
			Offset  int64  `json:"offset"`
		}{k, p.Files[k].Done})
	}
	return out
}
