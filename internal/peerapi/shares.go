package peerapi

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"os"
	"strconv"
	"strings"
	"sync"

	"lanyard/internal/shares"
	"lanyard/internal/trust"
)

// accessFor authorizes a data endpoint from the caller's certificate and
// returns its access decision. A peer may list/read a share only if it is
// paired with pull permission or holds a live Connect session.
func (s *Server) accessFor(w http.ResponseWriter, r *http.Request) (trust.Access, bool) {
	if s.shares == nil {
		http.Error(w, "shares unavailable", http.StatusForbidden)
		return trust.Access{}, false
	}
	fp := PeerID(r.Context())
	var a trust.Access
	if s.auth != nil {
		a = s.auth.Access(fp)
	}
	session := a.SessionID != ""
	if !a.Paired && !session {
		http.Error(w, "not permitted", http.StatusForbidden)
		return a, false
	}
	if a.Paired && !a.Browse {
		http.Error(w, "pull not permitted", http.StatusForbidden)
		return a, false
	}
	return a, true
}

// visibleShare returns a share the peer is allowed to see. A share the peer
// used that has since ended answers 410 with the reason; anything else the peer
// may not see answers 404 so shares cannot be probed.
func (s *Server) visibleShare(w http.ResponseWriter, r *http.Request, id string) (*shares.Share, trust.Access, bool) {
	a, ok := s.accessFor(w, r)
	if !ok {
		return nil, a, false
	}
	fp := PeerID(r.Context())
	sh, ok := s.shares.GetVisible(id, a.Paired, a.DeviceID, fp, a.Offered)
	if !ok {
		s.denyShare(w, id, fp)
		return nil, a, false
	}
	s.shares.Seen(id, fp)
	return sh, a, true
}

// transferShare is visibleShare for the data-moving endpoints: it also lets a
// peer that was already transferring finish after the share ended (grace).
func (s *Server) transferShare(w http.ResponseWriter, r *http.Request, id string) (*shares.Share, trust.Access, bool) {
	a, ok := s.accessFor(w, r)
	if !ok {
		return nil, a, false
	}
	fp := PeerID(r.Context())
	sh, ok := s.shares.GetForTransfer(id, a.Paired, a.DeviceID, fp, a.Offered)
	if !ok {
		s.denyShare(w, id, fp)
		return nil, a, false
	}
	s.shares.Seen(id, fp)
	return sh, a, true
}

func (s *Server) denyShare(w http.ResponseWriter, id, fp string) {
	if reason, ok := s.shares.Ended(id, fp); ok {
		http.Error(w, shareEndedMsg(reason), http.StatusGone)
		return
	}
	http.Error(w, "share not found", http.StatusNotFound)
}

func shareEndedMsg(reason string) string {
	switch reason {
	case "expired":
		return "This share has expired."
	case "completed":
		return "This one-time share has already been downloaded."
	default:
		return "The sender stopped this share."
	}
}

func (s *Server) handleShareList(w http.ResponseWriter, r *http.Request) {
	a, ok := s.accessFor(w, r)
	if !ok {
		return
	}
	writeJSON(w, s.shares.ListVisible(a.Paired, a.DeviceID, PeerID(r.Context()), a.Offered))
}

