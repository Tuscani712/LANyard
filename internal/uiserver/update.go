package uiserver

import (
	"context"
	"net/http"
	"os"
	"time"

	"lanyard/internal/update"
)

// updateURL is the configured manifest, falling back to the built-in channel
// (empty until the release channel is live).
func (s *Server) updateURL() string {
	if s.d.Cfg != nil {
		if u := s.d.Cfg.Get().UpdateURL; u != "" {
			return u
		}
	}
	return update.DefaultManifestURL
}

func updateClient() *http.Client { return &http.Client{Timeout: 16 * time.Minute} }

// handleUpdateStatus reports the running version and whether a channel is set.
func (s *Server) handleUpdateStatus(w http.ResponseWriter, r *http.Request) {
	u := s.updateURL()
	auto := false
	if s.d.Cfg != nil {
		auto = s.d.Cfg.Get().AutoUpdate
	}
	writeJSON(w, map[string]any{
		"current":     s.d.Self().Version,
		"platform":    update.Platform(),
		"configured":  u != "",
		"auto_update": auto,
	})
}

// handleUpdateCheck fetches the manifest and compares versions. It never
// downloads anything.
func (s *Server) handleUpdateCheck(w http.ResponseWriter, r *http.Request) {
	cur := s.d.Self().Version
	u := s.updateURL()
	if u == "" {
		writeJSON(w, update.Result{Configured: false, Current: cur, Platform: update.Platform()})
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 45*time.Second)
	defer cancel()
	res, err := update.Check(ctx, updateClient(), u, cur)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, res)
}

// handleUpdateDownload checks, then downloads the newer build and stages it
// next to the executable after verifying its hash/signature. It is applied on
// the next start.
func (s *Server) handleUpdateDownload(w http.ResponseWriter, r *http.Request) {
	cur := s.d.Self().Version
	u := s.updateURL()
	if u == "" {
		http.Error(w, "updates are not configured", http.StatusNotImplemented)
		return
	}
	exe, err := os.Executable()
	if err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	ctx, cancel := context.WithTimeout(r.Context(), 16*time.Minute)
	defer cancel()
	res, err := update.Check(ctx, updateClient(), u, cur)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	if !res.Available {
		writeJSON(w, map[string]any{"staged": false, "result": res})
		return
	}
	st, err := update.StageDownload(ctx, updateClient(), exe, res, update.EmbeddedPublicKey)
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadGateway)
		return
	}
	writeJSON(w, map[string]any{"staged": true, "version": st.Version, "result": res})
}
