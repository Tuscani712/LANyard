package transfer

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"sync/atomic"
	"time"

	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/peerapi"
	"lanyard/internal/xferlog"
)

// PushParams describes a push into a peer's Inbox.
type PushParams struct {
	PeerID   string
	PeerName string
	Host     string
	Port     int
	Paths    []string // local absolute files or folders
}

// Push enumerates local files, offers them, and uploads them with resume.
func (m *Manager) Push(ctx context.Context, p PushParams) (*View, error) {
	if len(p.Paths) == 0 {
		return nil, errors.New("nothing to push")
	}
	var files []*FileJob
	var total int64
	for _, pth := range p.Paths {
		info, err := os.Stat(pth)
		if err != nil {
			return nil, err
		}
		if info.IsDir() {
			base := filepath.Base(pth)
			err := filepath.WalkDir(pth, func(path string, d os.DirEntry, err error) error {
				if err != nil {
					return err
				}
				if d.IsDir() {
					return nil
				}
				fi, err := d.Info()
				if err != nil || !fi.Mode().IsRegular() {
					return nil
				}
				rel, err := filepath.Rel(pth, path)
				if err != nil {
					return nil
				}
				remote := base + "/" + filepath.ToSlash(rel)
				files = append(files, &FileJob{Rel: remote, Local: path, Size: fi.Size(), MTime: fi.ModTime().UnixNano(), State: FilePending})
				total += fi.Size()
				return nil
			})
			if err != nil {
				return nil, err
			}
		} else {
			files = append(files, &FileJob{Rel: info.Name(), Local: pth, Size: info.Size(), MTime: info.ModTime().UnixNano(), State: FilePending})
			total += info.Size()
		}
	}
	if len(files) == 0 {
		return nil, errors.New("nothing to push")
	}
	sort.Slice(files, func(i, j int) bool { return files[i].Rel < files[j].Rel })

	job := &Job{
		ID: "j_" + randHex(6), Direction: "push",
		PeerID: p.PeerID, PeerName: p.PeerName, Host: p.Host, Port: p.Port,
		ShareLabel: "Inbox", Sources: append([]string(nil), p.Paths...),
		Files: files, Total: total,
		State: StateQueued, StartedAt: time.Now(),
	}
	m.mu.Lock()
	m.jobs[job.ID] = job
	m.mu.Unlock()
	m.persist()
	m.onChange()
	go m.runPush(job)
	return m.view(job), nil
}

