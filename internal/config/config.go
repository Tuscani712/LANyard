// Package config holds persistent settings and the per-user data directory.
package config

import (
	"encoding/json"
	"os"
	"path/filepath"
	"sync"
)

const (
	DefaultPeerPort = 47800
	DefaultUIPort   = 47810
	BeaconPort      = 47801
)

type Settings struct {
	DeviceName string `json:"device_name"`
	PeerPort   int    `json:"peer_port"`
	UIPort     int    `json:"ui_port"`
	// DeviceIDLabel is the user-facing Device ID (§11.1). It is a label; the
	// certificate fingerprint remains the identity. Empty means "use the
	// generated short fingerprint".
	DeviceIDLabel string `json:"device_id_label,omitempty"`
	// Theme is "light", "dark" or "system".
	Theme string `json:"theme,omitempty"`
	// SpeedUnit is "mbs" (MB/s, default) or "mbps".
	SpeedUnit       string `json:"speed_unit,omitempty"`
	SoundOnComplete bool   `json:"sound_on_complete,omitempty"`
	// DefaultDownloadFolder prefills the download destination.
	DefaultDownloadFolder string `json:"default_download_folder,omitempty"`
	// InboxFolder is where pushes land; empty means <data-dir>/Inbox.
	InboxFolder string `json:"inbox_folder,omitempty"`
	// BandwidthLimitMBps caps transfer throughput; 0 means unlimited.
	BandwidthLimitMBps int `json:"bandwidth_limit_mbps,omitempty"`
	// Mounts are the paired devices to expose as drives again at startup (§11.2).
	Mounts []MountPref `json:"mounts,omitempty"`
	// StartOnLogin starts LANyard when the user signs in (spec §11).
	StartOnLogin bool `json:"start_on_login,omitempty"`
	// MinimizeToTray hides the window to the system tray when it is minimized
	// or closed, instead of using the taskbar / quitting (Windows).
	MinimizeToTray bool `json:"minimize_to_tray,omitempty"`
	// UpdateURL is the https manifest for the release channel. Empty means
	// updates are disabled (the channel is not live yet).
	UpdateURL string `json:"update_url,omitempty"`
	// AutoUpdate checks the release channel shortly after start and stages a
	// newer, verified build for the next launch.
	AutoUpdate bool `json:"auto_update,omitempty"`

	// Shares is owned by internal/shares and kept as an opaque blob so this
	// package never needs to import it (avoids an import cycle).
	Shares json.RawMessage `json:"shares,omitempty"`
	// Transfers is owned by internal/transfer (unfinished jobs).
	Transfers json.RawMessage `json:"transfers,omitempty"`
	// Trust is owned by internal/trust (paired devices, §3.2).
	Trust json.RawMessage `json:"trust,omitempty"`
}

// MountPref remembers a "mount as share drive" choice across restarts.
type MountPref struct {
	DeviceID string `json:"device_id"` // the peer's certificate fingerprint
	Name     string `json:"name"`
	Drive    string `json:"drive,omitempty"` // e.g. "Z:"; empty = address only
}

type Store struct {
	dir  string
	mu   sync.Mutex
	data Settings
}

// DefaultDir returns the per-user data directory.
func DefaultDir() (string, error) {
	base, err := os.UserConfigDir()
	if err != nil {
		return "", err
	}
	dir := filepath.Join(base, "LANyard")
	// One-time migration from the pre-rename data directory (keeps the
	// device identity, trust store and shares).
	legacy := filepath.Join(base, "EZ-Share")
	if _, err := os.Stat(dir); os.IsNotExist(err) {
		if _, lerr := os.Stat(legacy); lerr == nil {
			_ = os.Rename(legacy, dir)
		}
	}
	return dir, nil
}

func Open(dir string) (*Store, error) {
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return nil, err
	}
	s := &Store{dir: dir}
	host, _ := os.Hostname()
	if host == "" {
		host = "lanyard-device"
	}
	s.data = Settings{DeviceName: host, PeerPort: DefaultPeerPort, UIPort: DefaultUIPort}
	b, err := os.ReadFile(filepath.Join(dir, "config.json"))
	if err == nil {
		_ = json.Unmarshal(b, &s.data)
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	if s.data.PeerPort == 0 {
		s.data.PeerPort = DefaultPeerPort
	}
	if s.data.UIPort == 0 {
		s.data.UIPort = DefaultUIPort
	}
	if s.data.Theme == "" {
		s.data.Theme = "system"
	}
	if s.data.SpeedUnit == "" {
		s.data.SpeedUnit = "mbs"
	}
	return s, nil
}

func (s *Store) Dir() string { return s.dir }

func (s *Store) Get() Settings {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.data
}

func (s *Store) Update(fn func(*Settings)) error {
	s.mu.Lock()
	defer s.mu.Unlock()
	fn(&s.data)
	return WriteFileAtomic(filepath.Join(s.dir, "config.json"), mustJSON(s.data), 0o600)
}

func mustJSON(v any) []byte {
	b, _ := json.MarshalIndent(v, "", "  ")
	return b
}

// WriteFileAtomic writes via temp file + rename so a crash never leaves a torn file.
func WriteFileAtomic(path string, data []byte, perm os.FileMode) error {
	tmp, err := os.CreateTemp(filepath.Dir(path), ".tmp-*")
	if err != nil {
		return err
	}
	name := tmp.Name()
	if _, err := tmp.Write(data); err != nil {
		tmp.Close()
		os.Remove(name)
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		os.Remove(name)
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(name)
		return err
	}
	_ = os.Chmod(name, perm)
	if err := os.Rename(name, path); err != nil {
		os.Remove(name)
		return err
	}
	return nil
}
