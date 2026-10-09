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
	"math"
	"os"
	"path/filepath"
	"runtime"
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

	// completeMu serializes the finalize (hash + rename) of this file, so two
	// concurrent "complete" requests for the same file cannot both place it.
	completeMu sync.Mutex
	// completeDone is set once the file has been verified and renamed into
	// place. A repeated complete/whole-file request for the same file is then
	// an idempotent success that returns the same result instead of hashing or
	// renaming again. It is atomic because Incoming() reads it under m.mu while
	// the finalize paths set it while only holding the file's completeMu.
	completeDone atomic.Bool
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

	// live is the cumulative bytes received for this push, maintained so the
	// rate sampler can read one value without taking a lock. It is seeded from
	// the bytes already on disk when a push is resumed.
	live atomic.Int64

	// samples and sampleAt back the smoothed receive rate. They are guarded by
	// samplesMu rather than m.mu so bytes can be sampled while a stream is
	// copied and Incoming() can read the published speed without lock order
	// problems.
	samplesMu sync.Mutex
	samples   []rateSample
	sampleAt  time.Time
	// displayAt is when the displayed speed/ETA text was last refreshed. It
	// gates the published text to RateDisplayEvery while samples keep arriving.
	displayAt time.Time
	// speedBits holds the displayed smoothed receive rate as math.Float64bits of
	// bytes/second, published atomically for Incoming() to read.
	speedBits atomic.Uint64
	// etaBits holds the displayed ETA in whole seconds, published atomically.
	etaBits atomic.Int64
	// fileDone times every completed file, for the rolling files-per-second rate
	// that keeps a many-small-files ETA honest. Guarded by samplesMu so it can be
	// appended as files land while Incoming() reads it.
	fileDone []time.Time
}

// observeRate records the cumulative byte total and republishes the smoothed
// receive rate and ETA. It is called as bytes arrive; samples are throttled so a
// fast stream does not build an unbounded sample slice, and the displayed text
// is throttled independently to RateDisplayEvery.
func (p *Push) observeRate(now time.Time) {
	p.samplesMu.Lock()
	if p.sampleAt.IsZero() || now.Sub(p.sampleAt) >= RateSampleEvery {
		p.sampleAt = now
		p.samples = append(p.samples, rateSample{at: now, bytes: p.live.Load()})
		cutoff := now.Add(-RateWindow)
		drop := 0
		for drop < len(p.samples) && p.samples[drop].at.Before(cutoff) {
			drop++
		}
		if drop > 0 {
			p.samples = append(p.samples[:0], p.samples[drop:]...)
		}
	}
	if !displayDue(p.displayAt, now, RateDisplayEvery) {
		p.samplesMu.Unlock()
		return
	}
	p.displayAt = now
	r := computeRateETA(p.samples, now, RateWindow, p.Total-p.live.Load())
	p.samplesMu.Unlock()
	p.speedBits.Store(math.Float64bits(r.bytesPerSecond))
	p.etaBits.Store(int64(r.etaSeconds))
}

// speed returns the current displayed smoothed receive rate in bytes/second, or
// 0 when there is nothing to report.
func (p *Push) speed() float64 { return math.Float64frombits(p.speedBits.Load()) }

// eta returns the current displayed ETA in whole seconds, or 0 when unknown.
func (p *Push) eta() int { return int(p.etaBits.Load()) }

// observeFileDone records a completed file for the rolling files-per-second
// rate, pruning timestamps older than the smoothing window. It is called as
// each file lands (whole-file Receive or per-file Complete).
func (p *Push) observeFileDone(now time.Time) {
	p.samplesMu.Lock()
	p.fileDone = append(p.fileDone, now)
	cutoff := now.Add(-RateWindow)
	drop := 0
	for drop < len(p.fileDone) && p.fileDone[drop].Before(cutoff) {
		drop++
	}
	if drop > 0 {
		p.fileDone = append(p.fileDone[:0], p.fileDone[drop:]...)
	}
	p.samplesMu.Unlock()
}

// fileRate returns the rolling files-per-second rate over the trailing window,
// or 0 when no file has completed recently.
func (p *Push) fileRate(now time.Time) float64 {
	p.samplesMu.Lock()
	defer p.samplesMu.Unlock()
	return RollingCountPerSecond(p.fileDone, now, RateWindow)
}

