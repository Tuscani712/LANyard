package uiserver

import (
	"net/http"

	"lanyard/internal/inbox"
)

// incomingView is a push being received, with the sender's name filled in.
type incomingView struct {
	inbox.IncomingView
	PeerName string `json:"peer_name"`
}

func (s *Server) incoming() []incomingView {
	out := []incomingView{}
	if s.d.Inbox == nil {
		return out
	}
	for _, v := range s.d.Inbox.Incoming() {
		name := ""
		for _, p := range s.d.Peers() {
			if p.DeviceID == v.PeerFP {
				name = p.Name
			}
		}
		if name == "" && s.d.Trust != nil {
			for _, e := range s.d.Trust.Paired() {
				if e.Fingerprint == v.PeerFP {
					name = e.Name
				}
			}
		}
		out = append(out, incomingView{IncomingView: v, PeerName: name})
	}
	return out
}

func (s *Server) handleIncoming(w http.ResponseWriter, r *http.Request) { writeJSON(w, s.incoming()) }

// handleIncomingCancel lets the receiving person stop a push they already
// accepted. Files that finished stay in the Inbox; partial files are removed.
func (s *Server) handleIncomingCancel(w http.ResponseWriter, r *http.Request) {
	if s.d.Inbox == nil || !s.d.Inbox.Cancel(r.PathValue("id")) {
		http.Error(w, "that transfer is no longer running", http.StatusNotFound)
		return
	}
	s.Notify()
	writeJSON(w, map[string]bool{"ok": true})
}
