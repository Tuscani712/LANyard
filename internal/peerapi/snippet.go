package peerapi

import (
	"encoding/json"
	"net/http"

	"lanyard/internal/inbox"
)

// handleSnippet receives a short text message from a peer. It is authorized by
// the text permission (a paired peer whose text state is Allow or Ask, or a
// live Connect session) and lands in the receiver's Inbox as a text item.
func (s *Server) handleSnippet(w http.ResponseWriter, r *http.Request) {
	if _, ok := s.textAccess(w, r); !ok {
		return
	}
	if s.inbox == nil {
		http.Error(w, "inbox unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Text string `json:"text"`
	}
	// The body may be longer than the text itself once JSON escaping is applied.
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, inbox.MaxSnippetBytes*2+4096)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	if err := inbox.ValidateSnippet(req.Text); err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	sp, err := s.inbox.AddSnippet(PeerID(r.Context()), req.Text)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	writeJSON(w, map[string]string{"id": sp.ID})
}