// ErrCancelled is returned to the sender once the receiving person has
// cancelled an accepted push.
var ErrCancelled = errors.New("cancelled by the receiver")

// ErrFilesBusy is returned when an offer names one or more files whose .lanpart
// is already owned by another live push. Two live pushes must never share a part
// file: they would overwrite and delete each other's bytes (one push's finalize
// renames the shared part away, the other's complete then fails with ENOENT). A
// re-offer of a push that already died is allowed, so resume still works.
var ErrFilesBusy = errors.New("these files are already being received")

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
	if n > 0 {
		if c.st != nil {
			c.st.live.Add(int64(n))
		}
		c.p.live.Add(int64(n))
	}
	if n > 0 && c.m != nil {
		c.p.touch(c.m.now())
		c.p.observeRate(c.m.now())
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
	SpeedMBps  float64   `json:"speed_mbps"`
	ETASeconds int       `json:"eta_seconds"`
	FilesTotal int       `json:"files_total"`
	FilesDone  int       `json:"files_done"`
	Current    string    `json:"current,omitempty"`
	StartedAt  time.Time `json:"started_at"`
	// Finishing is true once every byte of the push has arrived but the push
	// has not yet been finalized: the .lanpart is still being hashed and
	// renamed, or the whole push is waiting for its final Finish. The UI shows
	// "Finishing…" for this 100%-received-but-still-listed window instead of an
	// unexplained full bar.
	Finishing bool `json:"finishing"`
}

// finishing reports whether every byte of the push has arrived but the push
// has not yet finished. Once the last byte of the last file is in, the row
// still exists while each .lanpart is hashed and renamed (the per-file
// Complete) and until the sender's final Finish removes the push. During that
// window the byte total reads 100%, so the UI needs an explicit "Finishing…"
// state rather than an unexplained full bar. A push with a body still arriving
// is not finishing. Caller holds m.mu, which guards the per-file Done counters;
// the live counters and completeDone are atomic.
func (p *Push) finishing() bool {
	if p.Total <= 0 {
		return false
	}
	var received int64
	for _, f := range p.Files {
		d := f.Done
		if l := f.live.Load(); l > d {
			d = l
		}
		if d > f.Size {
			d = f.Size
		}
		if d < f.Size {
			return false // a body is still arriving
		}
		received += d
	}
	return received >= p.Total
}

// Incoming lists the pushes being received, oldest first.
func (m *Manager) Incoming() []IncomingView {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]IncomingView, 0, len(m.pushes))
	now := m.now()
	for _, p := range m.pushes {
		v := IncomingView{ID: p.ID, PeerFP: p.PeerFP, Mode: p.Mode, Total: p.Total, SpeedMBps: p.speed() / 1e6, FilesTotal: len(p.Files), StartedAt: p.CreatedAt, Finishing: p.finishing()}
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
		// ETA is the conservative of the byte-based and file-based estimates: a
		// many-small-files push is latency-bound, so the byte rate alone can make
		// the ETA swing between hours; the rolling files-per-second rate keeps it
		// honest and MaxETA takes whichever finishes later.
		v.ETASeconds = MaxETA(p.eta(), fileETA(p, v.FilesTotal-v.FilesDone, now))
		out = append(out, v)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].StartedAt.Before(out[j].StartedAt) })
	return out
}

