package uiserver

import "net/http"

func (s *Server) handleApprovals(w http.ResponseWriter, r *http.Request) {
	if s.d.Approvals == nil {
		writeJSON(w, []struct{}{})
		return
	}
	writeJSON(w, s.d.Approvals.Pending())
}

// handleApprovalDecide answers a waiting incoming transfer.
func (s *Server) handleApprovalDecide(accept bool) http.HandlerFunc {
	return func(w http.ResponseWriter, r *http.Request) {
		if s.d.Approvals == nil || !s.d.Approvals.Decide(r.PathValue("id"), accept) {
			http.Error(w, "that request is no longer waiting", http.StatusNotFound)
			return
		}
		s.Notify()
		writeJSON(w, map[string]bool{"ok": true})
	}
}
