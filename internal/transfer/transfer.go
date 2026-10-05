// Package transfer downloads files from peers with resume support
// (.lanpart + .lanstate), a small worker pool, retries with back-off, and
// progress/speed/ETA metrics. See spec §8.
package transfer

import (
	"bytes"
	"context"
	"crypto/rand"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"hash"
	"io"
	"log/slog"
	"net/http"
	"os"
	"path/filepath"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
	"unicode"

	"lanyard/internal/config"
	"lanyard/internal/inbox"
	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
)

// Job states (spec §8.3).
const (
	StateQueued       = "Queued"
	StateConnecting   = "Connecting"
	StateTransferring = "Transferring"
	StateVerifying    = "Verifying"
	StatePaused       = "Paused"
	StateWaiting      = "Waiting for peer"
	StateDone         = "Done"
	StateFailed       = "Failed"
)

// Per-file states (spec §8.2 folder resume).
const (
	FilePending   = "pending"
	FilePartial   = "partial"
	FileVerifying = "verifying"
	FileDone      = "done"
)

const (
	flushBytes         = 4 << 20 // flush the sidecar every ~4 MB
	flushEvery         = time.Second
	persistEvery       = 3 * time.Second
	maxConcurrentFiles = 4 // total in-flight large-file transfers across all jobs (spec §8.4)
	// Small files are latency-bound, not bandwidth-bound, so many may be in
	// flight at once over the one multiplexed connection (spec §8.1).
	smallFile        = 512 << 10
	maxSmallFiles    = 16
	maxJobGoroutines = 32
	// syncMin is the size above which a finished file is fsynced before it is
	// renamed into place. A crash can only lose a small file's tail, and such a
	// file then fails the size check on resume and is fetched again.
	syncMin = 1 << 20
	// spaceMargin is kept free beyond the bytes a download needs.
	spaceMargin = 64 << 20
	// viewWindow caps how many per-file rows a job sends to the UI.
	viewWindow         = 200
	historyKeep        = 20
	maxRestartsPerFile = 3
)

var (
	errChecksum = errors.New("checksum mismatch")
	errRestart  = errors.New("source changed; restarting file")
	errWaiting  = errors.New("peer unreachable")
)

// fatalErr marks errors that must not be retried (local disk problems etc.).
type fatalErr struct{ err error }

func (e fatalErr) Error() string { return e.err.Error() }
func (e fatalErr) Unwrap() error { return e.err }

func fatal(err error) error {
	if err == nil {
		return nil
	}
	return fatalErr{err}
}

// FileJob is one file within a job.
type FileJob struct {
	Rel   string `json:"rel"`   // remote path relative to the share root
	Local string `json:"local"` // sanitized local relative path under the destination
	// Target is the relative path the file is actually saved to. It equals
	// Local unless a different file already lived there, in which case it is
	// "name (1).ext". Chosen once and persisted so a resume uses the same name.
	Target string `json:"target,omitempty"`
	Size   int64  `json:"size"`
	ETag   string `json:"etag"`
	MTime  int64  `json:"mtime,omitempty"`  // remote modification time, unix nanoseconds
	SHA256 string `json:"sha256,omitempty"` // digest verified for this download (reported for one-time shares)
	Done   int64  `json:"done"`
	State  string `json:"state"`
}

// localRel is the destination-relative path this file is saved under.
func (f *FileJob) localRel() string {
	if f.Target != "" {
		return f.Target
	}
	return f.Local
}

