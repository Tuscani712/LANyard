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
	"time"

	"lanyard/internal/approval"
	"lanyard/internal/identity"
	"lanyard/internal/shares"
	"lanyard/internal/trust"
	"lanyard/internal/xferlog"
)

// pathClass names a category for a share path so the pull log never stores the
// real path. It is deliberately coarse: extension family, or "dir"/"other".
func pathClass(rel string) string {
	ext := strings.ToLower(pathExt(rel))
	switch ext {
	case ".jpg", ".jpeg", ".png", ".gif", ".webp", ".bmp", ".svg", ".heic", ".tiff":
		return "image"
	case ".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".wmv":
		return "video"
	case ".mp3", ".wav", ".flac", ".m4a", ".aac", ".ogg", ".opus":
		return "audio"
	case ".pdf", ".doc", ".docx", ".xls", ".xlsx", ".ppt", ".pptx", ".txt", ".md", ".rtf", ".odt", ".csv":
		return "document"
	case ".zip", ".tar", ".gz", ".bz2", ".7z", ".rar", ".xz":
		return "archive"
	case ".go", ".js", ".ts", ".py", ".rs", ".java", ".c", ".cpp", ".h", ".json", ".yml", ".yaml", ".xml", ".html", ".css", ".sh":
		return "code"
	case ".exe", ".msi", ".dmg", ".apk", ".app", ".deb", ".rpm":
		return "app"
	}
	if ext == "" {
		return "dir"
	}
	return "other"
}

