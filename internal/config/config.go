// Package config holds persistent settings and the per-user data directory.
package config

import (
	"encoding/json"
	"fmt"
	"log/slog"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// WriteFileAtomic retries a rename that failed with a sharing violation (a
// Windows process holds the destination without FILE_SHARE_DELETE) this many
// times, waiting renameRetryDelay between attempts. They are vars so tests can
// shorten them.
var (
	renameRetries    = 5
	renameRetryDelay = 50 * time.Millisecond
)

const (
	DefaultPeerPort   = 47800
	DefaultUIPort     = 47810
	DefaultBeaconPort = 47801
	// MDNSPort is fixed by the mDNS standard; it is listed beside the
	// configurable peer and beacon ports when generating firewall commands.
	MDNSPort = 5353
)

type Settings struct {
	DeviceName string `json:"device_name"`
	// PeerPort is the user-chosen peer TCP port. Zero means "not chosen yet":
	// the app uses DefaultPeerPort and records the port it actually bound so
	// the firewall banner and next start agree. A port the *server* had to
	// fall back to is never written here.
	PeerPort int `json:"peer_port"`
	// BeaconPort is the fallback discovery UDP port (default 47801). Every
	// device on the network must use the same value or fallback discovery
	// between them stops working.
	BeaconPort int `json:"beacon_port,omitempty"`
	UIPort     int `json:"ui_port"`
	// DeviceIDLabel is the user-facing Device ID (§11.1). It is a label; the
	// certificate fingerprint remains the identity. Empty means "use the
	// generated short fingerprint".
	DeviceIDLabel string `json:"device_id_label,omitempty"`
	// Theme is "light", "dark" or "system".
	Theme string `json:"theme,omitempty"`
	// SpeedUnit is "mbs" (MB/s, default) or "mbps".
	SpeedUnit       string `json:"speed_unit,omitempty"`
	SoundOnComplete bool   `json:"sound_on_complete,omitempty"`
	// Notifications shows desktop notifications for an incoming pairing
	// request and for a finished or failed transfer. Absent means on.
	Notifications *bool `json:"notifications,omitempty"`
	// DefaultDownloadFolder prefills the download destination.
	DefaultDownloadFolder string `json:"default_download_folder,omitempty"`
	// InboxFolder is where pushes land; empty means DefaultInboxDir.
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

// DefaultInboxDir is where received pushes land when no Inbox folder is
// configured: a visible "LANyard" folder in the user's home directory, next to
// Downloads, rather than a hidden folder under the data directory.
func DefaultInboxDir() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "LANyard"), nil
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
	s.data = Settings{DeviceName: host, UIPort: DefaultUIPort}
	b, err := os.ReadFile(filepath.Join(dir, "config.json"))
	if err == nil {
		_ = json.Unmarshal(b, &s.data)
	} else if !os.IsNotExist(err) {
		return nil, err
	}
	// PeerPort is deliberately left at 0 when unset: the server records the
	// port it actually bound (only when the user had not chosen one) so a
	// later start and the firewall banner use the same port.
	if s.data.UIPort == 0 {
		s.data.UIPort = DefaultUIPort
	}
	if s.data.BeaconPort == 0 {
		s.data.BeaconPort = DefaultBeaconPort
	}
	if s.data.Theme == "" {
		s.data.Theme = "system"
	}
	if s.data.SpeedUnit == "" {
		s.data.SpeedUnit = "mbs"
	}
	return s, nil
}

// NotificationsEnabled reports whether desktop notifications are on. The
// setting defaults to on when it has never been set.
func (s Settings) NotificationsEnabled() bool {
	return s.Notifications == nil || *s.Notifications
}

// EffectivePeerPort is the peer port this run requests: the user's choice when
// one was made, otherwise the default. PeerPort itself may remain 0 until the
// server records the port it actually bound.
func (s Settings) EffectivePeerPort() int {
	if s.PeerPort > 0 {
		return s.PeerPort
	}
	return DefaultPeerPort
}

// EffectiveBeaconPort is the fallback discovery port, defaulting when unset.
func (s Settings) EffectiveBeaconPort() int {
	if s.BeaconPort > 0 {
		return s.BeaconPort
	}
	return DefaultBeaconPort
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

// WriteFileAtomic writes via temp file + rename so a crash never leaves a torn
// file. When the destination is held without FILE_SHARE_DELETE (Windows), the
// rename is retried briefly; if it still cannot replace the file the error is
// returned and logged, never discarded.
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
		if renameRetries > 0 && isSharingViolation(err) {
			if rerr := retryRename(name, path); rerr == nil {
				return nil
			} else {
				err = rerr
			}
		}
		os.Remove(name)
		slog.Error("config: could not replace file", "path", path, "err", err)
		return fmt.Errorf("replace %s: %w", path, err)
	}
	return nil
}

// retryRename retries a rename that failed with a sharing violation. It stops
// early if the error changes to a non-transient one.
func retryRename(src, dst string) error {
	var err error
	for i := 0; i < renameRetries; i++ {
		time.Sleep(renameRetryDelay)
		if err = os.Rename(src, dst); err == nil {
			return nil
		}
		if !isSharingViolation(err) {
			return err
		}
	}
	return err
}