// Job is one download (or later, push).
type Job struct {
	ID         string     `json:"id"`
	Direction  string     `json:"direction"`
	PeerID     string     `json:"peer_id"`
	PeerName   string     `json:"peer_name"`
	Host       string     `json:"host"`
	Port       int        `json:"port"`
	ShareID    string     `json:"share_id"`
	ShareLabel string     `json:"share_label"`
	Root       string     `json:"root"`
	Dest       string     `json:"dest"`
	Files      []*FileJob `json:"files"`
	Total      int64      `json:"total"`
	Done       int64      `json:"done"`
	State      string     `json:"state"`
	Error      string     `json:"error"`
	Note       string     `json:"note,omitempty"` // transient status line ("retrying in 8 s")
	StartedAt  time.Time  `json:"started_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
	FinishedAt time.Time  `json:"finished_at,omitempty"`
	// OneTime is set when the share is a one-time share: a finished, verified
	// download is reported to the sender so it can retire the share.
	OneTime           bool `json:"one_time,omitempty"`
	CompletionPending bool `json:"completion_pending,omitempty"`
	reporting         bool `json:"-"`

	mu           sync.Mutex         `json:"-"`
	ctx          context.Context    `json:"-"`
	cancel       context.CancelFunc `json:"-"`
	speed        float64            `json:"-"` // bytes/sec, EMA
	lastBytes    int64              `json:"-"`
	lastAt       time.Time          `json:"-"`
	lastProgress time.Time          `json:"-"`
	retryAt      time.Time          `json:"-"`
	attempt      int                `json:"-"`
	running      bool               `json:"-"`
	wake         chan struct{}      `json:"-"` // nudges a retrying worker (peer reappeared)
}

// View is the JSON shape handed to the local UI.
type View struct {
	ID         string     `json:"id"`
	Direction  string     `json:"direction"`
	PeerID     string     `json:"peer_id"`
	PeerName   string     `json:"peer_name"`
	ShareID    string     `json:"share_id"`
	ShareLabel string     `json:"share_label"`
	Root       string     `json:"root"`
	Dest       string     `json:"dest"`
	State      string     `json:"state"`
	Error      string     `json:"error"`
	Note       string     `json:"note"`
	RetryIn    int        `json:"retry_in"`
	Attempt    int        `json:"attempt"`
	Total      int64      `json:"total"`
	Done       int64      `json:"done"`
	SpeedMBps  float64    `json:"speed_mbps"`
	ETASeconds int        `json:"eta_seconds"`
	Files      []FileView `json:"files"`
	CurrentIdx int        `json:"current_index"` // index into Files (the window)
	FilesTotal int        `json:"files_total"`
	FilesStart int        `json:"files_offset"` // position of Files[0] in the full list
	FilesDone  int        `json:"files_done"`
	StartedAt  time.Time  `json:"started_at"`
	UpdatedAt  time.Time  `json:"updated_at"`
	FinishedAt time.Time  `json:"finished_at"`
}

type FileView struct {
	Rel   string `json:"rel"`
	Local string `json:"local"`
	Size  int64  `json:"size"`
	Done  int64  `json:"done"`
	State string `json:"state"`
}

// Manager owns all jobs and their worker execution.
type Manager struct {
	onDone     func(JobInfo)
	cfg        *config.Store
	client     *peerapi.Client
	log        *slog.Logger
	onChange   func()
	workers    int
	slots      chan struct{}
	smallSlots chan struct{}
	// freeSpace reports free bytes on the volume holding a folder (0 = unknown).
	freeSpace func(dir string) int64

	// Tunables (overridden in tests).
	offlineLimit time.Duration // give up retrying after this long without progress
	backoffBase  time.Duration
	backoffMax   time.Duration

	mu            sync.Mutex
	jobs          map[string]*Job
	bandwidthMBps int // 0 = unlimited
}

func (m *Manager) bandwidth() int {
	m.mu.Lock()
	defer m.mu.Unlock()
	return m.bandwidthMBps
}

func New(cfg *config.Store, client *peerapi.Client, log *slog.Logger, workers int, onChange func()) *Manager {
	if workers < 1 {
		workers = 3
	}
	if onChange == nil {
		onChange = func() {}
	}
	return &Manager{
		cfg: cfg, client: client, log: log, workers: workers, onChange: onChange,
		slots:        make(chan struct{}, maxConcurrentFiles),
		smallSlots:   make(chan struct{}, maxSmallFiles),
		freeSpace:    inbox.FreeSpace,
		offlineLimit: 5 * time.Minute,
		backoffBase:  time.Second,
		backoffMax:   30 * time.Second,
		jobs:         map[string]*Job{},
	}
}

// Load restores jobs from config. Jobs the user paused stay paused and failed
// or finished jobs keep their state; anything that was in flight becomes
// "Waiting for peer" and resumes by itself when the peer shows up again.
func (m *Manager) Load() error {
	raw := m.cfg.Get().Transfers
	if len(raw) == 0 {
		return nil
	}
	var jobs []*Job
	if err := json.Unmarshal(raw, &jobs); err != nil {
		return fmt.Errorf("transfers: %w", err)
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, j := range jobs {
		switch j.State {
		case StateDone, StatePaused, StateFailed:
		default:
			j.State = StateWaiting
		}
		j.wake = make(chan struct{}, 1)
		m.jobs[j.ID] = j
	}
	return nil
}

func (m *Manager) persist() {
	m.mu.Lock()
	jobs := make([]*Job, 0, len(m.jobs))
	var finished []*Job
	for _, j := range m.jobs {
		if j.State == StateDone {
			finished = append(finished, j)
		} else {
			jobs = append(jobs, j)
		}
	}
	m.mu.Unlock()
	// Keep a short history of finished jobs (without their per-file detail).
	sort.Slice(finished, func(a, b int) bool { return finished[a].FinishedAt.After(finished[b].FinishedAt) })
	if len(finished) > historyKeep {
		finished = finished[:historyKeep]
	}
	jobs = append(jobs, finished...)

	var buf bytes.Buffer
	buf.WriteByte('[')
	for i, j := range jobs {
		j.mu.Lock()
		b, err := json.Marshal(j)
		j.mu.Unlock()
		if err != nil {
			continue
		}
		if i > 0 {
			buf.WriteByte(',')
		}
		buf.Write(b)
	}
	buf.WriteByte(']')
	raw := buf.Bytes()
	_ = m.cfg.Update(func(s *config.Settings) { s.Transfers = raw })
}

// CreateParams describes a download request from the UI.
type CreateParams struct {
	PeerID     string
	PeerName   string
	Host       string
	Port       int
	ShareID    string
	ShareLabel string
	Paths      []string // remote paths relative to the share root; "" = whole share
	Dest       string
}

// Create plans a job by fetching the manifest, then starts it.
func (m *Manager) Create(ctx context.Context, p CreateParams) (*View, error) {
	if p.Dest == "" {
		return nil, errors.New("a destination folder is required")
	}
	if len(p.Paths) == 0 {
		p.Paths = []string{""}
	}
	job := &Job{
		ID:         "j_" + randHex(6),
		Direction:  "download",
		PeerID:     p.PeerID,
		PeerName:   p.PeerName,
		Host:       p.Host,
		Port:       p.Port,
		ShareID:    p.ShareID,
		ShareLabel: p.ShareLabel,
		Root:       strings.Join(p.Paths, ","),
		Dest:       p.Dest,
		State:      StateQueued,
		StartedAt:  time.Now(),
		wake:       make(chan struct{}, 1),
	}
	seen := map[string]bool{}
	for _, pth := range p.Paths {
		man, err := m.client.GetManifest(ctx, p.Host, p.Port, p.PeerID, p.ShareID, pth)
		if err != nil {
			return nil, err
		}
		if man.Lifetime == "one_time" {
			job.OneTime = true
		}
		for _, f := range man.Files {
			rel := f.Path
			if rel == "" {
				// A single-file share: use the real file name, falling back to
				// the share label if an older peer did not send one.
				rel = f.Name
			}
			if rel == "" {
				rel = p.ShareLabel
			}
			local, err := SanitizeRemote(rel)
			if err != nil || local == "" {
				continue
			}
			if seen[local] {
				continue
			}
			seen[local] = true
			job.Files = append(job.Files, &FileJob{
				Rel: f.Path, Local: local, Size: f.Size, ETag: f.ETag,
				MTime: f.ModTime.UnixNano(), State: FilePending,
			})
			job.Total += f.Size
		}
	}
	if len(job.Files) == 0 {
		return nil, errors.New("nothing to download")
	}
	sort.Slice(job.Files, func(i, j int) bool { return job.Files[i].Local < job.Files[j].Local })

	m.mu.Lock()
	m.jobs[job.ID] = job
	m.mu.Unlock()
	m.persist()
	m.onChange()
	job.mu.Lock()
	job.running = true
	job.mu.Unlock()
	go m.run(job)
	return m.view(job), nil
}

// Resume restarts a paused/failed/waiting job.
func (m *Manager) Resume(id string) error {
	m.mu.Lock()
	job, ok := m.jobs[id]
	m.mu.Unlock()
	if !ok {
		return errors.New("no such job")
	}
	job.mu.Lock()
	if job.running || job.State == StateDone {
		job.mu.Unlock()
		return nil
	}
	job.running = true
	job.State = StateQueued
	job.Error = ""
	job.Note = ""
	job.mu.Unlock()
	m.onChange()
	m.start(job)
	return nil
}

// start runs the right engine for a job's direction.
func (m *Manager) start(job *Job) {
	if job.Direction == "push" {
		go m.runPush(job)
		return
	}
	go m.run(job)
}

// Pause cancels the running transfer; partial files are kept.
func (m *Manager) Pause(id string) error {
	m.mu.Lock()
	job, ok := m.jobs[id]
	m.mu.Unlock()
	if !ok {
		return errors.New("no such job")
	}
	job.mu.Lock()
	if job.cancel != nil {
		job.cancel()
	}
	if job.State != StateDone {
		job.State = StatePaused
		job.Note = ""
	}
	job.mu.Unlock()
	m.persist()
	m.onChange()
	return nil
}

// Cancel stops a job, optionally deleting its partial files, and forgets it.
func (m *Manager) Cancel(id string, deletePartials bool) error {
	m.mu.Lock()
	job, ok := m.jobs[id]
	if ok {
		delete(m.jobs, id)
	}
	m.mu.Unlock()
	if !ok {
		return errors.New("no such job")
	}
	job.mu.Lock()
	if job.cancel != nil {
		job.cancel()
	}
	dest := job.Dest
	files := append([]*FileJob(nil), job.Files...)
	job.mu.Unlock()
	if deletePartials {
		if job.Direction != "push" {
			for _, f := range files {
				final := filepath.Join(dest, filepath.FromSlash(f.localRel()))
				_ = os.Remove(final + ".lanpart")
				_ = os.Remove(final + ".lanstate")
			}
		}
	}
	m.persist()
	m.onChange()
	return nil
}

// CancelAll cancels every unfinished job (both directions) and forgets it.
// Partial files are kept. Used by Cancel all shares (§11.3).
func (m *Manager) CancelAll() int {
	m.mu.Lock()
	var victims []*Job
	for id, j := range m.jobs {
		j.mu.Lock()
		done := j.State == StateDone
		j.mu.Unlock()
		if !done {
			victims = append(victims, j)
			delete(m.jobs, id)
		}
	}
	m.mu.Unlock()
	for _, j := range victims {
		j.mu.Lock()
		if j.cancel != nil {
			j.cancel()
		}
		j.mu.Unlock()
	}
	if len(victims) > 0 {
		m.persist()
		m.onChange()
	}
	return len(victims)
}

// SetBandwidthLimit sets the throughput cap in MB/s (0 = unlimited).
func (m *Manager) SetBandwidthLimit(mbps int) {
	m.mu.Lock()
	m.bandwidthMBps = mbps
	m.mu.Unlock()
}

// ClearFinished forgets completed jobs (the history list).
func (m *Manager) ClearFinished() int {
	m.mu.Lock()
	n := 0
	for id, j := range m.jobs {
		j.mu.Lock()
		done := j.State == StateDone
		j.mu.Unlock()
		if done {
			delete(m.jobs, id)
			n++
		}
	}
	m.mu.Unlock()
	if n > 0 {
		m.persist()
		m.onChange()
	}
	return n
}

// PeerAvailable is called when discovery sees a verified peer. It refreshes
// the address of that peer's jobs, nudges retrying workers to try immediately,
// and restarts jobs that were waiting for the peer (spec §8.2).
func (m *Manager) PeerAvailable(deviceID, host string, port int) {
	m.mu.Lock()
	var jobs []*Job
	for _, j := range m.jobs {
		if j.PeerID == deviceID {
			jobs = append(jobs, j)
		}
	}
	m.mu.Unlock()
	for _, j := range jobs {
		j.mu.Lock()
		if j.State == StateDone {
			pending := j.CompletionPending
			if host != "" {
				j.Host, j.Port = host, port
			}
			j.mu.Unlock()
			if pending {
				go m.reportCompletion(j)
			}
			continue
		}
		if host != "" {
			j.Host, j.Port = host, port
		}
		running, waiting := j.running, j.State == StateWaiting
		j.mu.Unlock()
		switch {
		case running:
			select {
			case j.wake <- struct{}{}:
			default:
			}
		case waiting:
			_ = m.Resume(j.ID)
		}
	}
}

// List returns a snapshot of every job, newest first.
func (m *Manager) List() []View {
	m.mu.Lock()
	jobs := make([]*Job, 0, len(m.jobs))
	for _, j := range m.jobs {
		jobs = append(jobs, j)
	}
	m.mu.Unlock()
	sort.Slice(jobs, func(i, j int) bool { return jobs[i].StartedAt.After(jobs[j].StartedAt) })
	out := make([]View, 0, len(jobs))
	for _, j := range jobs {
		out = append(out, *m.view(j))
	}
	return out
}

// Get returns one job's view.
func (m *Manager) Get(id string) (*View, bool) {
	m.mu.Lock()
	j, ok := m.jobs[id]
	m.mu.Unlock()
	if !ok {
		return nil, false
	}
	return m.view(j), true
}

func (m *Manager) view(j *Job) *View {
	j.mu.Lock()
	defer j.mu.Unlock()
	v := &View{
		ID: j.ID, Direction: j.Direction, PeerID: j.PeerID, PeerName: j.PeerName,
		ShareID: j.ShareID, ShareLabel: j.ShareLabel, Root: j.Root, Dest: j.Dest,
		State: j.State, Error: j.Error, Note: j.Note, Attempt: j.attempt,
		Total: j.Total, Done: j.Done,
		SpeedMBps: j.speed / 1e6, StartedAt: j.StartedAt, UpdatedAt: j.UpdatedAt, FinishedAt: j.FinishedAt,
	}
	if !j.retryAt.IsZero() && time.Now().Before(j.retryAt) {
		v.RetryIn = int(time.Until(j.retryAt).Seconds()) + 1
	}
	if j.State == StateTransferring {
		v.SpeedMBps = j.speed / 1e6
	} else {
		v.SpeedMBps = 0
	}
	remaining := j.Total - j.Done
	if j.speed > 1 && remaining > 0 && j.State == StateTransferring {
		v.ETASeconds = int(float64(remaining) / j.speed)
	}
	firstOpen, firstPartial := -1, -1
	downloading, verifying := 0, 0
	for i, f := range j.Files {
		switch f.State {
		case FileDone:
			v.FilesDone++
			continue
		case FilePartial:
			downloading++
			if firstPartial < 0 {
				firstPartial = i
			}
		case FileVerifying:
			verifying++
			if firstPartial < 0 {
				firstPartial = i
			}
		}
		if firstOpen < 0 {
			firstOpen = i
		}
	}
	cur := 0
	switch {
	case firstPartial >= 0:
		cur = firstPartial
	case firstOpen >= 0:
		cur = firstOpen
	}
	// Send everything for small jobs; for big ones only a window starting at the
	// first unfinished file, so the UI update stays small however long the job.
	start, end := 0, len(j.Files)
	if end > viewWindow {
		if firstOpen >= 0 {
			start = firstOpen
		}
		end = start + viewWindow
		if end > len(j.Files) {
			end = len(j.Files)
		}
	}
	v.Files = make([]FileView, 0, end-start)
	for _, f := range j.Files[start:end] {
		v.Files = append(v.Files, FileView{Rel: f.Rel, Local: f.localRel(), Size: f.Size, Done: f.Done, State: f.State})
	}
	v.CurrentIdx, v.FilesStart, v.FilesTotal = cur-start, start, len(j.Files)
	if j.State == StateTransferring && verifying > 0 && downloading == 0 {
		v.State = StateVerifying
	}
	return v
}

// setFileDone sets a file's progress and keeps the job total consistent.
// Caller holds job.mu.
func setFileDone(job *Job, f *FileJob, n int64) {
	job.Done += n - f.Done
	f.Done = n
}

// run transfers every file, using a small worker pool.
func (m *Manager) run(job *Job) {
	job.mu.Lock()
	job.ctx, job.cancel = context.WithCancel(context.Background())
	job.running = true
	job.State = StateConnecting
	job.Error = ""
	job.Note = ""
	job.attempt = 0
	job.retryAt = time.Time{}
	job.lastAt = time.Now()
	job.lastBytes = job.Done
	job.lastProgress = time.Now()
	job.speed = 0
	ctx := job.ctx
	job.mu.Unlock()
	m.onChange()

	if err := os.MkdirAll(job.Dest, 0o755); err != nil {
		m.fail(job, "cannot create destination: "+err.Error())
		return
	}

	// Folder-level resume: trust "done" only if the file is still on disk with
	// the right size and modification time; otherwise it goes back to pending.
	job.mu.Lock()
	for _, f := range job.Files {
		if f.State != FileDone {
			continue
		}
		final := filepath.Join(job.Dest, filepath.FromSlash(f.localRel()))
		if !alreadyComplete(final, f) {
			f.State = FilePending
			setFileDone(job, f, 0)
		}
	}
	job.State = StateTransferring
	job.mu.Unlock()
	m.onChange()

	// Refuse up front when the disk cannot hold what is left, rather than
	// failing hours in. The person frees space and presses Resume.
	job.mu.Lock()
	need := job.Total - job.Done
	job.mu.Unlock()
	if free := m.freeSpace(job.Dest); free > 0 && need+spaceMargin > free {
		m.fail(job, fmt.Sprintf("Not enough free space in %s: %s are still needed and only %s are free. Free up some space and press Resume.",
			job.Dest, humanBytes(need), humanBytes(free)))
		return
	}

	// Periodically persist progress so a crash or sleep resumes at the first
	// unfinished file (spec §8.2).
	stopPersist := make(chan struct{})
	var persistWG sync.WaitGroup
	persistWG.Add(1)
	go func() {
		defer persistWG.Done()
		// Saving rewrites the whole job list, so big jobs save less often.
		every := persistEvery
		if n := len(job.Files); n > 20000 {
			every = time.Duration(n/10000) * time.Second
			if every > time.Minute {
				every = time.Minute
			}
		}
		t := time.NewTicker(every)
		defer t.Stop()
		for {
			select {
			case <-stopPersist:
				return
			case <-t.C:
				m.persist()
			}
		}
	}()

	sem := make(chan struct{}, maxJobGoroutines)
	bigSem := make(chan struct{}, m.workers) // per-job cap for large files
	var wg sync.WaitGroup
	var firstErr error
	var errMu sync.Mutex
	noteErr := func(err error) {
		errMu.Lock()
		// A genuine failure outranks "peer unreachable".
		if firstErr == nil || (errors.Is(firstErr, errWaiting) && !errors.Is(err, errWaiting)) {
			firstErr = err
		}
		errMu.Unlock()
	}
	for _, f := range job.Files {
		f := f
		job.mu.Lock()
		skip := f.State == FileDone
		job.mu.Unlock()
		if skip {
			continue
		}
		select {
		case sem <- struct{}{}:
		case <-ctx.Done():
		}
		if ctx.Err() != nil {
			break
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			slots := m.slots
			if f.Size < smallFile {
				slots = m.smallSlots
			} else {
				select {
				case bigSem <- struct{}{}:
				case <-ctx.Done():
					return
				}
				defer func() { <-bigSem }()
			}
			select {
			case slots <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-slots }()
			if err := m.transferWithRetry(ctx, job, f); err != nil && ctx.Err() == nil {
				noteErr(err)
			}
		}()
	}
	wg.Wait()
	close(stopPersist)
	persistWG.Wait()

	job.mu.Lock()
	job.running = false
	canceled := errors.Is(ctx.Err(), context.Canceled)
	if job.cancel != nil {
		job.cancel()
	}
	job.retryAt = time.Time{}
	switch {
	case canceled:
		if job.State != StatePaused {
			job.State = StatePaused
		}
		job.Note = ""
	case errors.Is(firstErr, errWaiting):
		job.State = StateWaiting
		job.Note = "Peer unreachable. Will resume automatically when it comes back, or press Resume."
	case firstErr != nil:
		job.State = StateFailed
		job.Error = firstErr.Error()
		job.Note = ""
	default:
		job.State = StateDone
		job.Done = job.Total
		job.Note = ""
		job.FinishedAt = time.Now()
		job.CompletionPending = job.OneTime
	}
	job.UpdatedAt = time.Now()
	report := job.State == StateDone && job.CompletionPending
	finished := job.State == StateDone
	job.mu.Unlock()
	m.persist()
	m.onChange()
	if report {
		m.reportCompletion(job)
	}
	if finished {
		m.fireDone(job) // after the completion report: closing a Connect session first would cut it off
	}
}

// JobInfo is what the app needs to know about a job that just finished.
type JobInfo struct {
	PeerID    string
	Host      string
	Port      int
	Direction string
}

// SetOnDone registers a callback for every job that finishes successfully
// (used to end Connect sessions after their single transfer).
func (m *Manager) SetOnDone(fn func(JobInfo)) { m.onDone = fn }

func (m *Manager) fireDone(job *Job) {
	if m.onDone == nil {
		return
	}
	job.mu.Lock()
	ji := JobInfo{PeerID: job.PeerID, Host: job.Host, Port: job.Port, Direction: job.Direction}
	job.mu.Unlock()
	m.onDone(ji)
}

// reportCompletion tells the sender that a one-time share was downloaded and
// verified, so it can retire the share (spec §6.2). Interrupted or partial jobs
// never get here. If the sender cannot be reached the report stays pending and
// is retried when the peer shows up again.
func (m *Manager) reportCompletion(job *Job) {
	job.mu.Lock()
	if job.reporting || !job.CompletionPending || job.State != StateDone {
		job.mu.Unlock()
		return
	}
	job.reporting = true
	host, port := job.Host, job.Port
	var files []peerapi.VerifiedFile
	for _, f := range job.Files {
		if f.SHA256 != "" {
			files = append(files, peerapi.VerifiedFile{Path: f.Rel, SHA256: f.SHA256})
		}
	}
	job.mu.Unlock()

	var err error
	if len(files) > 0 {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Minute)
		_, err = m.client.CompleteShare(ctx, host, port, job.PeerID, job.ShareID, files)
		cancel()
	}
	// 4xx answers are final (share already gone, digest mismatch): retrying
	// cannot help. Network trouble and 5xx keep the report pending.
	var se *peerapi.StatusError
	final := err == nil || (errors.As(err, &se) && se.Code >= 400 && se.Code < 500)
	job.mu.Lock()
	job.reporting = false
	if final {
		job.CompletionPending = false
	}
	job.mu.Unlock()
	if err != nil {
		m.log.Debug("could not report completion", "job", job.ID, "err", err, "final", final)
	}
	m.persist()
	m.onChange()
}

func (m *Manager) fail(job *Job, msg string) {
	job.mu.Lock()
	job.running = false
	job.State = StateFailed
	job.Error = msg
	job.UpdatedAt = time.Now()
	job.mu.Unlock()
	m.persist()
	m.onChange()
}

// transferWithRetry runs downloadFile with back-off for transient failures
// (spec §8.2): 1 s doubling to 30 s, reset by progress, woken early when the
// peer reappears, and handed back as errWaiting after offlineLimit.
func (m *Manager) transferWithRetry(ctx context.Context, job *Job, f *FileJob) error {
	attempt := 0
	checksumRetried := false
	restarts := 0
	for {
		job.mu.Lock()
		before := job.Done
		job.mu.Unlock()

		err := m.downloadFile(ctx, job, f)
		if err == nil {
			return nil
		}
		if ctx.Err() != nil {
			return ctx.Err()
		}
		switch {
		case errors.Is(err, errChecksum):
			if checksumRetried {
				return fmt.Errorf("%s: checksum mismatch after retry", f.Local)
			}
			checksumRetried = true
			m.resetFile(job, f)
			m.setNote(job, f.Local+": checksum mismatch, downloading it again")
			continue
		case errors.Is(err, errRestart):
			restarts++
			if restarts > maxRestartsPerFile {
				return fmt.Errorf("%s: source keeps changing", f.Local)
			}
			m.resetFile(job, f)
			m.setNote(job, f.Local+": source changed, restarting file")
			continue
		case isShareEnded(err):
			var se *peerapi.StatusError
			errors.As(err, &se)
			return fmt.Errorf("%s Files already downloaded are kept, and so are partial files.", se.Msg)
		case !retryable(err):
			return err
		}

		job.mu.Lock()
		if job.Done > before {
			attempt = 0
		}
		idle := time.Since(job.lastProgress)
		job.mu.Unlock()
		attempt++
		if idle > m.offlineLimit {
			return errWaiting
		}
		delay := m.backoff(attempt)
		job.mu.Lock()
		job.attempt = attempt
		job.retryAt = time.Now().Add(delay)
		job.Note = fmt.Sprintf("Connection problem (%s). Retrying in %d s (attempt %d).", shortErr(err), int(delay.Seconds())+1, attempt)
		job.mu.Unlock()
		m.onChange()
		m.log.Debug("transfer retry", "file", f.Local, "attempt", attempt, "delay", delay, "err", err)
		select {
		case <-ctx.Done():
			return ctx.Err()
		case <-time.After(delay):
		case <-job.wake:
		}
		job.mu.Lock()
		job.retryAt = time.Time{}
		job.mu.Unlock()
	}
}

func (m *Manager) backoff(attempt int) time.Duration {
	d := m.backoffBase
	for i := 1; i < attempt && d < m.backoffMax; i++ {
		d *= 2
	}
	if d > m.backoffMax {
		d = m.backoffMax
	}
	return d
}

func shortErr(err error) string {
	s := err.Error()
	if len(s) > 80 {
		s = s[:80] + "…"
	}
	return s
}

func (m *Manager) setNote(job *Job, note string) {
	job.mu.Lock()
	job.Note = note
	job.mu.Unlock()
	m.onChange()
}

// retryable reports whether an error is worth retrying.
func retryable(err error) bool {
	var fe fatalErr
	if errors.As(err, &fe) {
		return false
	}
	if errors.Is(err, context.Canceled) {
		return false
	}
	var se *peerapi.StatusError
	if errors.As(err, &se) {
		switch {
		case se.Code >= 500, se.Code == http.StatusRequestTimeout, se.Code == http.StatusTooManyRequests:
			return true
		}
		return false // 401/403/404/410 ...: the share is gone or not permitted
	}
	return true // network-level errors, truncated bodies, ...
}

// resetFile discards a file's partial data so it starts again from byte 0.
func (m *Manager) resetFile(job *Job, f *FileJob) {
	final := filepath.Join(job.Dest, filepath.FromSlash(f.localRel()))
	_ = os.Remove(final + ".lanpart")
	_ = os.Remove(final + ".lanstate")
	job.mu.Lock()
	setFileDone(job, f, 0)
	f.State = FilePending
	job.mu.Unlock()
}

type sidecar struct {
	ShareID        string `json:"share_id"`
	PeerID         string `json:"peer_id"`
	RemotePath     string `json:"remote_path"`
	Size           int64  `json:"size"`
	ETag           string `json:"etag"`
	BytesCommitted int64  `json:"bytes_committed"`
}

// alreadyComplete reports whether final is a finished copy of f: same size
// and, when the remote mtime is known, the same modification time.
func alreadyComplete(final string, f *FileJob) bool {
	info, err := os.Stat(final)
	if err != nil || info.IsDir() || info.Size() != f.Size {
		return false
	}
	if f.MTime == 0 {
		return true
	}
	d := info.ModTime().UnixNano() - f.MTime
	if d < 0 {
		d = -d
	}
	return d <= int64(2*time.Second) // tolerate coarse filesystem timestamps
}

func (m *Manager) downloadFile(ctx context.Context, job *Job, f *FileJob) error {
	job.mu.Lock()
	host, port := job.Host, job.Port
	job.mu.Unlock()

	final := filepath.Join(job.Dest, filepath.FromSlash(m.resolveTarget(job, f)))
	if err := os.MkdirAll(filepath.Dir(final), 0o755); err != nil {
		return fatal(err)
	}
	part := final + ".lanpart"
	statePath := final + ".lanstate"
	began := time.Now()

	if alreadyComplete(final, f) {
		job.mu.Lock()
		f.State = FileDone
		setFileDone(job, f, f.Size)
		job.mu.Unlock()
		_ = os.Remove(part)
		_ = os.Remove(statePath)
		m.onChange()
		return nil
	}

	// How much can we trust? Only what the sidecar says was flushed, and only
	// for the same source version.
	var offset int64
	sidecarExisted := false
	if sc, err := readSidecar(statePath); err == nil {
		sidecarExisted = true
		if sc.Size == f.Size && sc.ETag == f.ETag && sc.BytesCommitted > 0 {
			if info, err := os.Stat(part); err == nil && info.Size() >= sc.BytesCommitted {
				offset = sc.BytesCommitted
			}
		}
	}

	h := sha256.New()
	if offset > 0 {
		// Re-hash the committed prefix (disk speed) so the final digest covers
		// the whole file, and drop anything written past the commit point.
		job.mu.Lock()
		f.State = FileVerifying
		job.mu.Unlock()
		m.onChange()
		ok, err := hashPrefix(ctx, part, offset, h)
		if err != nil {
			if ctx.Err() != nil {
				return ctx.Err()
			}
			return fatal(err)
		}
		if !ok {
			offset = 0
			h.Reset()
		}
	}
	if offset == 0 && sidecarExisted {
		_ = os.Remove(statePath) // stale state for a file we start over
	}
	job.mu.Lock()
	setFileDone(job, f, offset)
	f.State = FilePartial
	job.mu.Unlock()

	flags := os.O_CREATE | os.O_WRONLY
	if offset == 0 {
		flags |= os.O_TRUNC // also discards any stale partial data
	}
	out, err := os.OpenFile(part, flags, 0o644)
	if err != nil {
		return fatal(err)
	}
	defer out.Close()
	if offset > 0 {
		if err := out.Truncate(offset); err != nil {
			return fatal(err)
		}
		if _, err := out.Seek(offset, io.SeekStart); err != nil {
			return fatal(err)
		}
	}

	var trailerSum string // set when a full 200 response supplies the digest
	if offset < f.Size || f.Size == 0 {
		resp, err := m.client.OpenFile(ctx, host, port, job.PeerID, job.ShareID, f.Rel, offset, f.ETag)
		if err != nil {
			var se *peerapi.StatusError
			if errors.As(err, &se) && se.Code == http.StatusRequestedRangeNotSatisfiable {
				return errRestart
			}
			return err
		}
		defer resp.Body.Close()

		if resp.StatusCode == http.StatusOK {
			if offset > 0 {
				// The server ignored the range (source changed): start over.
				m.setNote(job, f.Local+": source changed, restarting file")
				offset = 0
				h.Reset()
				if err := out.Truncate(0); err != nil {
					return fatal(err)
				}
				if _, err := out.Seek(0, io.SeekStart); err != nil {
					return fatal(err)
				}
				job.mu.Lock()
				setFileDone(job, f, 0)
				job.mu.Unlock()
			}
			// Adopt the size/validator the server is actually sending.
			job.mu.Lock()
			if cl := resp.ContentLength; cl >= 0 && cl != f.Size {
				job.Total += cl - f.Size
				f.Size = cl
			}
			if et := strings.Trim(resp.Header.Get("ETag"), `"`); et != "" {
				f.ETag = et
			}
			job.mu.Unlock()
		} else if start, ok := contentRangeStart(resp.Header.Get("Content-Range")); !ok || start != offset {
			return errRestart
		}

		var body io.Reader = resp.Body
		if mbps := m.bandwidth(); mbps > 0 {
			body = &throttleReader{r: resp.Body, mbps: mbps, start: time.Now()}
		}
		written, lastErr := m.copyBody(ctx, job, f, out, body, h, statePath, offset)
		if lastErr != nil {
			return lastErr
		}
		if want := resp.ContentLength; want >= 0 && written != want {
			m.writeSidecar(statePath, job, f, offset+written)
			return io.ErrUnexpectedEOF
		}
		if offset+written != f.Size {
			return errRestart
		}
		if resp.StatusCode == http.StatusOK {
			trailerSum = resp.Trailer.Get("X-Content-SHA256")
		}
	}

	// Verify the whole-file digest, then commit.
	job.mu.Lock()
	f.State = FileVerifying
	job.mu.Unlock()
	m.onChange()
	if f.Size >= syncMin {
		if err := out.Sync(); err != nil {
			return fatal(err)
		}
	}
	// The "all bytes are here" marker only pays off when verification needs a
	// second request that may fail (resumed files) or the file is big enough
	// that re-downloading it hurts. A small file verified by its trailer skips it.
	wroteMarker := trailerSum == "" || f.Size >= flushBytes
	if wroteMarker {
		m.writeSidecar(statePath, job, f, f.Size)
	}
	got := hex.EncodeToString(h.Sum(nil))
	want := trailerSum
	if want == "" {
		fh, err := m.client.FileHash(ctx, host, port, job.PeerID, job.ShareID, f.Rel)
		if err != nil {
			var se *peerapi.StatusError
			if errors.As(err, &se) && se.Code == http.StatusNotFound {
				return errRestart
			}
			return err
		}
		if fh.ETag != f.ETag || fh.Size != f.Size {
			return errRestart
		}
		want = fh.SHA256
	}
	if !strings.EqualFold(got, want) {
		return errChecksum
	}
	if job.OneTime {
		job.mu.Lock()
		f.SHA256 = got
		job.mu.Unlock()
	}
	if err := out.Close(); err != nil {
		return fatal(err)
	}
	if err := os.Rename(part, final); err != nil {
		return fatal(err)
	}
	if f.MTime != 0 {
		mt := time.Unix(0, f.MTime)
		_ = os.Chtimes(final, mt, mt) // spec §8.1: preserve modification time
	}
	// A sidecar can exist if we wrote the marker, resumed from one, or a slow
	// transfer committed progress (every flushEvery); otherwise skip the syscall.
	if wroteMarker || sidecarExisted || time.Since(began) >= flushEvery {
		_ = os.Remove(statePath)
	}
	job.mu.Lock()
	f.State = FileDone
	setFileDone(job, f, f.Size)
	job.mu.Unlock()
	m.onChange()
	return nil
}

