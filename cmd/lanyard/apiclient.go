package main

// Typed wrapper over LANyard's loopback API, shared by the native UI (and
// available to the CLI). The engine is unchanged; the native window is just a
// second front-end.

import (
	"net/url"
	"time"
)

func urlQuery(s string) string { return url.QueryEscape(s) }

type apiLifetime struct {
	Type        string     `json:"type"`
	DurationSec int        `json:"duration_sec"`
	ExpiresAt   *time.Time `json:"expires_at"`
}

type apiPeer struct {
	ShortID     string   `json:"short_id"`
	DeviceID    string   `json:"device_id"`
	DeviceLabel string   `json:"device_label"`
	Name        string   `json:"name"`
	OS          string   `json:"os"`
	Addrs       []string `json:"addrs"`
	Port        int      `json:"port"`
	Verified    bool     `json:"verified"`
	Source      string   `json:"source"`
}

type apiShare struct {
	ShareID         string      `json:"share_id"`
	Path            string      `json:"path"`
	Kind            string      `json:"kind"`
	Label           string      `json:"label"`
	Visibility      string      `json:"visibility"`
	Lifetime        apiLifetime `json:"lifetime"`
	CreatedAt       time.Time   `json:"created_at"`
	State           string      `json:"state"`
	EndReason       string      `json:"end_reason"`
	DrainUntil      *time.Time  `json:"drain_until"`
	ActiveTransfers int         `json:"active_transfers"`
}

type apiFile struct {
	Rel   string `json:"rel"`
	Local string `json:"local"`
	State string `json:"state"`
	Size  int64  `json:"size"`
	Done  int64  `json:"done"`
}

type apiTransfer struct {
	ID         string    `json:"id"`
	Direction  string    `json:"direction"`
	PeerID     string    `json:"peer_id"`
	PeerName   string    `json:"peer_name"`
	ShareID    string    `json:"share_id"`
	ShareLabel string    `json:"share_label"`
	State      string    `json:"state"`
	Error      string    `json:"error"`
	Note       string    `json:"note"`
	Total      int64     `json:"total"`
	Done       int64     `json:"done"`
	SpeedMBPS  float64   `json:"speed_mbps"`
	ETASeconds int       `json:"eta_seconds"`
	Files      []apiFile `json:"files"`
	CurrentIdx int       `json:"current_index"`
	FilesTotal int       `json:"files_total"`
	FilesDone  int       `json:"files_done"`
	StartedAt  time.Time `json:"started_at"`
	FinishedAt time.Time `json:"finished_at"`
}

type apiPerms struct {
	Browse       bool  `json:"browse"`
	Push         bool  `json:"push"`
	PushMaxBytes int64 `json:"push_max_bytes"`
	AskOver      int64 `json:"ask_over"`
}

type apiSession struct {
	ID            string   `json:"id"`
	RemoteID      string   `json:"remote_id"`
	Mode          string   `json:"mode"`
	Incoming      bool     `json:"incoming"`
	PeerFP        string   `json:"peer_fp"`
	PeerName      string   `json:"peer_name"`
	PeerDevice    string   `json:"peer_device"`
	Status        string   `json:"status"`
	Error         string   `json:"error"`
	SAS           string   `json:"sas"`
	Granted       apiPerms `json:"granted"`
	Requested     apiPerms `json:"requested"`
	KeepConnected bool     `json:"keep_connected"`
	Offers        []string `json:"offers"`
}

type apiTrust struct {
	DeviceID    string    `json:"device_id"`
	Name        string    `json:"name"`
	Fingerprint string    `json:"cert_fingerprint"`
	Mode        string    `json:"mode"`
	Permissions apiPerms  `json:"permissions"`
	CreatedAt   time.Time `json:"created_at"`
}

type apiSettings struct {
	DeviceName            string `json:"device_name"`
	DeviceIDLabel         string `json:"device_id_label"`
	Fingerprint           string `json:"fingerprint"`
	GeneratedLabel        string `json:"generated_label"`
	Theme                 string `json:"theme"`
	SpeedUnit             string `json:"speed_unit"`
	SoundOnComplete       bool   `json:"sound_on_complete"`
	DefaultDownloadFolder string `json:"default_download_folder"`
	InboxFolder           string `json:"inbox_folder"`
	PeerPort              int    `json:"peer_port"`
	BandwidthLimitMBPS    int    `json:"bandwidth_limit_mbps"`
}

type apiRemoteShare struct {
	ShareID   string     `json:"share_id"`
	Label     string     `json:"label"`
	Name      string     `json:"name"`
	Kind      string     `json:"kind"`
	Size      int64      `json:"size"`
	Lifetime  string     `json:"lifetime"`
	ExpiresAt *time.Time `json:"expires_at"`
}

type apiRemoteEntry struct {
	Name    string    `json:"name"`
	Path    string    `json:"path"`
	IsDir   bool      `json:"is_dir"`
	Size    int64     `json:"size"`
	ModTime time.Time `json:"mtime"`
}

// --- calls (all through cliClient.do) ---

func (c *cliClient) Peers() ([]apiPeer, error) {
	var out []apiPeer
	return out, c.do("GET", "/api/peers", nil, &out)
}

func (c *cliClient) Shares() ([]apiShare, error) {
	var out []apiShare
	return out, c.do("GET", "/api/shares", nil, &out)
}