func (m *Manager) runPush(job *Job) {
	job.mu.Lock()
	job.ctx, job.cancel = context.WithCancel(context.Background())
	job.running = true
	job.State = StateConnecting
	job.Note = "Waiting for the other device to accept…"
	job.lastAt = time.Now()
	job.lastBytes = job.Done
	ctx := job.ctx
	job.mu.Unlock()
	m.onChange()

	reqs := make([]inbox.FileReq, 0, len(job.Files))
	for _, f := range job.Files {
		reqs = append(reqs, inbox.FileReq{RelPath: f.Rel, Size: f.Size, MTime: time.Unix(0, f.MTime)})
	}
	offerAt := time.Now()
	offer, err := m.client.PushOffer(ctx, job.Host, job.Port, job.PeerID, reqs)
	if err != nil {
		m.xfer(xferlog.Entry{
			Direction: xferlog.DirectionSend, Step: xferlog.StepOffer, Level: xferlog.LevelError,
			Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
			Elapsed: time.Since(offerAt), Error: err.Error(),
		})
		m.pushFail(job, err)
		return
	}
	m.xfer(xferlog.Entry{
		Direction: xferlog.DirectionSend, Step: xferlog.StepOffer, Level: xferlog.LevelInfo,
		Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
		Elapsed: time.Since(offerAt),
	})
	offsets := map[string]int64{}
	for _, f := range offer.Files {
		offsets[f.RelPath] = f.Offset
	}

	job.mu.Lock()
	job.State = StateTransferring
	job.Note = ""
	job.mu.Unlock()
	m.onChange()

	// Files go up in parallel like downloads: many small ones at once (they are
	// latency-bound), a few large ones.
	pctx, stopAll := context.WithCancel(ctx)
	defer stopAll()
	sem := make(chan struct{}, maxJobGoroutines)
	bigSem := make(chan struct{}, m.workers)
	var wg sync.WaitGroup
	var errMu sync.Mutex
	var firstErr error
	for _, f := range job.Files {
		f := f
		select {
		case sem <- struct{}{}:
		case <-pctx.Done():
		}
		if pctx.Err() != nil {
			break
		}
		wg.Add(1)
		go func() {
			defer wg.Done()
			defer func() { <-sem }()
			slots := m.smallSlots
			if f.Size >= smallFile {
				slots = m.slots
				select {
				case bigSem <- struct{}{}:
				case <-pctx.Done():
					return
				}
				defer func() { <-bigSem }()
			}
			select {
			case slots <- struct{}{}:
			case <-pctx.Done():
				return
			}
			defer func() { <-slots }()
			if err := m.pushOne(pctx, job, offer.PushID, f, offsets[f.Rel]); err != nil {
				errMu.Lock()
				if firstErr == nil {
					firstErr = err
				}
				errMu.Unlock()
				stopAll() // the first failure ends the rest
			}
		}()
	}
	wg.Wait()
	if firstErr != nil || ctx.Err() != nil {
		job.mu.Lock()
		job.running = false
		cancelled := errors.Is(ctx.Err(), context.Canceled)
		switch {
		case cancelled && job.cancelled:
			job.State = StateCancelled
			if job.FinishedAt.IsZero() {
				job.FinishedAt = time.Now()
			}
			job.Note = ""
		case cancelled:
			job.State = StatePaused
		}
		job.mu.Unlock()
		if !cancelled {
			m.pushFail(job, firstErr)
		}
		m.persist()
		m.onChange()
		return
	}
	completeAt := time.Now()
	if err := m.client.PushComplete(ctx, job.Host, job.Port, job.PeerID, offer.PushID, "", "", true); err != nil {
		// The whole push is not finalized on the receiver: the job must not be
		// marked Done. Surface it like any other transfer failure.
		m.xfer(xferlog.Entry{
			Direction: xferlog.DirectionSend, Step: xferlog.StepComplete, Level: xferlog.LevelError,
			Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
			Elapsed: time.Since(completeAt), Error: err.Error(),
		})
		m.pushFail(job, fmt.Errorf("could not finalize the transfer: %w", err))
		return
	}
	m.xfer(xferlog.Entry{
		Direction: xferlog.DirectionSend, Step: xferlog.StepComplete, Level: xferlog.LevelInfo,
		Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
		Bytes: job.Total, Elapsed: time.Since(completeAt),
	})
	job.mu.Lock()
	job.running = false
	job.State = StateDone
	job.Done = job.Total
	job.UpdatedAt = time.Now()
	job.mu.Unlock()
	m.persist()
	m.onChange()
	m.fireDone(job)
}

func (m *Manager) pushOne(ctx context.Context, job *Job, pushID string, f *FileJob, offset int64) error {
	if f.Size < smallFile && offset == 0 {
		return m.pushSmall(ctx, job, pushID, f)
	}
	src, err := os.Open(f.Local)
	if err != nil {
		return err
	}
	defer src.Close()
	h := sha256.New()
	if offset > 0 {
		// Re-hash the prefix already on the receiver so the digest covers the
		// whole file, then rewind to send the remainder.
		if _, err := io.CopyN(h, src, offset); err != nil {
			return err
		}
		if _, err := src.Seek(offset, io.SeekStart); err != nil {
			return err
		}
	}
	var body io.Reader = io.TeeReader(src, h)
	if mbps := m.bandwidth(); mbps > 0 {
		body = &throttleReader{r: body, mbps: mbps, start: time.Now()}
	}
	// Publish the resumed prefix before streaming so job.Done is offset (not
	// zero) and setFileDone, which is absolute, never adds the prefix twice.
	job.mu.Lock()
	setFileDone(job, f, offset)
	f.State = FilePartial
	job.UpdatedAt = time.Now()
	job.lastProgress = time.Now()
	job.mu.Unlock()
	m.onChange()
	putAt := time.Now()
	cr := &pushCountingReader{m: m, job: job, f: f, r: body, base: offset}
	written, err := m.client.PushFile(ctx, job.Host, job.Port, job.PeerID, pushID, f.Rel, offset, f.Size, cr)
	if err != nil {
		m.xfer(xferlog.Entry{
			Direction: xferlog.DirectionSend, Step: xferlog.StepFile, Level: xferlog.LevelError,
			Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
			File: filepath.Base(f.Rel), Bytes: cr.sent.Load(), Elapsed: time.Since(putAt), Error: err.Error(),
		})
		return err
	}
	m.xfer(xferlog.Entry{
		Direction: xferlog.DirectionSend, Step: xferlog.StepFile, Level: xferlog.LevelInfo,
		Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
		File: filepath.Base(f.Rel), Bytes: written, Elapsed: time.Since(putAt),
	})
	if offset+written < f.Size {
		if _, err := io.Copy(h, src); err != nil {
			return err
		}
	}
	sum := hex.EncodeToString(h.Sum(nil))
	completeAt := time.Now()
	if err := m.client.PushComplete(ctx, job.Host, job.Port, job.PeerID, pushID, f.Rel, sum, false); err != nil {
		m.xfer(xferlog.Entry{
			Direction: xferlog.DirectionSend, Step: xferlog.StepComplete, Level: xferlog.LevelError,
			Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
			File: filepath.Base(f.Rel), Elapsed: time.Since(completeAt), Error: err.Error(),
		})
		return err
	}
	m.xfer(xferlog.Entry{
		Direction: xferlog.DirectionSend, Step: xferlog.StepComplete, Level: xferlog.LevelInfo,
		Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
		File: filepath.Base(f.Rel), Bytes: f.Size, Elapsed: time.Since(completeAt),
	})
	job.mu.Lock()
	setFileDone(job, f, f.Size)
	f.State = FileDone
	job.UpdatedAt = time.Now()
	m.bumpSpeed(job)
	job.mu.Unlock()
	m.onChange()
	return nil
}