// copyBody streams the response into the part file, hashing as it goes and
// committing the sidecar every flushBytes/flushEvery. It returns the number of
// bytes written in this call.
// throttleReader paces reads to at most mbps MB/s (0 = unlimited).
type throttleReader struct {
	r     io.Reader
	mbps  int
	start time.Time
	total int64
}

func (t *throttleReader) Read(p []byte) (int, error) {
	n, err := t.r.Read(p)
	if n > 0 && t.mbps > 0 {
		t.total += int64(n)
		want := time.Duration(float64(t.total) / (float64(t.mbps) * 1e6) * float64(time.Second))
		if d := want - time.Since(t.start); d > 0 {
			time.Sleep(d)
		}
	}
	return n, err
}

func (m *Manager) copyBody(ctx context.Context, job *Job, f *FileJob, out *os.File, body io.Reader, h hash.Hash, statePath string, offset int64) (int64, error) {
	var written int64
	buf := make([]byte, 256*1024)
	lastFlush := time.Now()
	lastCommitted := int64(0)
	commit := func() {
		_ = out.Sync() // only then is the sidecar's promise true
		m.writeSidecar(statePath, job, f, offset+written)
		lastCommitted = written
		lastFlush = time.Now()
	}
	for {
		if err := ctx.Err(); err != nil {
			commit()
			return written, err
		}
		n, rerr := body.Read(buf)
		if n > 0 {
			if _, werr := out.Write(buf[:n]); werr != nil {
				return written, fatal(werr)
			}
			h.Write(buf[:n])
			written += int64(n)
			job.mu.Lock()
			setFileDone(job, f, offset+written)
			job.lastProgress = time.Now()
			m.bumpSpeed(job)
			job.mu.Unlock()
			if written-lastCommitted >= flushBytes || time.Since(lastFlush) >= flushEvery {
				commit()
				m.onChange()
			}
		}
		if rerr == io.EOF {
			return written, nil
		}
		if rerr != nil {
			commit()
			return written, rerr
		}
	}
}

