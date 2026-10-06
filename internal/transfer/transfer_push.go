package transfer

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"io"
	"os"
	"path/filepath"
	"sort"
	"sync"
	"time"

	"lanyard/internal/inbox"
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
		ShareLabel: "Inbox", Files: files, Total: total,
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
	offer, err := m.client.PushOffer(ctx, job.Host, job.Port, job.PeerID, reqs)
	if err != nil {
		m.pushFail(job, err)
		return
	}
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
	if firstErr != nil {
		job.mu.Lock()
		job.running = false
		paused := errors.Is(ctx.Err(), context.Canceled)
		if paused {
			job.State = StatePaused
		}
		job.mu.Unlock()
		if !paused {
			m.pushFail(job, firstErr)
		}
		m.persist()
		m.onChange()
		return
	}
	_ = m.client.PushComplete(ctx, job.Host, job.Port, job.PeerID, offer.PushID, "", "", true)
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
	written, err := m.client.PushFile(ctx, job.Host, job.Port, job.PeerID, pushID, f.Rel, offset, f.Size, body)
	if err != nil {
		return err
	}
	if offset+written < f.Size {
		if _, err := io.Copy(h, src); err != nil {
			return err
		}
	}
	sum := hex.EncodeToString(h.Sum(nil))
	if err := m.client.PushComplete(ctx, job.Host, job.Port, job.PeerID, pushID, f.Rel, sum, false); err != nil {
		return err
	}
	job.mu.Lock()
	f.State = FileDone
	f.Done = f.Size
	job.Done += f.Size - offset
	job.UpdatedAt = time.Now()
	m.bumpSpeed(job)
	job.mu.Unlock()
	m.onChange()
	return nil
}

func (m *Manager) pushFail(job *Job, err error) {
	job.mu.Lock()
	job.running = false
	job.State = StateFailed
	job.Error = err.Error()
	job.UpdatedAt = time.Now()
	job.mu.Unlock()
	m.persist()
	m.onChange()
	m.fireFail(job)
}

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
	if err := m.client.PushFileSHA(ctx, job.Host, job.Port, job.PeerID, pushID, f.Rel, body, f.Size, hex.EncodeToString(sum[:])); err != nil {
		return err
	}
	job.mu.Lock()
	f.State = FileDone
	f.Done = f.Size
	job.Done += f.Size
	job.UpdatedAt = time.Now()
	job.lastProgress = time.Now()
	m.bumpSpeed(job)
	job.mu.Unlock()
	m.onChange()
	return nil
}