// pushCountingReader wraps an upload body and records the job's live progress
// as bytes leave the sender, so the UI advances during a slow push instead of
// jumping to 100% only once the whole file has been verified. base is the
// number of bytes already on the receiver for this attempt; because
// setFileDone sets an absolute value, a retry/resume can never double-count
// the prefix that was already transferred.
type pushCountingReader struct {
	m    *Manager
	job  *Job
	f    *FileJob
	r    io.Reader
	base int64
	sent atomic.Int64
}

func (c *pushCountingReader) Read(p []byte) (int, error) {
	n, err := c.r.Read(p)
	if n > 0 {
		c.sent.Add(int64(n))
		c.job.mu.Lock()
		setFileDone(c.job, c.f, c.base+c.sent.Load())
		c.job.lastProgress = time.Now()
		c.m.bumpSpeed(c.job)
		c.job.mu.Unlock()
		c.m.notifyProgress()
	}
	return n, err
}

func (m *Manager) pushFail(job *Job, err error) {
	job.mu.Lock()
	job.running = false
	job.State = StateFailed
	job.Error = peerapi.UserMessage(err)
	job.UpdatedAt = time.Now()
	job.FinishedAt = job.UpdatedAt
	job.mu.Unlock()
	m.persist()
	m.onChange()
	m.fireFail(job)
}

// pushTarget is the host:port a push is talking to, for the transfer log.
func pushTarget(host string, port int) string {
	return fmt.Sprintf("%s:%d", host, port)
}

// pushFP is the peer short fingerprint for the transfer log (never the full id).
func pushFP(job *Job) string { return identity.ShortID(job.PeerID) }

// pushSmall sends a small file and its digest in one request; the receiver
// verifies and finalizes it in the same call.
func (m *Manager) pushSmall(ctx context.Context, job *Job, pushID string, f *FileJob) error {
	data, err := os.ReadFile(f.Local)
	if err != nil {
		return err
	}
	if int64(len(data)) != f.Size {
		return errors.New(f.Rel + ": the file changed while it was being sent")
	}
	sum := sha256.Sum256(data)
	var body io.Reader = bytes.NewReader(data)
	if mbps := m.bandwidth(); mbps > 0 {
		body = &throttleReader{r: body, mbps: mbps, start: time.Now()}
	}
	putAt := time.Now()
	if err := m.client.PushFileSHA(ctx, job.Host, job.Port, job.PeerID, pushID, f.Rel, body, f.Size, hex.EncodeToString(sum[:])); err != nil {
		m.xfer(xferlog.Entry{
			Direction: xferlog.DirectionSend, Step: xferlog.StepFile, Level: xferlog.LevelError,
			Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
			File: filepath.Base(f.Rel), Elapsed: time.Since(putAt), Error: err.Error(),
		})
		return err
	}
	m.xfer(xferlog.Entry{
		Direction: xferlog.DirectionSend, Step: xferlog.StepFile, Level: xferlog.LevelInfo,
		Job: job.ID, FP: pushFP(job), Peer: job.PeerName, Target: pushTarget(job.Host, job.Port),
		File: filepath.Base(f.Rel), Bytes: f.Size, Elapsed: time.Since(putAt),
	})
	job.mu.Lock()
	setFileDone(job, f, f.Size)
	f.State = FileDone
	job.UpdatedAt = time.Now()
	job.lastProgress = time.Now()
	m.bumpSpeed(job)
	job.mu.Unlock()
	m.onChange()
	return nil
}