// hashPrefix feeds the first n bytes of path into h. ok is false when the file
// is shorter than n (the partial file cannot be trusted).
func hashPrefix(ctx context.Context, path string, n int64, h hash.Hash) (bool, error) {
	in, err := os.Open(path)
	if err != nil {
		if os.IsNotExist(err) {
			return false, nil
		}
		return false, err
	}
	defer in.Close()
	buf := make([]byte, 1<<20)
	var left = n
	for left > 0 {
		if err := ctx.Err(); err != nil {
			return false, err
		}
		chunk := buf
		if int64(len(chunk)) > left {
			chunk = chunk[:left]
		}
		r, err := io.ReadFull(in, chunk)
		h.Write(chunk[:r])
		left -= int64(r)
		if err != nil {
			if err == io.ErrUnexpectedEOF || err == io.EOF {
				return false, nil
			}
			return false, err
		}
	}
	return true, nil
}

// contentRangeStart parses "bytes START-END/TOTAL".
func contentRangeStart(h string) (int64, bool) {
	h = strings.TrimPrefix(h, "bytes ")
	dash := strings.IndexByte(h, '-')
	if dash <= 0 {
		return 0, false
	}
	v, err := strconv.ParseInt(h[:dash], 10, 64)
	return v, err == nil
}

// bumpSpeed updates the exponentially smoothed speed. Caller holds job.mu.
func (m *Manager) bumpSpeed(job *Job) {
	now := time.Now()
	dt := now.Sub(job.lastAt).Seconds()
	if dt < 0.25 {
		return
	}
	inst := float64(job.Done-job.lastBytes) / dt
	if inst < 0 {
		inst = 0
	}
	if job.speed == 0 {
		job.speed = inst
	} else {
		const alpha = 0.3
		job.speed = (1-alpha)*job.speed + alpha*inst
	}
	job.lastBytes = job.Done
	job.lastAt = now
	job.UpdatedAt = now
}

