package peerapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"net/http"
	"sort"
	"strconv"
	"strings"

	"lanyard/internal/approval"
	"lanyard/internal/inbox"
	"lanyard/internal/trust"
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
// any live Connect session.
func (s *Server) pushAccess(w http.ResponseWriter, r *http.Request) (trust.Access, bool) {
	if s.inbox == nil {
		http.Error(w, "inbox unavailable", http.StatusServiceUnavailable)
		return trust.Access{}, false
	}
	var a trust.Access
	if s.auth != nil {
		a = s.auth.Access(PeerID(r.Context()))
	}
	if a.Paired && a.Push {
		return a, true
	}
	if a.SessionID != "" {
		return a, true
	}
	if a.Paired {
		http.Error(w, "push not permitted", http.StatusForbidden)
		return a, false
	}
	http.Error(w, "not permitted", http.StatusForbidden)
	return a, false
}

const (
	maxOfferBytes = 128 << 20
	maxOfferFiles = 500000
)

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
	a, ok := s.pushAccess(w, r)
	if !ok {
		return
	}
	var req struct {
		Files      []inbox.FileReq `json:"files"`
		TotalBytes int64           `json:"total_bytes"`
	}
	// A folder push lists every file in one offer (about 150 bytes each), so the
	// body limit has to cover a large tree, not just a handful of files.
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, maxOfferBytes)).Decode(&req); err != nil {
		http.Error(w, "bad request (the list of files is too large or malformed)", http.StatusBadRequest)
		return
	}
	if len(req.Files) == 0 {
		http.Error(w, "no files", http.StatusBadRequest)
		return
	}
	if len(req.Files) > maxOfferFiles {
		http.Error(w, fmt.Sprintf("too many files in one push (limit %d); send the folder in parts", maxOfferFiles), http.StatusRequestEntityTooLarge)
		return
	}
	total := req.TotalBytes
	if total <= 0 {
		for _, f := range req.Files {
			total += f.Size
		}
	}
	// A Connect session, or a paired peer above its ask-over limit, needs a
	// person to accept (spec §4.2/§4.4). Without an approval queue (tests, the
	// dev flag) pushes keep the old automatic behaviour.
	if reason := needsApproval(a, total); reason != "" && s.approvals != nil {
		if !s.askToAccept(w, r, a, reason, req.Files, total) {
			return
		}
	}
	p, err := s.inbox.Offer(PeerID(r.Context()), a.SessionID, req.Files, a.MaxPushBytes)
	if err != nil {
		code := http.StatusBadRequest
		if strings.Contains(err.Error(), "storage") || strings.Contains(err.Error(), "exceeds") {
			code = http.StatusInsufficientStorage
		}
		http.Error(w, err.Error(), code)
		return
	}
	resp := pushOfferResp{PushID: p.ID, Accepted: true, MaxBytes: p.MaxBytes}
	for _, f := range p.SortedOffsets() {
		resp.Files = append(resp.Files, pushFileResp{RelPath: f.RelPath, Offset: f.Offset})
	}
	writeJSON(w, resp)
}

func (s *Server) handlePushFile(w http.ResponseWriter, r *http.Request) {
	a, ok := s.pushAccess(w, r)
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
		st, err := s.inbox.Receive(r.PathValue("id"), PeerID(r.Context()), rel, sha, r.Body)
		if err != nil {
			code := http.StatusBadRequest
			switch {
			case strings.Contains(err.Error(), "no such"):
				code = http.StatusNotFound
			case strings.Contains(err.Error(), "mismatch"):
				code = http.StatusConflict
			}
			http.Error(w, err.Error(), code)
			return
		}
		writeJSON(w, map[string]any{"written": st.Size, "offset": st.Size, "done": true})
		return
	}
	n, err := s.inbox.WriteChunk(r.PathValue("id"), PeerID(r.Context()), rel, offset, r.Body)
	if err != nil {
		code := http.StatusBadRequest
		if strings.Contains(err.Error(), "no such") {
			code = http.StatusNotFound
		}
		http.Error(w, err.Error(), code)
		return
	}
	writeJSON(w, map[string]int64{"written": n, "offset": offset + n})
	_ = a
}

func (s *Server) handlePushComplete(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.pushAccess(w, r); !ok {
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
	if req.All {
		s.inbox.Finish(id, fp)
		writeJSON(w, map[string]bool{"done": true})
		return
	}
	st, err := s.inbox.Complete(id, fp, req.RelPath, req.SHA256)
	if err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
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