// pathExt is filepath.Ext without importing path/filepath into this file's hot
// path; it returns the last-dot suffix including the dot.
func pathExt(p string) string {
	if i := strings.LastIndexByte(p, '.'); i >= 0 && !strings.ContainsAny(p[i:], `/\`) {
		return p[i:]
	}
	return ""
}

// xferPull records one serving-side pull event. rel is reduced to a path class;
// the target is the caller's address, and the peer to its short fingerprint.
func (s *Server) xferPull(level xferlog.Level, outcome, fp, target, class, reason string, bytes, offset, size int64, elapsed time.Duration, err error) {
	e := xferlog.Entry{
		Area: xferlog.AreaPulling, Level: level, Outcome: outcome,
		FP: identity.ShortID(fp), Target: target, File: class,
		Bytes: bytes, Offset: offset, Size: size, Reason: reason, Elapsed: elapsed,
	}
	if err != nil {
		e.Error = err.Error()
	}
	s.xfer(e)
}

// accessFor authorizes a data endpoint from the caller's certificate and
// returns its access decision. A peer may list/read a share only if it is
// paired with a browse permission that is not Never or holds a live Connect
// session. A paired peer whose browse state is Ask is prompted once per browse
// session (the first request blocks on a person; later requests in the same
// session are remembered).
func (s *Server) accessFor(w http.ResponseWriter, r *http.Request) (trust.Access, bool) {
	fp := PeerID(r.Context())
	if s.shares == nil {
		http.Error(w, "shares unavailable", http.StatusForbidden)
		s.xferPull(xferlog.LevelError, "deny", fp, r.RemoteAddr, "", "shares unavailable", 0, 0, 0, 0, errors.New("shares unavailable"))
		return trust.Access{}, false
	}
	var a trust.Access
	if s.auth != nil {
		a = s.auth.Access(fp)
	}
	session := a.SessionID != ""
	if !a.Paired && !session {
		http.Error(w, "not permitted", http.StatusForbidden)
		s.xferPull(xferlog.LevelWarn, "deny", fp, r.RemoteAddr, "", "not paired", 0, 0, 0, 0, errors.New("not paired"))
		return a, false
	}
	if a.Paired && a.Browse.Denies() {
		http.Error(w, "pull not permitted", http.StatusForbidden)
		s.xferPull(xferlog.LevelWarn, "deny", fp, r.RemoteAddr, "", "pull not permitted", 0, 0, 0, 0, errors.New("pull not permitted"))
		return a, false
	}
	if a.Paired && a.Browse.Asks() && !s.approveBrowse(w, r, a) {
		return a, false
	}
	return a, true
}

// approveBrowse asks the person on this device, once per browse session, whether
// a peer may browse the shares. The acceptance is remembered by the approval
// manager under a per-peer browse key, so later requests in the same session do
// not prompt again; an unanswered prompt (timeout) denies the request.
func (s *Server) approveBrowse(w http.ResponseWriter, r *http.Request, a trust.Access) bool {
	fp := PeerID(r.Context())
	if s.approvals == nil {
		http.Error(w, permissionReasonUserDenied, http.StatusForbidden)
		s.xferPull(xferlog.LevelWarn, "deny", fp, r.RemoteAddr, "", permissionReasonUserDenied, 0, 0, 0, 0, errors.New(permissionReasonUserDenied))
		return false
	}
	ok, err := s.approvals.Ask(r.Context(), approval.Request{
		PeerFP: fp, PeerName: cleanLabel(s.peerName(fp, a)), Reason: "browse",
	}, "browse:"+fp)
	switch {
	case err == nil && ok:
		return true
	case errors.Is(err, approval.ErrTooMany):
		http.Error(w, "Too many requests are waiting on the other device.", http.StatusTooManyRequests)
	case errors.Is(err, approval.ErrTimeout):
		http.Error(w, "The other device did not answer in time.", http.StatusForbidden)
	case err != nil:
		// The peer hung up while waiting; nothing to answer.
	default:
		http.Error(w, permissionReasonUserDenied, http.StatusForbidden)
	}
	s.xferPull(xferlog.LevelWarn, "deny", fp, r.RemoteAddr, "", "browse "+permissionReasonUserDenied, 0, 0, 0, 0, errors.New(permissionReasonUserDenied))
	return false
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
		s.xferPull(xferlog.LevelWarn, "deny", fp, "", "", "share "+reason, 0, 0, 0, 0, nil)
		http.Error(w, shareEndedMsg(reason), http.StatusGone)
		return
	}
	s.xferPull(xferlog.LevelWarn, "deny", fp, "", "", "share not found", 0, 0, 0, 0, nil)
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
	fp := PeerID(r.Context())
	list := s.shares.ListVisible(a.Paired, a.DeviceID, fp, a.Offered)
	s.xferPull(xferlog.LevelInfo, "share-list", fp, r.RemoteAddr, "", fmt.Sprintf("%d shares", len(list)), 0, 0, 0, 0, nil)
	writeJSON(w, list)
}

func (s *Server) handleTree(w http.ResponseWriter, r *http.Request) {
	sh, _, ok := s.visibleShare(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	fp := PeerID(r.Context())
	rel := r.URL.Query().Get("path")
	entries, err := sh.Tree(rel)
	if err != nil {
		s.xferPull(xferlog.LevelWarn, "browse", fp, r.RemoteAddr, pathClass(rel), "tree failed", 0, 0, 0, 0, err)
		if errors.Is(err, shares.ErrBadPath) {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.xferPull(xferlog.LevelInfo, "browse", fp, r.RemoteAddr, pathClass(rel), fmt.Sprintf("%d entries", len(entries)), 0, 0, 0, 0, nil)
	writeJSON(w, entries)
}

func (s *Server) handleManifest(w http.ResponseWriter, r *http.Request) {
	sh, _, ok := s.visibleShare(w, r, r.PathValue("id"))
	if !ok {
		return
	}
	fp := PeerID(r.Context())
	rel := r.URL.Query().Get("path")
	files, total, err := sh.Manifest(rel)
	if err != nil {
		s.xferPull(xferlog.LevelWarn, "browse", fp, r.RemoteAddr, pathClass(rel), "manifest failed", 0, 0, 0, 0, err)
		if errors.Is(err, shares.ErrBadPath) {
			http.Error(w, "bad path", http.StatusBadRequest)
			return
		}
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	s.xferPull(xferlog.LevelInfo, "browse", fp, r.RemoteAddr, pathClass(rel), fmt.Sprintf("manifest %d files", len(files)), total, 0, 0, 0, nil)
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
	fp := PeerID(r.Context())
	start := time.Now()
	release := s.shares.TrackTransfer(sh.ShareID, fp, cancel)
	defer release()
	rel := r.URL.Query().Get("path")
	class := pathClass(rel)
	f, info, err := sh.OpenFile(rel)
	if err != nil {
		s.xferPull(xferlog.LevelWarn, "download", fp, r.RemoteAddr, class, "open failed", 0, 0, 0, time.Since(start), err)
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
		s.xferPull(xferlog.LevelWarn, "download", fp, r.RemoteAddr, class, "not a file", 0, 0, 0, time.Since(start), errNotFile)
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
			s.xferPull(xferlog.LevelInfo, "download", fp, r.RemoteAddr, class, "head", 0, 0, info.Size(), time.Since(start), nil)
			w.WriteHeader(http.StatusOK)
			return
		}
		w.Header().Set("Trailer", "X-Content-SHA256")
		w.WriteHeader(http.StatusOK)
		h := sha256.New()
		n, err := io.CopyBuffer(w, &ctxReader{ctx: ctx, r: io.TeeReader(f, h)}, copyBuf())
		if err != nil {
			s.log.Debug("file stream interrupted", "err", err)
			s.xferPull(xferlog.LevelWarn, "download", fp, r.RemoteAddr, class, "stream interrupted", n, 0, info.Size(), time.Since(start), err)
			return
		}
		sum := hex.EncodeToString(h.Sum(nil))
		w.Header().Set("X-Content-SHA256", sum)
		// A complete pass is as good as a dedicated hash request: remember it so
		// one-time completion does not have to re-read the file.
		s.hashes.put(hashKey(sh.ShareID, rel, shares.Validator(info)), sum)
		s.xferPull(xferlog.LevelInfo, "serve", fp, r.RemoteAddr, class, "complete", n, 0, info.Size(), time.Since(start), nil)
		return
	}

	startOff, end, ok := parseRange(rangeHdr, info.Size())
	if !ok {
		w.Header().Set("Content-Range", fmt.Sprintf("bytes */%d", info.Size()))
		s.xferPull(xferlog.LevelWarn, "download", fp, r.RemoteAddr, class, "range not satisfiable", 0, 0, info.Size(), time.Since(start), errors.New("range not satisfiable"))
		http.Error(w, "range not satisfiable", http.StatusRequestedRangeNotSatisfiable)
		return
	}
	if _, err := f.Seek(startOff, io.SeekStart); err != nil {
		s.xferPull(xferlog.LevelError, "download", fp, r.RemoteAddr, class, "seek failed", 0, startOff, info.Size(), time.Since(start), err)
		http.Error(w, "seek failed", http.StatusInternalServerError)
		return
	}
	length := end - startOff + 1
	w.Header().Set("Content-Range", fmt.Sprintf("bytes %d-%d/%d", startOff, end, info.Size()))
	w.Header().Set("Content-Length", strconv.FormatInt(length, 10))
	w.WriteHeader(http.StatusPartialContent)
	n, err := io.CopyBuffer(w, &ctxReader{ctx: ctx, r: io.LimitReader(f, length)}, copyBuf())
	if err != nil {
		s.log.Debug("range stream interrupted", "err", err)
		s.xferPull(xferlog.LevelWarn, "download", fp, r.RemoteAddr, class, "range interrupted", n, startOff, info.Size(), time.Since(start), err)
		return
	}
	s.xferPull(xferlog.LevelInfo, "serve", fp, r.RemoteAddr, class, "range complete", n, startOff, info.Size(), time.Since(start), nil)
	// A partial body is not trailer-hashed; after a resume the client verifies
	// the whole file through /hash.
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
	fp := PeerID(r.Context())
	rel := r.URL.Query().Get("path")
	start := time.Now()
	s.shares.TouchTransfer(sh.ShareID, fp)
	sum, info, err := s.fileDigest(sh, rel)
	if err != nil {
		s.xferPull(xferlog.LevelWarn, "hash", fp, r.RemoteAddr, pathClass(rel), "hash failed", 0, 0, 0, time.Since(start), err)
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
	s.xferPull(xferlog.LevelInfo, "hash", fp, r.RemoteAddr, pathClass(rel), "hash served", 0, 0, info.Size(), time.Since(start), nil)
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
	start := time.Now()
	var req struct {
		Files []struct {
			Path   string `json:"path"`
			SHA256 string `json:"sha256"`
		} `json:"files"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 32<<20)).Decode(&req); err != nil {
		s.xferPull(xferlog.LevelWarn, "complete", fp, r.RemoteAddr, "", "bad request", 0, 0, 0, time.Since(start), err)
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if sh.Lifetime.Type != shares.LifetimeOneTime {
		s.xferPull(xferlog.LevelInfo, "complete", fp, r.RemoteAddr, "", "not one-time", 0, 0, 0, time.Since(start), nil)
		writeJSON(w, map[string]any{"consumed": false})
		return
	}
	if len(req.Files) == 0 || len(req.Files) > 500000 {
		s.xferPull(xferlog.LevelWarn, "complete", fp, r.RemoteAddr, "", "no verified files reported", 0, 0, 0, time.Since(start), errors.New("no verified files reported"))
		http.Error(w, "no verified files reported", http.StatusBadRequest)
		return
	}
	s.shares.TouchTransfer(sh.ShareID, fp)
	for _, f := range req.Files {
		sum, _, err := s.fileDigest(sh, f.Path)
		if err != nil || !strings.EqualFold(sum, f.SHA256) {
			s.xferPull(xferlog.LevelWarn, "complete", fp, r.RemoteAddr, pathClass(f.Path), "reported file does not match", 0, 0, 0, time.Since(start), errors.New("reported file does not match"))
			http.Error(w, "reported file does not match: "+f.Path, http.StatusConflict)
			return
		}
	}
	consumed := s.shares.Consume(sh.ShareID, fp)
	s.xferPull(xferlog.LevelInfo, "serve", fp, r.RemoteAddr, "", "download complete", 0, 0, 0, time.Since(start), nil)
	writeJSON(w, map[string]any{"consumed": consumed})
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
