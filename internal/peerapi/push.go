package peerapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"path"
	"sort"
	"strconv"
	"strings"
	"time"

	"lanyard/internal/approval"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/trust"
	"lanyard/internal/xferlog"
)

// SetInbox attaches the Inbox receiver. Kept as a setter so the constructor
// signature does not grow for every milestone.
func (s *Server) SetInbox(m *inbox.Manager) { s.inbox = m }

// SetApprovals attaches the queue used to ask a person before accepting
// pushes from a Connect session or larger-than-limit pushes from paired peers.
func (s *Server) SetApprovals(m *approval.Manager) { s.approvals = m }

// needsApproval says why a person must accept this push first, or "".
func needsApproval(a trust.Access, total int64) string {
	switch {
	case !a.Paired && a.SessionID != "":
		return "connect"
	case a.Paired && a.AskOver > 0 && total > a.AskOver:
		return "large"
	}
	return ""
}

// peerName is the display name we hold for the caller (never trusted markup).
func (s *Server) peerName(fp string, a trust.Access) string {
	if s.trust != nil {
		if a.SessionID != "" {
			if sess, ok := s.trust.Snapshot(a.SessionID); ok && sess.PeerName != "" {
				return sess.PeerName
			}
		}
		if e, ok := s.trust.Entry(fp); ok && e.Name != "" {
			return e.Name
		}
	}
	return "A device"
}

// approvalKey identifies "the same transfer" so a resumed push is not asked
// about twice.
func approvalKey(fp string, files []inbox.FileReq) string {
	lines := make([]string, 0, len(files))
	for _, f := range files {
		lines = append(lines, fmt.Sprintf("%s\x00%d", f.RelPath, f.Size))
	}
	sort.Strings(lines)
	h := sha256.New()
	h.Write([]byte(fp))
	for _, l := range lines {
		h.Write([]byte{'\n'})
		h.Write([]byte(l))
	}
	return hex.EncodeToString(h.Sum(nil))
}

// askToAccept blocks the sender's offer until a person decides. It writes the
// refusal itself and reports whether the push may go ahead.
func (s *Server) askToAccept(w http.ResponseWriter, r *http.Request, a trust.Access, reason string, files []inbox.FileReq, total int64) bool {
	fp := PeerID(r.Context())
	shown := make([]approval.File, 0, len(files))
	for _, f := range files {
		shown = append(shown, approval.File{Path: cleanLabel(f.RelPath), Size: f.Size})
	}
	ok, err := s.approvals.Ask(r.Context(), approval.Request{
		PeerFP: fp, PeerName: cleanLabel(s.peerName(fp, a)), Reason: reason,
		Files: shown, Count: len(files), Total: total,
	}, approvalKey(fp, files))
	switch {
	case err == nil && ok:
		return true
	case errors.Is(err, approval.ErrTooMany):
		http.Error(w, "Too many requests are waiting on the other device.", http.StatusTooManyRequests)
	case errors.Is(err, approval.ErrTimeout):
		http.Error(w, "The other device did not answer in time.", http.StatusForbidden)
	case err != nil:
		// The sender hung up while waiting; nothing to answer.
	default:
		http.Error(w, "The other device declined the transfer.", http.StatusForbidden)
	}
	return false
}

// pushAccess authorizes a push endpoint: a paired peer with push permission, or
// any live Connect session. step names the stage being authorized, for the
// transfer log when the request is refused.
func (s *Server) pushAccess(w http.ResponseWriter, r *http.Request, step string) (trust.Access, bool) {
	fp := PeerID(r.Context())
	if s.inbox == nil {
		http.Error(w, "inbox unavailable", http.StatusServiceUnavailable)
		s.xferRecv(PeerID(r.Context()), step, xferlog.LevelError, s.peerName(fp, trust.Access{}), r.RemoteAddr, "", 0, 0, errors.New("inbox unavailable"))
		return trust.Access{}, false
	}
	var a trust.Access
	if s.auth != nil {
		a = s.auth.Access(fp)
	}
	if a.Paired && a.Push {
		return a, true
	}
	if a.SessionID != "" {
		return a, true
	}
	reason := "not permitted"
	if a.Paired {
		reason = "push not permitted"
	}
	http.Error(w, reason, http.StatusForbidden)
	s.xferRecv(PeerID(r.Context()), step, xferlog.LevelWarn, s.peerName(fp, a), r.RemoteAddr, "", 0, 0, errors.New("not paired with this device"))
	return a, false
}

