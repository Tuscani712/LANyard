package uiserver

import (
	"encoding/json"
	"net/http"
	"regexp"
	"strings"

	"lanyard/internal/config"
	"lanyard/internal/mount"
)

var driveLetter = regexp.MustCompile(`^[A-Za-z]:$`)

func (s *Server) handleMounts(w http.ResponseWriter, r *http.Request) {
	if s.d.Mounts == nil {
		writeJSON(w, []struct{}{})
		return
	}
	writeJSON(w, s.d.Mounts.List())
}

// handleDriveLetters lists the free drive letters on Windows (empty elsewhere),
// so the UI can offer a picker instead of asking the person to type one.
func (s *Server) handleDriveLetters(w http.ResponseWriter, r *http.Request) {
	letters := freeDriveLetters()
	if letters == nil {
		letters = []string{}
	}
	writeJSON(w, map[string]any{"letters": letters})
}

// handleMountAdd serves a paired device as a drive and, if a drive letter was
// given and the OS supports it, asks the OS to mount it.
func (s *Server) handleMountAdd(w http.ResponseWriter, r *http.Request) {
	if s.d.Mounts == nil || s.d.Trust == nil {
		http.Error(w, "mounting is not available", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		Device string `json:"device"`
		Drive  string `json:"drive"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 2048)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}
	req.Drive = strings.TrimSpace(req.Drive)
	if req.Drive != "" && !driveLetter.MatchString(req.Drive) {
		http.Error(w, "the drive must be a letter followed by a colon, like Z:", http.StatusBadRequest)
		return
	}
	e, ok := s.d.Trust.Entry(req.Device)
	if !ok {
		http.Error(w, "only paired devices can be mounted", http.StatusBadRequest)
		return
	}
	info, err := s.d.Mounts.Add(e.Fingerprint, firstNonEmpty(e.Name, "Device"))
	if err != nil {
		http.Error(w, err.Error(), http.StatusBadRequest)
		return
	}
	if req.Drive != "" {
		if err := mount.MountOS(req.Drive, info.URL); err != nil {
			s.d.Mounts.SetOSState(info.ID, "", false, err.Error())
		} else {
			s.d.Mounts.SetOSState(info.ID, strings.ToUpper(req.Drive), true, "")
		}
	}
	s.saveMountPrefs()
	s.Notify()
	for _, m := range s.d.Mounts.List() {
		if m.ID == info.ID {
			writeJSON(w, m)
			return
		}
	}
	writeJSON(w, info)
}

func (s *Server) handleMountRemove(w http.ResponseWriter, r *http.Request) {
	if s.d.Mounts == nil {
		http.Error(w, "mounting is not available", http.StatusServiceUnavailable)
		return
	}
	info, ok := s.d.Mounts.Remove(r.PathValue("id"))
	if !ok {
		http.Error(w, "no such mount", http.StatusNotFound)
		return
	}
	if info.Drive != "" {
		_ = mount.UnmountOS(info.Drive)
	}
	s.saveMountPrefs()
	s.Notify()
	writeJSON(w, map[string]bool{"ok": true})
}

// dropMountsOf removes the mounts of a device that was just unpaired.
func (s *Server) dropMountsOf(fp string) {
	if s.d.Mounts == nil {
		return
	}
	for _, in := range s.d.Mounts.RemoveDevice(fp) {
		if in.Drive != "" {
			_ = mount.UnmountOS(in.Drive)
		}
	}
	s.saveMountPrefs()
	s.Notify()
}

// saveMountPrefs remembers the active mounts so they come back after a restart.
func (s *Server) saveMountPrefs() {
	if s.d.Cfg == nil || s.d.Mounts == nil {
		return
	}
	var prefs []config.MountPref
	for _, m := range s.d.Mounts.List() {
		prefs = append(prefs, config.MountPref{DeviceID: m.DeviceID, Name: m.Name, Drive: m.Drive})
	}
	_ = s.d.Cfg.Update(func(st *config.Settings) { st.Mounts = prefs })
}
