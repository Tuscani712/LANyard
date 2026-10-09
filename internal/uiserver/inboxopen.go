package uiserver

import (
	"net/http"
	"os"
)

// handleInboxOpen opens the Inbox folder in the desktop's file manager. It is
// what the "Open folder" buttons (Settings and a received history entry) call.
func (s *Server) handleInboxOpen(w http.ResponseWriter, r *http.Request) {
	if s.d.Inbox == nil {
		http.Error(w, "inbox unavailable", http.StatusServiceUnavailable)
		return
	}
	dir := s.d.Inbox.Dir()
	if err := os.MkdirAll(dir, 0o700); err != nil {
		http.Error(w, "could not open the folder: "+err.Error(), http.StatusInternalServerError)
		return
	}
	open := s.d.OpenFolder
	if open == nil {
		open = openPath
	}
	if err := open(dir); err != nil {
		http.Error(w, "could not open the folder: "+err.Error(), http.StatusInternalServerError)
		return
	}
	writeJSON(w, map[string]string{"opened": dir})
}