func (c *cliClient) Transfers() ([]apiTransfer, error) {
	var out []apiTransfer
	return out, c.do("GET", "/api/transfers", nil, &out)
}

func (c *cliClient) Sessions() ([]apiSession, error) {
	var out []apiSession
	return out, c.do("GET", "/api/sessions", nil, &out)
}

func (c *cliClient) Trust() ([]apiTrust, error) {
	var out []apiTrust
	return out, c.do("GET", "/api/trust", nil, &out)
}

func (c *cliClient) Settings() (apiSettings, error) {
	var out apiSettings
	return out, c.do("GET", "/api/settings", nil, &out)
}

func (c *cliClient) SaveSettings(body map[string]any) error {
	return c.do("PUT", "/api/settings", body, nil)
}

func (c *cliClient) RemoteShares(device string) ([]apiRemoteShare, error) {
	var out []apiRemoteShare
	err := c.do("GET", "/api/remote/shares?device="+urlQuery(device), nil, &out)
	return out, err
}

func (c *cliClient) RemoteTree(device, share, path string) ([]apiRemoteEntry, error) {
	var out []apiRemoteEntry
	u := "/api/remote/tree?device=" + urlQuery(device) + "&share=" + urlQuery(share) + "&path=" + urlQuery(path)
	return out, c.do("GET", u, nil, &out)
}

func (c *cliClient) AddShare(path, label, lifetime string, seconds int) (apiShare, error) {
	var out apiShare
	body := map[string]any{"path": path, "label": label, "lifetime": lifetime, "seconds": seconds}
	return out, c.do("POST", "/api/shares", body, &out)
}

func (c *cliClient) StopShare(id string) error {
	return c.do("POST", "/api/shares/"+id+"/stop", map[string]any{}, nil)
}

func (c *cliClient) StopAllShares() error {
	return c.do("POST", "/api/shares/stop-all", map[string]any{}, nil)
}

func (c *cliClient) CancelAll() (map[string]int, error) {
	var out map[string]int
	return out, c.do("POST", "/api/cancel-all", map[string]any{}, &out)
}

func (c *cliClient) StartSession(device, mode string, perms apiPerms, keep bool) (apiSession, error) {
	var out apiSession
	body := map[string]any{"device": device, "mode": mode, "permissions": perms, "keep_connected": keep}
	return out, c.do("POST", "/api/sessions/request", body, &out)
}

func (c *cliClient) SessionAccept(id string, perms apiPerms, keep bool) (apiSession, error) {
	var out apiSession
	body := map[string]any{"permissions": perms, "keep_connected": keep}
	return out, c.do("POST", "/api/sessions/"+id+"/accept", body, &out)
}

func (c *cliClient) SessionConfirm(id string) (apiSession, error) {
	var out apiSession
	return out, c.do("POST", "/api/sessions/"+id+"/confirm", map[string]any{}, &out)
}

func (c *cliClient) SessionRefresh(id string) (apiSession, error) {
	var out apiSession
	return out, c.do("POST", "/api/sessions/"+id+"/refresh", map[string]any{}, &out)
}

func (c *cliClient) SessionClose(id string) error {
	return c.do("POST", "/api/sessions/"+id+"/close", map[string]any{}, nil)
}

func (c *cliClient) SessionOffers(id string, ids []string) (apiSession, error) {
	var out apiSession
	return out, c.do("POST", "/api/sessions/"+id+"/offers", map[string]any{"share_ids": ids}, &out)
}

func (c *cliClient) Unpair(fp string) error {
	return c.do("POST", "/api/trust/"+fp+"/unpair", map[string]any{}, nil)
}

func (c *cliClient) SetPermissions(fp string, p apiPerms) error {
	return c.do("POST", "/api/trust/"+fp+"/permissions", p, nil)
}

func (c *cliClient) CreateTransfer(device, shareID, shareLabel, peerName, dest string, paths []string) error {
	if paths == nil {
		paths = []string{""}
	}
	body := map[string]any{
		"device": device, "share_id": shareID, "share_label": shareLabel,
		"peer_name": peerName, "paths": paths, "dest": dest,
	}
	return c.do("POST", "/api/transfers", body, nil)
}

func (c *cliClient) TransferAction(id, action string) error {
	return c.do("POST", "/api/transfers/"+id+"/"+action, map[string]any{}, nil)
}

func (c *cliClient) CancelTransfer(id string, deletePartials bool) error {
	u := "/api/transfers/" + id + "/cancel"
	if deletePartials {
		u += "?delete=1"
	}
	return c.do("POST", u, map[string]any{}, nil)
}

func (c *cliClient) ClearFinished() error {
	return c.do("POST", "/api/transfers/clear-finished", map[string]any{}, nil)
}

func (c *cliClient) Push(device string, paths []string) error {
	return c.do("POST", "/api/push", map[string]any{"device": device, "paths": paths}, nil)
}

// AddShareTo shares a path visible only to one device (visibility "specific").
func (c *cliClient) AddShareTo(path, deviceID string) (apiShare, error) {
	var out apiShare
	body := map[string]any{
		"path": path, "visibility": "specific", "allowed_devices": []string{deviceID},
		"lifetime": "until_stopped", "seconds": 0,
	}
	return out, c.do("POST", "/api/shares", body, &out)
}
