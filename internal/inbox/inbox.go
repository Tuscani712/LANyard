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
	"time"
	"unicode"

	"lanyard/internal/shares"
)

// DefaultMaxBytes is the limit applied when the peer has none configured: none.
// The sender was allowed to push (paired with push permission, or accepted by a
// person in a Connect session) and free space is still checked on every offer.
const DefaultMaxBytes = int64(1) << 62

// syncMin is the size from which a received file is fsynced before it is renamed
// into place. A smaller file lost to a crash is simply sent again.
const syncMin = 1 << 20

// FileReq is one file in a push offer.
type FileReq struct {
	RelPath string    `json:"rel_path"`
	Size    int64     `json:"size"`
	MTime   time.Time `json:"mtime"`
}

// FileState tracks one file of a push.
type FileState struct {
	RelPath string    `json:"rel_path"`
	Size    int64     `json:"size"`
	MTime   time.Time `json:"mtime"`
	Done    int64     `json:"done"`
	Final   string    `json:"-"`
	Part    string    `json:"-"`
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
}

// Manager owns pushes for one process.
type Manager struct {
	dir      string
	onChange func()

	mu     sync.Mutex
	pushes map[string]*Push
}

// FreeSpace reports the bytes available to the current user on the volume
// holding path, or 0 if that cannot be determined.
func FreeSpace(path string) int64 { return freeSpace(path) }

func New(dir string, onChange func()) *Manager {
	if onChange == nil {
		onChange = func() {}
	}
	return &Manager{dir: dir, onChange: onChange, pushes: map[string]*Push{}}
}

func (m *Manager) Dir() string { return m.dir }

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
	m.mu.Unlock()
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
	f, err := os.OpenFile(st.Part, os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		return 0, err
	}
	defer f.Close()
	if _, err := f.Seek(offset, io.SeekStart); err != nil {
		return 0, err
	}
	n, err := io.CopyBuffer(f, io.LimitReader(r, st.Size-offset), make([]byte, 256*1024))
	if err != nil {
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
	f, err := os.OpenFile(st.Part, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return nil, err
	}
	h := sha256.New()
	// Read one byte more than announced so an oversized body is noticed.
	n, err := io.CopyBuffer(io.MultiWriter(f, h), io.LimitReader(r, st.Size+1), make([]byte, 64*1024))
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
		return fail(err)
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

// Finish removes a completed push.
func (m *Manager) Finish(id, peerFP string) bool {
	m.mu.Lock()
	p, ok := m.pushes[id]
	if ok && p.PeerFP == peerFP {
		delete(m.pushes, id)
	}
	m.mu.Unlock()
	if ok {
		m.onChange()
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