// fileETA is remaining files divided by the push's rolling files-per-second
// rate, or 0 when either is unknown. It is a free function so the combination
// stays a single testable step (see MaxETA).
func fileETA(p *Push, remainingFiles int, now time.Time) int {
	if remainingFiles <= 0 {
		return 0
	}
	rate := p.fileRate(now)
	if rate <= 0 {
		return 0
	}
	return int(float64(remainingFiles) / rate)
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
// are removed. The sender's next request fails with ErrCancelled. It is the
// local UI path (the receiving person stopping a push).
func (m *Manager) Cancel(id string) bool { return m.cancelOwned(id, "", "") }

// CancelBy stops an accepted push only when it belongs to peerFP: a sender may
// stop its own push but never another peer's. It reports whether a live push
// was cancelled. An unknown id, or one owned by another peer, is a harmless
// no-op so the caller can answer 200 (idempotent, safe to retry). reason is
// carried to the Cancelled history entry.
func (m *Manager) CancelBy(id, peerFP, reason string) bool {
	return m.cancelOwned(id, peerFP, reason)
}

func (m *Manager) cancelOwned(id, peerFP, reason string) bool {
	var received []ReceivedFile
	var started time.Time
	var owner string
	m.mu.Lock()
	p, ok := m.pushes[id]
	if ok && peerFP != "" && p.PeerFP != peerFP {
		// Not the owner: leave the push untouched.
		m.mu.Unlock()
		return false
	}
	if ok {
		owner, started = p.PeerFP, p.CreatedAt
		for _, f := range p.Files {
			if f.Size > 0 && f.Done >= f.Size {
				name := filepath.Base(f.Final)
				if f.Final == "" {
					name = filepath.Base(f.RelPath)
				}
				received = append(received, ReceivedFile{Rel: f.RelPath, Name: name, Size: f.Size})
			}
		}
		sort.Slice(received, func(i, j int) bool { return received[i].Rel < received[j].Rel })
		delete(m.pushes, id)
		p.cancelled.Store(true)
		m.releasePartsLocked(p)
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
	if m.onCancel != nil {
		m.onCancel(owner, received, started, reason)
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
	onCancel  func(peerFP string, files []ReceivedFile, started time.Time, reason string)

	// Tunables; overridable before or during use. now is injectable for tests.
	now           func() time.Time
	progressEvery time.Duration
	stallTimeout  time.Duration
	idleTimeout   time.Duration
	sweepInterval time.Duration

	mu         sync.Mutex
	lastNotify time.Time
	pushes     map[string]*Push
	// partOwners maps a .lanpart path to the ID of the live push that owns it,
	// so two live pushes can never write one part file. Guarded by mu. A push
	// claims its parts atomically when it registers and releases them when it
	// finishes, is cancelled, fails or is reaped.
	partOwners map[string]string
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

// Option customizes a Manager at construction time. Options run before the
// background reaper starts, so a timeout set here is in effect from the very
// first sweep rather than only after the reaper has already armed its timer.
type Option func(*Manager)

// WithTimeouts sets the in-flight stall timeout and the no-body idle timeout
// before the reaper starts, deriving the sweep interval from the shorter of the
// two. A non-positive value leaves that timeout at its default. Intended for
// tests that need a deterministic reap; production uses the package defaults.
func WithTimeouts(stall, idle time.Duration) Option {
	return func(m *Manager) {
		if stall > 0 {
			m.stallTimeout = stall
		}
		if idle > 0 {
			m.idleTimeout = idle
		}
		m.refreshSweepLocked()
	}
}

func New(dir string, onChange func(), opts ...Option) *Manager {
	if onChange == nil {
		onChange = func() {}
	}
	m := &Manager{
		dir: dir, onChange: onChange, pushes: map[string]*Push{}, partOwners: map[string]string{}, gone: map[string]struct{}{},
		now: time.Now, progressEvery: defaultProgressThrottle,
		stallTimeout: defaultStallTimeout, idleTimeout: defaultIdleTimeout,
		sweepInterval: defaultSweepInterval,
		done:          make(chan struct{}),
	}
	for _, opt := range opts {
		if opt != nil {
			opt(m)
		}
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

// SetOnCancel registers a callback for a push that is stopped, with the files
// that had already landed and the reason ("" for a local cancel, "Cancelled by
// the sender" for a sender-requested one), so a Cancelled history entry is
// recorded.
func (m *Manager) SetOnCancel(fn func(peerFP string, files []ReceivedFile, started time.Time, reason string)) {
	m.onCancel = fn
}

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
			m.releasePartsLocked(p)
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

// releasePartsLocked drops every .lanpart ownership this push still holds, so a
// later offer naming the same file may claim it (resume). It only clears entries
// that still point at p, so it can never release a newer push's claim. Caller
// holds m.mu.
func (m *Manager) releasePartsLocked(p *Push) {
	for _, st := range p.Files {
		m.releasePartLocked(st.Part, p.ID)
	}
}

// releasePartLocked drops ownership of a single .lanpart once it has been
// renamed into place, so the part file name is free for another push even while
// this push is still live awaiting its final Finish. It only clears an entry
// still owned by id. Caller holds m.mu.
func (m *Manager) releasePartLocked(part, id string) {
	if owner, ok := m.partOwners[part]; ok && owner == id {
		delete(m.partOwners, part)
	}
}

// Fail ends a push as Failed with the given reason: it is removed from the
// incoming list (so a row cannot sit "Receiving" forever) and onFail is fired
// with the reason. The .lanpart files are kept so a re-offer can resume. It is
// safe to call for an unknown push.
func (m *Manager) Fail(id, reason string) bool { return m.failPush(id, reason) }

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
		m.releasePartsLocked(p)
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

// windowsNames reports whether DOS reserved device names must be remapped. It is
// a var so tests can exercise the Windows rule on any host.
var windowsNames = runtime.GOOS == "windows"

// isReservedName reports whether base is a DOS device name (CON, PRN, AUX, NUL,
// COM1–COM9, LPT1–LPT9), ignoring case and any extension.
func isReservedName(name string) bool {
	base := name
	if i := strings.IndexByte(base, '.'); i >= 0 {
		base = base[:i]
	}
	base = strings.ToUpper(base)
	switch base {
	case "CON", "PRN", "AUX", "NUL":
		return true
	}
	if len(base) == 4 && (base[:3] == "COM" || base[:3] == "LPT") {
		return base[3] >= '1' && base[3] <= '9'
	}
	return false
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
		// On Windows a device name is reserved with or without an extension and
		// with trailing dots/spaces stripped; prefix an underscore to make it a
		// normal file name.
		if windowsNames && isReservedName(s) {
			s = "_" + s
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
		p.live.Add(st.Done)
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
	// Refuse if any part file this offer names is already owned by another live
	// push. The check and the claim happen under one lock, so two racing offers
	// for the same file cannot both win: whichever registers first owns the part
	// and the other gets ErrFilesBusy. A dead/cancelled owner has already
	// released its parts, so a resume offer is not refused.
	for _, st := range p.Files {
		if owner, ok := m.partOwners[st.Part]; ok && owner != p.ID {
			if op, live := m.pushes[owner]; live && !op.cancelled.Load() && !op.dead.Load() {
				m.mu.Unlock()
				return nil, ErrFilesBusy
			}
		}
	}
	if m.partOwners == nil {
		m.partOwners = map[string]string{}
	}
	for _, st := range p.Files {
		m.partOwners[st.Part] = p.ID
	}
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
	if old := st.live.Swap(offset); old != offset {
		p.live.Add(offset - old)
	}
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
//
// The returned already flag reports a replayed request for a file that was
// already placed: the body is drained and the original result is returned, so a
// duplicate whole-file send is an idempotent success rather than a second place.
func (m *Manager) Receive(id, peerFP, rel, wantSHA string, r io.Reader) (*FileState, bool, error) {
	p, ok := m.Get(id, peerFP)
	if !ok {
		return nil, false, errors.New("no such push")
	}
	st, ok := p.Files[rel]
	if !ok {
		return nil, false, errors.New("no such file in push")
	}
	st.completeMu.Lock()
	defer st.completeMu.Unlock()
	if st.completeDone.Load() {
		// Drain the retried body so the connection stays healthy, then report
		// the same result without touching the placed file.
		_, _ = io.Copy(io.Discard, r)
		return st, true, nil
	}
	if err := os.MkdirAll(filepath.Dir(st.Part), 0o700); err != nil {
		return nil, false, err
	}
	if old := st.live.Swap(0); old != 0 {
		p.live.Add(-old)
	}
	f, err := os.OpenFile(st.Part, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, false, err
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
	fail := func(e error) (*FileState, bool, error) {
		_ = os.Remove(st.Part)
		return nil, false, e
	}
	if err != nil {
		if p.cancelled.Load() {
			// An explicit receiver Cancel() removes the partial.
			return fail(ErrCancelled)
		}
		// A read/write error means the connection died; drop the push now but
		// keep the .lanpart so a re-offer can resume from what already arrived.
		m.failPush(id, err.Error())
		return nil, false, err
	}
	if n != st.Size {
		err := fmt.Errorf("size mismatch: have %d, expected %d", n, st.Size)
		m.failPush(id, err.Error())
		return fail(err)
	}
	if !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), wantSHA) {
		err := errors.New("checksum mismatch")
		m.failPush(id, err.Error())
		return fail(err)
	}
	final := uniqueName(st.Final)
	if err := os.MkdirAll(filepath.Dir(final), 0o700); err != nil {
		m.failPush(id, err.Error())
		return fail(err)
	}
	if err := os.Rename(st.Part, final); err != nil {
		m.failPush(id, err.Error())
		return fail(err)
	}
	if !st.MTime.IsZero() {
		_ = os.Chtimes(final, st.MTime, st.MTime)
	}
	m.mu.Lock()
	st.Final, st.Done = final, st.Size
	st.completeDone.Store(true)
	m.releasePartLocked(st.Part, p.ID)
	m.mu.Unlock()
	p.observeFileDone(m.now())
	m.onChange()
	return st, false, nil
}

// Complete verifies the whole-file SHA-256 and atomically renames the part in
// place, choosing a non-colliding name. It sets the remote modification time.
//
// The returned already flag reports a replayed complete for a file that was
// already verified and placed: the same result is returned and nothing is
// hashed or renamed again, so a duplicate final-complete cannot re-place the
// file, re-count it or fail.
func (m *Manager) Complete(id, peerFP, rel, wantSHA string) (*FileState, bool, error) {
	p, ok := m.Get(id, peerFP)
	if !ok {
		return nil, false, errors.New("no such push")
	}
	st, ok := p.Files[rel]
	if !ok {
		return nil, false, errors.New("no such file in push")
	}
	st.completeMu.Lock()
	defer st.completeMu.Unlock()
	if st.completeDone.Load() {
		return st, true, nil
	}
	gotSHA, gotSize, err := hashFile(st.Part)
	if err != nil {
		// A missing part (or an unreadable one) means finalize can never
		// succeed. End the push as Failed with the reason instead of leaving a
		// row stuck "Receiving". The part is not touched here, so a failure can
		// never delete a partial another push might still need.
		m.failPush(id, err.Error())
		return nil, false, err
	}
	if gotSize != st.Size {
		err := fmt.Errorf("size mismatch: have %d, expected %d", gotSize, st.Size)
		m.failPush(id, err.Error())
		return nil, false, err
	}
	if wantSHA != "" && !strings.EqualFold(gotSHA, wantSHA) {
		err := errors.New("checksum mismatch")
		m.failPush(id, err.Error())
		return nil, false, err
	}
	final := uniqueName(st.Final)
	if err := os.MkdirAll(filepath.Dir(final), 0o700); err != nil {
		m.failPush(id, err.Error())
		return nil, false, err
	}
	if err := os.Rename(st.Part, final); err != nil {
		m.failPush(id, err.Error())
		return nil, false, err
	}
	if !st.MTime.IsZero() {
		_ = os.Chtimes(final, st.MTime, st.MTime)
	}
	st.Final = final
	st.completeDone.Store(true)
	m.mu.Lock()
	m.releasePartLocked(st.Part, p.ID)
	m.mu.Unlock()
	p.observeFileDone(m.now())
	m.onChange()
	return st, false, nil
}

// Finish removes a completed push and reports the files that landed.
func (m *Manager) Finish(id, peerFP string) bool {
	var received []ReceivedFile
	var done *Push
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
		m.releasePartsLocked(p)
		done = p
	}
	m.mu.Unlock()
	if ok {
		if done != nil {
			m.finishLog(done, received, m.now())
		}
		m.onChange()
		if m.onDone != nil {
			m.onDone(peerFP, received)
		}
	}
	return ok
}

// finishLog records a completed incoming push in the shared transfer log with
// the average throughput over its whole life, so a finished receive leaves a
// line reporting how fast it actually went.
func (m *Manager) finishLog(p *Push, files []ReceivedFile, now time.Time) {
	if m.xlog == nil {
		return
	}
	var total int64
	for _, f := range files {
		total += f.Size
	}
	elapsed := now.Sub(p.CreatedAt)
	var bps int64
	if elapsed > 0 && total > 0 {
		bps = int64(float64(total) / elapsed.Seconds())
	}
	m.xlog.Record(nil, xferlog.Entry{
		Area: xferlog.AreaPushing, Direction: xferlog.DirectionReceive, Step: xferlog.StepComplete,
		Level: xferlog.LevelInfo, Outcome: "complete", FP: identity.ShortID(p.PeerFP),
		Session: p.ID, Bytes: total, Elapsed: elapsed, SpeedBps: bps,
	})
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