// xferRecv records one receiving-side transfer entry. It never stores a full
// file path: callers pass a base name. The peer identity is reduced to its
// short fingerprint.
func (s *Server) xferRecv(fp, step string, level xferlog.Level, peer, target, file string, bytes int64, elapsed time.Duration, err error) {
	e := xferlog.Entry{
		Area: xferlog.AreaPushing, Direction: xferlog.DirectionReceive, Step: step, Level: level,
		FP: identity.ShortID(fp), Peer: peer, Target: target, File: file, Bytes: bytes, Elapsed: elapsed,
	}
	if err != nil {
		e.Error = err.Error()
	}
	s.xfer(e)
}

// xferRecvFile records a per-file receipt with its offset and size, which is
// what tells resume apart from a fresh write.
func (s *Server) xferRecvFile(fp, step string, level xferlog.Level, peer, target, class string, offset, size, bytes int64, elapsed time.Duration, err error) {
	e := xferlog.Entry{
		Area: xferlog.AreaPushing, Direction: xferlog.DirectionReceive, Step: step, Level: level,
		FP: identity.ShortID(fp), Peer: peer, Target: target, File: class,
		Offset: offset, Size: size, Bytes: bytes, Elapsed: elapsed,
	}
	if err != nil {
		e.Error = err.Error()
	}
	s.xfer(e)
}

// The desktop receiver's own caps. These are the values advertised in hello
// (discovery.Hello.MaxOfferBytes / MaxOfferFiles). A sender that gets no
// advertised limits from an older peer falls back to the Android-side floor.
const (
	MaxOfferBytes = 128 << 20
	MaxOfferFiles = 500000

	// FallbackOfferBytes / FallbackOfferFiles are the safe floor a sender uses
	// when a peer's hello advertises no limits (older builds). They match the
	// Android receiver so a desktop sending to an un-updated phone still fits.
	FallbackOfferBytes = 8 << 20
	FallbackOfferFiles = 50000
)

// EffectiveOfferLimits returns the offer limits a sender should honour for a
// peer. An advertised value wins; a missing (zero/negative) value or a nil
// hello falls back to the Android-side floor.
func EffectiveOfferLimits(h *discovery.Hello) (maxBytes int64, maxFiles int) {
	maxBytes, maxFiles = FallbackOfferBytes, FallbackOfferFiles
	if h == nil {
		return maxBytes, maxFiles
	}
	if h.MaxOfferBytes > 0 {
		maxBytes = h.MaxOfferBytes
	}
	if h.MaxOfferFiles > 0 {
		maxFiles = h.MaxOfferFiles
	}
	return maxBytes, maxFiles
}

type pushFileResp struct {
	RelPath string `json:"rel_path"`
	Offset  int64  `json:"offset"`
}

type pushOfferResp struct {
	PushID   string         `json:"push_id"`
	Accepted bool           `json:"accepted"`
	MaxBytes int64          `json:"max_bytes"`
	Files    []pushFileResp `json:"files"`
}

func (s *Server) handlePushOffer(w http.ResponseWriter, r *http.Request) {
	a, ok := s.pushAccess(w, r, xferlog.StepOffer)
	if !ok {
		return
	}
	var req struct {
		Files      []inbox.FileReq `json:"files"`
		TotalBytes int64           `json:"total_bytes"`
	}
	// A folder push lists every file in one offer (about 150 bytes each), so the
	// body limit has to cover a large tree, not just a handful of files.
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, MaxOfferBytes)).Decode(&req); err != nil {
		var mbe *http.MaxBytesError
		if errors.As(err, &mbe) {
			// The body was truncated mid-stream: the connection cannot be
			// reused (the unread tail would be misparsed as the next request),
			// so tell the sender to close it and surface a 413.
			w.Header().Set("Connection", "close")
			http.Error(w, "the list of files is too large for this device", http.StatusRequestEntityTooLarge)
			return
		}
		http.Error(w, "bad request (the list of files is too large or malformed)", http.StatusBadRequest)
		return
	}
	if len(req.Files) == 0 {
		http.Error(w, "no files", http.StatusBadRequest)
		return
	}
	if len(req.Files) > MaxOfferFiles {
		w.Header().Set("Connection", "close")
		http.Error(w, fmt.Sprintf("too many files in one push (limit %d)", MaxOfferFiles), http.StatusRequestEntityTooLarge)
		return
	}
	total := req.TotalBytes
	if total <= 0 {
		for _, f := range req.Files {
			total += f.Size
		}
	}
	offerAt := time.Now()
	// A Connect session, or a paired peer above its ask-over limit, needs a
	// person to accept (spec §4.2/§4.4). Without an approval queue (tests, the
	// dev flag) pushes keep the old automatic behaviour.
	if reason := needsApproval(a, total); reason != "" && s.approvals != nil {
		if !s.askToAccept(w, r, a, reason, req.Files, total) {
			s.xferRecv(PeerID(r.Context()), xferlog.StepOffer, xferlog.LevelWarn, s.peerName(PeerID(r.Context()), a), r.RemoteAddr, "", 0, time.Since(offerAt), errors.New("the offer was declined or not answered"))
			return
		}
	}
	p, err := s.inbox.Offer(PeerID(r.Context()), a.SessionID, req.Files, a.MaxPushBytes)
	if err != nil {
		code := http.StatusBadRequest
		switch {
		case errors.Is(err, inbox.ErrFilesBusy):
			// A concurrent push from the same peer is already receiving these
			// files. A 409 (not a 400/500) tells the sender this is a transient
			// conflict and the readable body is shown to the person. Two live
			// pushes must never share a .lanpart, so the newer one is refused
			// intact rather than allowed to clobber the older one.
			code = http.StatusConflict
		case strings.Contains(err.Error(), "storage") || strings.Contains(err.Error(), "exceeds"):
			code = http.StatusInsufficientStorage
		}
		http.Error(w, err.Error(), code)
		s.xferRecv(PeerID(r.Context()), xferlog.StepOffer, xferlog.LevelError, s.peerName(PeerID(r.Context()), a), r.RemoteAddr, "", 0, time.Since(offerAt), err)
		return
	}
	s.xferRecv(PeerID(r.Context()), xferlog.StepOffer, xferlog.LevelInfo, s.peerName(PeerID(r.Context()), a), r.RemoteAddr, "", 0, time.Since(offerAt), nil)
	resp := pushOfferResp{PushID: p.ID, Accepted: true, MaxBytes: p.MaxBytes}
	for _, f := range p.SortedOffsets() {
		resp.Files = append(resp.Files, pushFileResp{RelPath: f.RelPath, Offset: f.Offset})
	}
	writeJSON(w, resp)
}