func (m *Manager) writeSidecar(path string, job *Job, f *FileJob, committed int64) {
	sc := sidecar{
		ShareID: job.ShareID, PeerID: job.PeerID, RemotePath: f.Rel,
		Size: f.Size, ETag: f.ETag, BytesCommitted: committed,
	}
	_ = writeSidecar(path, sc)
}

func writeSidecar(path string, sc sidecar) error {
	b, err := json.Marshal(sc)
	if err != nil {
		return err
	}
	return config.WriteFileAtomic(path, b, 0o600)
}

func readSidecar(path string) (*sidecar, error) {
	b, err := os.ReadFile(path)
	if err != nil {
		return nil, err
	}
	var sc sidecar
	if err := json.Unmarshal(b, &sc); err != nil {
		return nil, err
	}
	return &sc, nil
}

// SanitizeRemote converts a remote relative path into a safe local relative
// path (spec §9.1 receive side). It rejects traversal and strips control
// characters and Windows-hostile names.
func SanitizeRemote(rel string) (string, error) {
	clean, err := shares.CleanRel(rel)
	if err != nil {
		return "", err
	}
	if clean == "" {
		return "", errors.New("empty name")
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
		if s == "" || isReservedName(s) {
			s = "_" + s
		}
		out = append(out, s)
	}
	return strings.Join(out, "/"), nil
}

