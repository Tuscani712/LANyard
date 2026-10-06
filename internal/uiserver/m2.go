package uiserver

import (
	"encoding/json"
	"net/http"
	"strings"

	"lanyard/internal/discovery"
	"lanyard/internal/shares"
	"lanyard/internal/transfer"
)

func (s *Server) peerByID(id string) (discovery.Peer, bool) {
	if id == "" {
		return discovery.Peer{}, false
	}
	for _, p := range s.d.Peers() {
		if p.DeviceID == id || p.ShortID == id {
			return p, true
		}
	}
	return discovery.Peer{}, false
}

func peerAddr(p discovery.Peer) (string, int, bool) {
	if len(p.Addrs) == 0 || p.Port == 0 {
		return "", 0, false
	}
	return p.Addrs[0], p.Port, true
}

// --- local shares ---

func (s *Server) handleShares(w http.ResponseWriter, r *http.Request) {
	if s.d.Shares == nil {
		writeJSON(w, []any{})
		return
	}
	writeJSON(w, s.d.Shares.LocalViews())
}

func (s *Server) handleShareAdd(w http.ResponseWriter, r *http.Request) {
	if s.d.Shares == nil {
		http.Error(w, "shares unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Path           string   `json:"path"`
		Label          string   `json:"label"`
		Lifetime       string   `json:"lifetime"`
		Seconds        int      `json:"seconds"`
		Visibility     string   `json:"visibility"`
		AllowedDevices []string `json:"allowed_devices"`
		Confirm        bool     `json:"confirm"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 8192)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	req.Path = strings.TrimSpace(req.Path)
	if req.Path == "" {
		http.Error(w, "a path is required", http.StatusBadRequest)
		return
	}
	if reason := shares.Risky(req.Path); reason != "" && !req.Confirm {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusConflict)
		_ = json.NewEncoder(w).Encode(map[string]string{"warning": "This looks like " + reason + ". Share it anyway?"})
		return
	}
	sh, err := s.d.Shares.Add(req.Path, shares.AddOptions{
		Label: req.Label, Visibility: req.Visibility, AllowedDevices: req.AllowedDevices,
		LifetimeType: req.Lifetime, DurationSec: req.Seconds,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, sh)
}

func (s *Server) handleShareStop(w http.ResponseWriter, r *http.Request) {
	if s.d.Shares == nil {
		http.Error(w, "shares unavailable", http.StatusServiceUnavailable)
		return
	}
	if !s.d.Shares.Stop(r.PathValue("id")) {
		http.Error(w, "no such share", http.StatusNotFound)
		return
	}
	writeJSON(w, map[string]bool{"stopped": true})
}

func (s *Server) handleShareStopAll(w http.ResponseWriter, r *http.Request) {
	if s.d.Shares == nil {
		http.Error(w, "shares unavailable", http.StatusServiceUnavailable)
		return
	}
	n := s.d.Shares.StopAll()
	writeJSON(w, map[string]int{"stopped": n})
}

// --- remote browse ---

func (s *Server) handleRemoteShares(w http.ResponseWriter, r *http.Request) {
	if s.d.Client == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	p, ok := s.peerByID(r.URL.Query().Get("device"))
	if !ok {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	host, port, ok := peerAddr(p)
	if !ok {
		http.Error(w, "device has no reachable address", http.StatusBadGateway)
		return
	}
	list, err := s.d.Client.ListShares(r.Context(), host, port, p.DeviceID)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	if list == nil {
		list = []shares.Summary{}
	}
	writeJSON(w, list)
}

func (s *Server) handleRemoteTree(w http.ResponseWriter, r *http.Request) {
	if s.d.Client == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	q := r.URL.Query()
	p, ok := s.peerByID(q.Get("device"))
	if !ok {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	host, port, ok := peerAddr(p)
	if !ok {
		http.Error(w, "device has no reachable address", http.StatusBadGateway)
		return
	}
	entries, err := s.d.Client.Tree(r.Context(), host, port, p.DeviceID, q.Get("share"), q.Get("path"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	if entries == nil {
		entries = []shares.Entry{}
	}
	writeJSON(w, entries)
}

// --- transfers ---

func (s *Server) handleTransfers(w http.ResponseWriter, r *http.Request) {
	if s.d.Transfers == nil {
		writeJSON(w, []any{})
		return
	}
	writeJSON(w, s.d.Transfers.List())
}

func (s *Server) handleTransferCreate(w http.ResponseWriter, r *http.Request) {
	if s.d.Transfers == nil {
		http.Error(w, "transfers unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Device     string   `json:"device"`
		ShareID    string   `json:"share_id"`
		ShareLabel string   `json:"share_label"`
		PeerName   string   `json:"peer_name"`
		Paths      []string `json:"paths"`
		Dest       string   `json:"dest"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	p, ok := s.peerByID(req.Device)
	if !ok {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	host, port, ok := peerAddr(p)
	if !ok {
		http.Error(w, "device has no reachable address", http.StatusBadGateway)
		return
	}
	view, err := s.d.Transfers.Create(r.Context(), transfer.CreateParams{
		PeerID: p.DeviceID, PeerName: firstNonEmpty(req.PeerName, p.Name),
		Host: host, Port: port,
		ShareID: req.ShareID, ShareLabel: req.ShareLabel,
		Paths: req.Paths, Dest: strings.TrimSpace(req.Dest),
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, view)
}

func (s *Server) handleTransferPause(w http.ResponseWriter, r *http.Request) {
	s.transferAction(w, r, "pause")
}

func (s *Server) handleTransferResume(w http.ResponseWriter, r *http.Request) {
	s.transferAction(w, r, "resume")
}

func (s *Server) handleTransferCancel(w http.ResponseWriter, r *http.Request) {
	if s.d.Transfers == nil {
		http.Error(w, "transfers unavailable", http.StatusServiceUnavailable)
		return
	}
	del := r.URL.Query().Get("delete") == "1"
	if err := s.d.Transfers.Cancel(r.PathValue("id"), del); err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) transferAction(w http.ResponseWriter, r *http.Request, action string) {
	if s.d.Transfers == nil {
		http.Error(w, "transfers unavailable", http.StatusServiceUnavailable)
		return
	}
	var err error
	if action == "pause" {
		err = s.d.Transfers.Pause(r.PathValue("id"))
	} else {
		err = s.d.Transfers.Resume(r.PathValue("id"))
	}
	if err != nil {
		http.Error(w, err.Error(), http.StatusNotFound)
		return
	}
	writeJSON(w, map[string]bool{"ok": true})
}

func (s *Server) handleTransfersClear(w http.ResponseWriter, r *http.Request) {
	if s.d.Transfers == nil {
		http.Error(w, "transfers unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, map[string]int{"cleared": s.d.Transfers.ClearFinished()})
}

// handleTransfersClearHistory forgets every finished and failed job.
func (s *Server) handleTransfersClearHistory(w http.ResponseWriter, r *http.Request) {
	if s.d.Transfers == nil {
		http.Error(w, "transfers unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, map[string]int{"cleared": s.d.Transfers.ClearHistory()})
}

// handleTransferRetry re-creates a finished or failed job with the same peer,
// sources and destination, leaving the old one in the history.
func (s *Server) handleTransferRetry(w http.ResponseWriter, r *http.Request) {
	if s.d.Transfers == nil {
		http.Error(w, "transfers unavailable", http.StatusServiceUnavailable)
		return
	}
	view, err := s.d.Transfers.Retry(r.Context(), r.PathValue("id"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusConflict)
		return
	}
	writeJSON(w, view)
}

func firstNonEmpty(a, b string) string {
	if a != "" {
		return a
	}
	return b
}

// handlePush starts a push of local files into a peer's Inbox.
func (s *Server) handlePush(w http.ResponseWriter, r *http.Request) {
	if s.d.Transfers == nil {
		http.Error(w, "transfers unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Device string   `json:"device"`
		Paths  []string `json:"paths"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if len(req.Paths) == 0 {
		http.Error(w, "no paths", http.StatusBadRequest)
		return
	}
	p, ok := s.peerByID(req.Device)
	if !ok {
		http.Error(w, "unknown device", http.StatusNotFound)
		return
	}
	host, port, ok := peerAddr(p)
	if !ok {
		http.Error(w, "device has no reachable address", http.StatusBadGateway)
		return
	}
	view, err := s.d.Transfers.Push(r.Context(), transfer.PushParams{
		PeerID: p.DeviceID, PeerName: p.Name, Host: host, Port: port, Paths: req.Paths,
	})
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, view)
}
