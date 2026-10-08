package uiserver

import (
	"encoding/json"
	"net/http"
	"runtime"
	"strings"
	"unicode"

	"lanyard/internal/config"
	"lanyard/internal/trust"
)

type settingsView struct {
	DeviceName            string        `json:"device_name"`
	DeviceIDLabel         string        `json:"device_id_label"`
	Fingerprint           string        `json:"fingerprint"`
	GeneratedLabel        string        `json:"generated_label"`
	Theme                 string        `json:"theme"`
	SpeedUnit             string        `json:"speed_unit"`
	SoundOnComplete       bool          `json:"sound_on_complete"`
	Notifications         bool          `json:"notifications"`
	DefaultDownloadFolder string        `json:"default_download_folder"`
	InboxFolder           string        `json:"inbox_folder"`
	PeerPort              int           `json:"peer_port"`
	BandwidthLimitMBps    int           `json:"bandwidth_limit_mbps"`
	StartOnLogin          bool          `json:"start_on_login"`
	MinimizeToTray        bool          `json:"minimize_to_tray"`
	TraySupported         bool          `json:"tray_supported"`
	TrayReason            string        `json:"tray_reason"`
	Version               string        `json:"version"`
	UpdateURL             string        `json:"update_url"`
	AutoUpdate            bool          `json:"auto_update"`
	Paired                []trust.Entry `json:"paired"`

	// Warning carries a non-fatal problem from a settings PUT (for example the
	// OS sign-in entry could not be changed). Empty on success.
	Warning string `json:"warning,omitempty"`
}

// TrayProbe is set by the native app to report whether a system tray is really
// available (and why not). Nil falls back to a per-platform default.
var TrayProbe func() (bool, string)

func traySupported() (bool, string) {
	if TrayProbe != nil {
		return TrayProbe()
	}
	if runtime.GOOS == "windows" {
		return true, ""
	}
	return false, "The system tray is not available in this mode."
}

func (s *Server) settingsView() settingsView {
	st := s.d.Cfg.Get()
	self := s.d.Self()
	generated := self.GeneratedLabel
	if generated == "" {
		generated = self.DeviceID[:16]
	}
	v := settingsView{
		DeviceName: st.DeviceName, DeviceIDLabel: st.DeviceIDLabel,
		Fingerprint: self.DeviceID, GeneratedLabel: generated,
		Theme: st.Theme, SpeedUnit: st.SpeedUnit, SoundOnComplete: st.SoundOnComplete,
		Notifications:         st.NotificationsEnabled(),
		DefaultDownloadFolder: st.DefaultDownloadFolder, InboxFolder: st.InboxFolder,
		PeerPort: st.PeerPort, BandwidthLimitMBps: st.BandwidthLimitMBps,
	}
	v.StartOnLogin = st.StartOnLogin
	v.MinimizeToTray = st.MinimizeToTray
	// Show the folder pushes actually land in: the configured one, or the
	// default (~/LANyard) the manager resolved at startup.
	if st.InboxFolder == "" && s.d.Inbox != nil {
		v.InboxFolder = s.d.Inbox.Dir()
	}
	v.TraySupported, v.TrayReason = traySupported()
	v.Version = self.Version
	v.UpdateURL = st.UpdateURL
	v.AutoUpdate = st.AutoUpdate
	if s.d.StartOnLoginEnabled != nil {
		v.StartOnLogin = s.d.StartOnLoginEnabled() // what the OS really has
	}
	if s.d.Trust != nil {
		v.Paired = s.d.Trust.Paired()
	}
	return v
}

func (s *Server) handleSettingsGet(w http.ResponseWriter, r *http.Request) {
	if s.d.Cfg == nil {
		http.Error(w, "settings unavailable", http.StatusServiceUnavailable)
		return
	}
	writeJSON(w, s.settingsView())
}