func isReservedName(s string) bool {
	base := strings.ToUpper(strings.TrimSuffix(s, filepath.Ext(s)))
	switch base {
	case "CON", "PRN", "AUX", "NUL",
		"COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9",
		"LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9":
		return true
	}
	return false
}

func randHex(n int) string {
	b := make([]byte, n)
	_, _ = rand.Read(b)
	return hex.EncodeToString(b)
}

// resolveTarget decides, once per file, where it is saved. If a *different*
// file already exists at the natural path it is left untouched and the
// download goes to "name (1).ext" ("(2)", ...). An identical copy (same size
// and modification time) is recognised so repeating a download is a no-op
// instead of piling up duplicates.
func (m *Manager) resolveTarget(job *Job, f *FileJob) string {
	job.mu.Lock()
	defer job.mu.Unlock()
	if f.Target != "" {
		return f.Target
	}
	rel := f.Local
	final := filepath.Join(job.Dest, filepath.FromSlash(rel))
	if _, err := os.Stat(final); err == nil && !alreadyComplete(final, f) {
		rel = uniqueRel(job, f)
	}
	f.Target = rel
	return rel
}

// uniqueRel returns the first "stem (n).ext" next to f.Local that is free, or
// that already holds a complete copy of f. Caller holds job.mu.
func uniqueRel(job *Job, f *FileJob) string {
	dir, base := "", f.Local
	if i := strings.LastIndexByte(base, '/'); i >= 0 {
		dir, base = base[:i+1], base[i+1:]
	}
	ext := filepath.Ext(base)
	stem := strings.TrimSuffix(base, ext)
	if stem == "" { // dotfile such as ".env"
		stem, ext = base, ""
	}
	for n := 1; n < 100000; n++ {
		cand := fmt.Sprintf("%s%s (%d)%s", dir, stem, n, ext)
		p := filepath.Join(job.Dest, filepath.FromSlash(cand))
		if _, err := os.Stat(p); err == nil {
			if alreadyComplete(p, f) {
				return cand // an identical copy is already there
			}
			continue
		}
		// A partial from an interrupted earlier run counts as "taken by us".
		if _, err := os.Stat(p + ".lanpart"); err == nil {
			if _, e2 := os.Stat(p + ".lanstate"); e2 == nil {
				return cand
			}
			continue
		}
		taken := false
		for _, o := range job.Files {
			if o != f && (strings.EqualFold(o.Local, cand) || strings.EqualFold(o.Target, cand)) {
				taken = true
				break
			}
		}
		if !taken {
			return cand
		}
	}
	return f.Local
}

// isShareEnded reports a 410 from the sender: the share expired, was stopped,
// or (one-time) was already downloaded.
func isShareEnded(err error) bool {
	var se *peerapi.StatusError
	return errors.As(err, &se) && se.Code == http.StatusGone
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for m := n / unit; m >= unit && exp < 4; m /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %ciB", float64(n)/float64(div), "KMGTP"[exp])
}
