package uiserver

import (
	"encoding/json"
	"net/http"

	"lanyard/internal/inbox"
)

// snippetView is a received text snippet with the sender's name filled in.
type snippetView struct {
	inbox.Snippet
	PeerName string `json:"peer_name"`
}

func (s *Server) snippets() []snippetView {
	out := []snippetView{}
	if s.d.Inbox == nil {
		return out
	}
	for _, sp := range s.d.Inbox.Snippets() {
		// A local alias wins so the text list names the sender the way the
		// person does; otherwise fall back to discovery's broadcast name.
		name := ""
		if s.d.Trust != nil {
			for _, e := range s.d.Trust.Paired() {
				if e.Fingerprint == sp.PeerFP && e.DisplayName() != "" {
					name = e.DisplayName()
				}
			}
		}
		if name == "" {
			for _, p := range s.d.Peers() {
				if p.DeviceID == sp.PeerFP {
					name = p.Name
				}
			}
		}
		out = append(out, snippetView{Snippet: sp, PeerName: name})
	}
	return out
}

func (s *Server) handleSnippets(w http.ResponseWriter, r *http.Request) { writeJSON(w, s.snippets()) }

// handleSendSnippet sends a short text message to a paired peer. The peer side
// authorizes it with the same permission check as a push.
func (s *Server) handleSendSnippet(w http.ResponseWriter, r *http.Request) {
	if s.d.Client == nil {
		http.Error(w, "unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Device string `json:"device"`
		Text   string `json:"text"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, inbox.MaxSnippetBytes*2+8192)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if err := inbox.ValidateSnippet(req.Text); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
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
	if err := s.d.Client.SendSnippet(r.Context(), host, port, p.DeviceID, req.Text); err != nil {
		s.peerRefusedPairing(p.DeviceID, err)
		http.Error(w, peerUIMessage(err), peerStatus(err))
		return
	}
	writeJSON(w, map[string]bool{"sent": true})
}

// handleSnippetDismiss removes a received snippet from the local list.
func (s *Server) handleSnippetDismiss(w http.ResponseWriter, r *http.Request) {
	if s.d.Inbox == nil || !s.d.Inbox.DismissSnippet(r.PathValue("id")) {
		http.Error(w, "no such snippet", http.StatusNotFound)
		return
	}
	s.Notify()
	writeJSON(w, map[string]bool{"ok": true})
}