func (s *Server) handlePushFile(w http.ResponseWriter, r *http.Request) {
	a, ok := s.pushAccess(w, r, xferlog.StepFile)
	if !ok {
		return
	}
	rel := r.URL.Query().Get("path")
	if rel == "" {
		http.Error(w, "path required", http.StatusBadRequest)
		return
	}
	offset, err := parseContentRange(r.Header.Get("Content-Range"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	// Small-file fast path: the whole file and its digest in one request, which
	// the receiver verifies and finalizes at once (no separate "complete" call).
	if sha := r.Header.Get("X-Lanyard-SHA256"); sha != "" && offset == 0 {
		fileAt := time.Now()
		st, already, err := s.inbox.Receive(r.PathValue("id"), PeerID(r.Context()), rel, sha, r.Body)
		if err != nil {
			code := http.StatusBadRequest
			switch {
			case errors.Is(err, inbox.ErrCancelled) || s.inbox.WasCancelled(r.PathValue("id")):
				code = http.StatusGone
				err = inbox.ErrCancelled
			case strings.Contains(err.Error(), "no such"):
				code = http.StatusNotFound
			case strings.Contains(err.Error(), "mismatch"):
				code = http.StatusConflict
			}
			http.Error(w, err.Error(), code)
			s.xferRecvFile(PeerID(r.Context()), xferlog.StepFile, xferlog.LevelError, s.peerName(PeerID(r.Context()), a), r.RemoteAddr, path.Base(rel), 0, 0, 0, time.Since(fileAt), err)
			return
		}
		// A replayed whole-file request for an already-placed file is an
		// idempotent success: report the same result without a second receipt.
		if !already {
			s.xferRecvFile(PeerID(r.Context()), xferlog.StepFile, xferlog.LevelInfo, s.peerName(PeerID(r.Context()), a), r.RemoteAddr, path.Base(rel), 0, st.Size, st.Size, time.Since(fileAt), nil)
		}
		writeJSON(w, map[string]any{"written": st.Size, "offset": st.Size, "done": true})
		return
	}
	fileAt := time.Now()
	n, err := s.inbox.WriteChunk(r.PathValue("id"), PeerID(r.Context()), rel, offset, r.Body)
	if err != nil {
		code := http.StatusBadRequest
		if errors.Is(err, inbox.ErrCancelled) || s.inbox.WasCancelled(r.PathValue("id")) {
			code, err = http.StatusGone, inbox.ErrCancelled
		} else if strings.Contains(err.Error(), "no such") {
			code = http.StatusNotFound
		}
		http.Error(w, err.Error(), code)
		s.xferRecvFile(PeerID(r.Context()), xferlog.StepFile, xferlog.LevelError, s.peerName(PeerID(r.Context()), a), r.RemoteAddr, path.Base(rel), offset, 0, 0, time.Since(fileAt), err)
		return
	}
	s.xferRecvFile(PeerID(r.Context()), xferlog.StepFile, xferlog.LevelInfo, s.peerName(PeerID(r.Context()), a), r.RemoteAddr, path.Base(rel), offset, 0, n, time.Since(fileAt), nil)
	writeJSON(w, map[string]int64{"written": n, "offset": offset + n})
	_ = a
}

// handlePushCancel lets the sender (the owner of the push) ask the receiver to
// stop a push it is still receiving. It is idempotent and harmless: a known id
// owned by the caller is cancelled, and an unknown id (or one owned by another
// peer) is a no-op answered 200, so the route is safe to retry and a peer can
// never cancel someone else's push. The push leaves the Receiving/Finishing
// list immediately and partial files/spool are freed through the normal cancel
// path.
func (s *Server) handlePushCancel(w http.ResponseWriter, r *http.Request) {
	_, ok := s.pushAccess(w, r, xferlog.StepCancel)
	if !ok {
		return
	}
	id := r.PathValue("id")
	fp := PeerID(r.Context())
	if s.inbox != nil {
		s.inbox.CancelBy(id, fp, "Cancelled by the sender")
	}
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) handlePushComplete(w http.ResponseWriter, r *http.Request) {
	a, ok := s.pushAccess(w, r, xferlog.StepComplete)
	if !ok {
		return
	}
	var req struct {
		RelPath string `json:"rel_path"`
		SHA256  string `json:"sha256"`
		All     bool   `json:"all"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	id := r.PathValue("id")
	fp := PeerID(r.Context())
	peer := s.peerName(fp, a)
	file := path.Base(req.RelPath)
	completeAt := time.Now()
	if req.All {
		// A repeated final complete is an idempotent success: only the first
		// Finish removes the push and records it, so the duplicate neither
		// errors nor double-records.
		if s.inbox.Finish(id, fp) {
			s.xferRecv(PeerID(r.Context()), xferlog.StepComplete, xferlog.LevelInfo, peer, r.RemoteAddr, file, 0, time.Since(completeAt), nil)
		}
		writeJSON(w, map[string]bool{"done": true})
		return
	}
	st, already, err := s.inbox.Complete(id, fp, req.RelPath, req.SHA256)
	if err != nil {
		if s.inbox.WasCancelled(id) {
			err = inbox.ErrCancelled
		}
		// A 409 conflict carries the Connect session id and its age so a
		// resume/verify mismatch can be told apart from a stale session.
		var age time.Duration
		sessionID := a.SessionID
		if sessionID != "" && s.trust != nil {
			if sess, ok := s.trust.Snapshot(sessionID); ok {
				age = time.Since(sess.CreatedAt)
			}
		}
		s.xfer(xferlog.Entry{
			Area: xferlog.AreaPushing, Direction: xferlog.DirectionReceive, Step: xferlog.StepComplete,
			Level: xferlog.LevelError, FP: identity.ShortID(fp), Peer: peer, Target: r.RemoteAddr,
			File: file, Session: sessionID, Age: age, Elapsed: time.Since(completeAt), Error: err.Error(),
		})
		if errors.Is(err, inbox.ErrCancelled) {
			http.Error(w, inbox.ErrCancelled.Error(), http.StatusGone)
			return
		}
		// A failed finalize must end the push as Failed with the reason rather
		// than leave the row "Receiving". Complete already fails it internally;
		// this safety net covers any other error path.
		s.inbox.Fail(id, err.Error())
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	// A replayed per-file complete for an already-placed file returns the same
	// result without a second receipt, so it cannot double-record.
	if !already {
		s.xferRecv(PeerID(r.Context()), xferlog.StepComplete, xferlog.LevelInfo, peer, r.RemoteAddr, path.Base(st.RelPath), st.Size, time.Since(completeAt), nil)
	}
	writeJSON(w, map[string]any{"rel_path": st.RelPath, "done": true})
}

// parseContentRange reads "bytes START-END/TOTAL" and returns START.
func parseContentRange(h string) (int64, error) {
	if h == "" {
		return 0, nil
	}
	h = strings.TrimSpace(h)
	if !strings.HasPrefix(h, "bytes ") {
		return 0, fmt.Errorf("bad Content-Range %q", h)
	}
	rangePart := strings.TrimPrefix(h, "bytes ")
	if i := strings.IndexByte(rangePart, '/'); i >= 0 {
		rangePart = rangePart[:i]
	}
	dash := strings.IndexByte(rangePart, '-')
	if dash <= 0 {
		return 0, fmt.Errorf("bad Content-Range %q", h)
	}
	start, err := strconv.ParseInt(strings.TrimSpace(rangePart[:dash]), 10, 64)
	if err != nil || start < 0 {
		return 0, fmt.Errorf("bad Content-Range %q", h)
	}
	return start, nil
}