func (s *Server) handleTree(w http.ResponseWriter, r *http.Request) {
	sh, _, ok := s.visibleShare(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	entries, err := sh.Tree(r.URL.Query().Get("path"))
	if err != nil {
		if errors.Is(err, shares.ErrBadPath) {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, entries)
}

func (s *Server) handleManifest(w http.ResponseWriter, r *http.Request) {
	sh, _, ok := s.visibleShare(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	files, total, err := sh.Manifest(r.URL.Query().Get("path"))
	if err != nil {
		if errors.Is(err, shares.ErrBadPath) {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, map[string]any{"files": files, "total_bytes": total, "count": len(files), "lifetime": sh.Lifetime.Type})
}

// handleFile serves a file with Range/If-Range and a whole-file SHA-256 trailer
// on full responses (spec §7, §8.2).
func (s *Server) handleFile(w http.ResponseWriter, r *http.Request) {
	sh, _, ok := s.transferShare(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	// Register the stream so "Stop now" / the end of the grace period can cut it.
	ctx, cancel := context.WithCancel(r.Context())
	defer cancel()
	release := s.shares.TrackTransfer(sh.ShareID, PeerID(r.Context()), cancel)
	defer release()
	rel := r.URL.Query().Get("path")
	f, info, err := sh.OpenFile(rel)
	if err != nil {
		if errors.Is(err, shares.ErrBadPath) {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		if os.IsNotExist(err) {
			http.Error(w, "not found", http.StatusNotFound)
			return
		}
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	defer f.Close()
	if info.IsDir() {
		http.Error(w, "not a file", http.StatusBadRequest)
		return
	}

	etag := `"` + shares.Validator(info) + `"`
	w.Header().Set("ETag", etag)
	w.Header().Set("Accept-Ranges", "bytes")
	w.Header().Set("Content-Type", "application/octet-stream")

	// If-Range: only honour a range when the validator still matches.
	rangeHdr := r.Header.Get("Range")
	if ir := r.Header.Get("If-Range"); ir != "" && QuoteETag(ir) != etag {
		rangeHdr = ""
	}

	if rangeHdr == "" || r.Method == http.MethodHead {
		w.Header().Set("Content-Length", strconv.FormatInt(info.Size(), 10))
		if r.Method == http.MethodHead {
			w.WriteHeader(http.StatusOK)
			return
		}
		w.Header().Set("Trailer", "X-Content-SHA256")
		w.WriteHeader(http.StatusOK)
		h := sha256.New()
		if _, err := io.CopyBuffer(w, &ctxReader{ctx: ctx, r: io.TeeReader(f, h)}, copyBuf()); err != nil {
			s.log.Debug("file stream interrupted", "err", err)
			return
		}
		sum := hex.EncodeToString(h.Sum(nil))
		w.Header().Set("X-Content-SHA256", sum)
		// A complete pass is as good as a dedicated hash request: remember it so
		// one-time completion does not have to re-read the file.
		s.hashes.put(hashKey(sh.ShareID, rel, shares.Validator(info)), sum)
		return
	}

	start, end, ok := parseRange(rangeHdr, info.Size())
	if !ok {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", info.Size()))
		http.Error(w, "range not satisfiable", http.StatusRequestedRangeNotSatisfiable)
		return
	}
	if _, err := f.Seek(start, io.SeekStart); err != nil {
		http.Error(w, "seek failed", http.StatusInternalServerError)
		return
	}
	length := end - start + 1
	w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", start, end, info.Size()))
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	w.WriteHeader(http.StatusPartialContent)
	if _, err := io.CopyBuffer(w, &ctxReader{ctx: ctx, r: io.LimitReader(f, length)}, copyBuf()); err != nil {
		s.log.Debug("range stream interrupted", "err", err)
	}
	// Note: a partial body is not trailer-hashed; whole-file verification on
	// resume lands with the M3 hash state.
}

// ctxReader stops a copy as soon as its context is cancelled.
type ctxReader struct {
	ctx context.Context
	r   io.Reader
}

func (c *ctxReader) Read(p []byte) (int, error) {
	if err := c.ctx.Err(); err != nil {
		return 0, err
	}
	return c.r.Read(p)
}

func hashKey(shareID, rel, validator string) string { return shareID + "|" + rel + "|" + validator }

// fileDigest returns the whole-file SHA-256 of rel, cached per share, path and
// validator so an unchanged file is read at most once.
func (s *Server) fileDigest(sh *shares.Share, rel string) (string, os.FileInfo, error) {
	f, info, err := sh.OpenFile(rel)
	if err != nil {
		return "", nil, err
	}
	defer f.Close()
	if info.IsDir() {
		return "", nil, errNotFile
	}
	sum, err := s.hashes.get(hashKey(sh.ShareID, rel, shares.Validator(info)), func() (string, error) {
		h := sha256.New()
		if _, err := io.CopyBuffer(h, f, copyBuf()); err != nil {
			return "", err
		}
		return hex.EncodeToString(h.Sum(nil)), nil
	})
	return sum, info, err
}

var errNotFile = errors.New("not a file")

// handleHash returns the SHA-256 of a whole file so a receiver can verify a
// resumed download (206 bodies carry no trailer).
func (s *Server) handleHash(w http.ResponseWriter, r *http.Request) {
	sh, _, ok := s.transferShare(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	s.shares.TouchTransfer(sh.ShareID, PeerID(r.Context()))
	sum, info, err := s.fileDigest(sh, r.URL.Query().Get("path"))
	if err != nil {
		switch {
		case errors.Is(err, shares.ErrBadPath):
			http.Error(w, "bad path", http.StatusBadRequest)
		case os.IsNotExist(err):
			http.Error(w, "not found", http.StatusNotFound)
		case errors.Is(err, errNotFile):
			http.Error(w, "not a file", http.StatusBadRequest)
		default:
			http.Error(w, "hash failed", http.StatusInternalServerError)
		}
		return
	}
	writeJSON(w, map[string]any{"sha256": sum, "size": info.Size(), "etag": shares.Validator(info)})
}

// handleComplete is how a receiver reports a finished, hash-verified download.
// It is what consumes a one-time share: the claimed digests are checked against
// the files themselves, so a bare "I'm done" cannot burn someone's share.
func (s *Server) handleComplete(w http.ResponseWriter, r *http.Request) {
	sh, _, ok := s.transferShare(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	fp := PeerID(r.Context())
	var req struct {
		Files []struct {
			Path   string `json:"path"`
			SHA256 string `json:"sha256"`
		} `json:"files"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32<<20)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if sh.Lifetime.Type != shares.LifetimeOneTime {
		writeJSON(w, map[string]any{"consumed": false})
		return
	}
	if len(req.Files) == 0 || len(req.Files) > 500000 {
		http.Error(w, "no verified files reported", http.StatusBadRequest)
		return
	}
	s.shares.TouchTransfer(sh.ShareID, fp)
	for _, f := range req.Files {
		sum, _, err := s.fileDigest(sh, f.Path)
		if err != nil || !strings.EqualFold(sum, f.SHA256) {
			http.Error(w, "reported file does not match: "+f.Path, http.StatusConflict)
			return
		}
	}
	writeJSON(w, map[string]any{"consumed": s.shares.Consume(sh.ShareID, fp)})
}

type hashEntry struct {
	once sync.Once
	sum  string
	err  error
}

// hashCache memoises whole-file digests; it is bounded and drops failures.
type hashCache struct {
	mu sync.Mutex
	m  map[string]*hashEntry
}

func (c *hashCache) get(key string, fn func() (string, error)) (string, error) {
	c.mu.Lock()
	if c.m == nil || len(c.m) > 100000 {
		c.m = map[string]*hashEntry{}
	}
	e := c.m[key]
	if e == nil {
		e = &hashEntry{}
		c.m[key] = e
	}
	c.mu.Unlock()
	e.once.Do(func() { e.sum, e.err = fn() })
	if e.err != nil {
		c.mu.Lock()
		delete(c.m, key)
		c.mu.Unlock()
	}
	return e.sum, e.err
}

// put records a digest computed while streaming a whole file.
func (c *hashCache) put(key, sum string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.m == nil || len(c.m) > 100000 {
		c.m = map[string]*hashEntry{}
	}
	e := &hashEntry{}
	e.once.Do(func() { e.sum = sum })
	c.m[key] = e
}

// parseRange handles a single "bytes=" range. Suffix ranges ("-N") are not
// needed by the transfer engine and are rejected.
func parseRange(h string, size int64) (start, end int64, ok bool) {
	if !strings.HasPrefix(h, "bytes=") {
		return 0, 0, false
	}
	spec := strings.TrimPrefix(h, "bytes=")
	if strings.Contains(spec, ",") {
		return 0, 0, false
	}
	dash := strings.IndexByte(spec, '-')
	if dash < 0 {
		return 0, 0, false
	}
	lo, hi := strings.TrimSpace(spec[:dash]), strings.TrimSpace(spec[dash+1:])
	if lo == "" {
		return 0, 0, false
	}
	start, err := strconv.ParseInt(lo, 10, 64)
	if err != nil || start < 0 || start >= size {
		return 0, 0, false
	}
	if hi == "" {
		return start, size - 1, true
	}
	end, err = strconv.ParseInt(hi, 10, 64)
	if err != nil || end < start {
		return 0, 0, false
	}
	if end > size-1 {
		end = size - 1
	}
	return start, end, true
}

func copyBuf() []byte { return make([]byte, 256*1024) }