func (s *Server) handleSettingsPut(w http.ResponseWriter, r *http.Request) {
	if s.d.Cfg == nil {
		http.Error(w, "settings unavailable", http.StatusServiceUnavailable)
		return
	}
	var req struct {
		DeviceName            *string `json:"device_name"`
		DeviceIDLabel         *string `json:"device_id_label"`
		Theme                 *string `json:"theme"`
		SpeedUnit             *string `json:"speed_unit"`
		SoundOnComplete       *bool   `json:"sound_on_complete"`
		Notifications         *bool   `json:"notifications"`
		DefaultDownloadFolder *string `json:"default_download_folder"`
		InboxFolder           *string `json:"inbox_folder"`
		PeerPort              *int    `json:"peer_port"`
		BandwidthLimitMBps    *int    `json:"bandwidth_limit_mbps"`
		StartOnLogin          *bool   `json:"start_on_login"`
		MinimizeToTray        *bool   `json:"minimize_to_tray"`
		UpdateURL             *string `json:"update_url"`
		AutoUpdate            *bool   `json:"auto_update"`
		ConfirmDeviceID       bool    `json:"confirm_device_id"`
	}
	if err := json.NewDecoder(http.MaxBytesReader(w, r.Body, 16384)).Decode(&req); err != nil {
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	cur := s.d.Cfg.Get()
	next := cur
	if req.DeviceName != nil {
		name := cleanText(*req.DeviceName, 64)
		if name == "" {
			http.Error(w, "device name cannot be empty", http.StatusBadRequest)
			return
		}
		next.DeviceName = name
	}
	if req.DeviceIDLabel != nil {
		label := cleanText(*req.DeviceIDLabel, 32)
		if label != "" && !validDeviceLabel(label) {
			http.Error(w, "device ID must be 3-32 characters of A-Z a-z 0-9 _ - .", http.StatusBadRequest)
			return
		}
		if label != "" && !req.ConfirmDeviceID {
			if who := s.labelConflict(label); who != "" {
				w.Header().Set("Content-Type", "application/json")
				w.WriteHeader(http.StatusConflict)
				_ = json.NewEncoder(w).Encode(map[string]string{"warning": "That Device ID is already used by " + who + ". Use it anyway?"})
				return
			}
		}
		next.DeviceIDLabel = label
	}
	if req.Theme != nil {
		if !oneOf(*req.Theme, "light", "dark", "system") {
			http.Error(w, "theme must be light, dark or system", http.StatusBadRequest)
			return
		}
		next.Theme = *req.Theme
	}
	if req.SpeedUnit != nil {
		if !oneOf(*req.SpeedUnit, "mbs", "mbps") {
			http.Error(w, "speed unit must be mbs or mbps", http.StatusBadRequest)
			return
		}
		next.SpeedUnit = *req.SpeedUnit
	}
	if req.SoundOnComplete != nil {
		next.SoundOnComplete = *req.SoundOnComplete
	}
	if req.Notifications != nil {
		next.Notifications = req.Notifications
	}
	if req.DefaultDownloadFolder != nil {
		next.DefaultDownloadFolder = cleanPath(*req.DefaultDownloadFolder)
	}
	if req.InboxFolder != nil {
		next.InboxFolder = cleanPath(*req.InboxFolder)
	}
	if req.PeerPort != nil {
		if *req.PeerPort < 1024 || *req.PeerPort > 65535 {
			http.Error(w, "peer port must be 1024-65535", http.StatusBadRequest)
			return
		}
		next.PeerPort = *req.PeerPort
	}
	if req.BandwidthLimitMBps != nil {
		if *req.BandwidthLimitMBps < 0 || *req.BandwidthLimitMBps > 100000 {
			http.Error(w, "bandwidth limit must be 0 (unlimited) or a positive MB/s value", http.StatusBadRequest)
			return
		}
		next.BandwidthLimitMBps = *req.BandwidthLimitMBps
	}

	if req.MinimizeToTray != nil {
		next.MinimizeToTray = *req.MinimizeToTray
	}
	if req.UpdateURL != nil {
		u := strings.TrimSpace(*req.UpdateURL)
		if u != "" && !strings.HasPrefix(u, "https://") {
			http.Error(w, "the update URL must use https", http.StatusBadRequest)
			return
		}
		next.UpdateURL = cleanText(u, 512)
	}
	if req.AutoUpdate != nil {
		next.AutoUpdate = *req.AutoUpdate
	}
	warning := ""
	if req.StartOnLogin != nil {
		if s.d.SetStartOnLogin == nil {
			http.Error(w, "start on login is not available here", http.StatusNotImplemented)
			return
		}
		// Only touch the OS sign-in entry when the value actually changes, so a
		// normal save never writes (or can fail on) the autostart files.
		if *req.StartOnLogin != next.StartOnLogin {
			if err := s.d.SetStartOnLogin(*req.StartOnLogin); err != nil {
				// Non-fatal: keep every other requested setting, leave
				// start_on_login at its previous value, and surface the failure as a
				// warning instead of discarding the whole save. Never silent.
				if s.d.Log != nil {
					s.d.Log.Warn("settings: could not change start on login", "err", err, "enable", *req.StartOnLogin)
				}
				warning = "could not change start on login: " + err.Error()
			} else {
				next.StartOnLogin = *req.StartOnLogin
			}
		}
	}

	if err := s.d.Cfg.Update(func(st *config.Settings) { *st = next }); err != nil {
		http.Error(w, err.Error(), http.StatusInternalServerError)
		return
	}
	if s.d.ApplySettings != nil {
		s.d.ApplySettings(next)
	}
	s.Notify()
	view := s.settingsView()
	view.Warning = warning
	writeJSON(w, view)
}

// handleCancelAll (§11.3) is the hard stop: every share ends at once and all
// in-flight transfers in both directions are cancelled immediately.
func (s *Server) handleCancelAll(w http.ResponseWriter, r *http.Request) {
	stopped := 0
	if s.d.Shares != nil {
		stopped = s.d.Shares.StopAll()
	}
	cancelled := 0
	if s.d.Transfers != nil {
		cancelled = s.d.Transfers.CancelAll()
	}
	writeJSON(w, map[string]int{"shares_stopped": stopped, "transfers_cancelled": cancelled})
}

// labelConflict returns a human name if the Device ID label is already in use
// by a paired device or a device currently in discovery.
func (s *Server) labelConflict(label string) string {
	low := strings.ToLower(label)
	if s.d.Trust != nil {
		for _, e := range s.d.Trust.Paired() {
			if strings.ToLower(e.DeviceID) == low {
				return firstNonEmpty(e.Name, "a paired device")
			}
		}
	}
	for _, p := range s.d.Peers() {
		if strings.ToLower(p.DeviceLabel) == low {
			return firstNonEmpty(p.Name, "a nearby device")
		}
	}
	return ""
}

func validDeviceLabel(s string) bool {
	if len(s) < 3 || len(s) > 32 {
		return false
	}
	for _, r := range s {
		switch {
		case r >= 'A' && r <= 'Z', r >= 'a' && r <= 'z', r >= '0' && r <= '9':
		case r == '_' || r == '-' || r == '.':
		default:
			return false
		}
	}
	return true
}

func oneOf(v string, opts ...string) bool {
	for _, o := range opts {
		if v == o {
			return true
		}
	}
	return false
}

func cleanText(s string, max int) string {
	s = strings.Map(func(r rune) rune {
		if unicode.IsControl(r) {
			return -1
		}
		return r
	}, s)
	s = strings.TrimSpace(s)
	if len(s) > max {
		s = s[:max]
	}
	return s
}

func cleanPath(s string) string {
	return cleanText(s, 512)
}
