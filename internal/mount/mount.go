// Package mount lets the operating system mount a paired device's shares as a
// drive (spec §11.2). LANyard runs a loopback-only, read-only WebDAV server whose
// every request is answered from the peer's authenticated share API, and the
// OS's own WebDAV client mounts it: Windows "net use", macOS mount_webdav,
// Linux davfs2. Nothing is cached on disk and nothing new is exposed to the
// network: the endpoint only listens on 127.0.0.1 and its URL contains a random
// per-mount secret.
package mount

import (
	"context"
	"crypto/rand"
	"crypto/subtle"
	"encoding/hex"
	"errors"
	"fmt"
	"log/slog"
	"net"
	"net/http"
	"os/exec"
	"regexp"
	"runtime"
	"sort"
	"strings"
	"sync"
	"time"

	"golang.org/x/net/webdav"
)

// Info describes one active mount for the UI.
type Info struct {
	ID       string `json:"id"`
	DeviceID string `json:"device_id"` // the peer's certificate fingerprint
	Name     string `json:"name"`
	URL      string `json:"url"`
	Drive    string `json:"drive,omitempty"`    // Windows drive letter or macOS/Linux mount point, if the OS mount was requested
	Mounted  bool   `json:"mounted"`            // the OS mount command succeeded
	Hint     string `json:"hint"`               // how to mount it by hand
	OSError  string `json:"os_error,omitempty"` // why the automatic OS mount did not work
}

type mount struct {
	Info
	secret string
	fs     *remoteFS
}

// Manager owns the loopback server and the active mounts.
type Manager struct {
	api     api
	resolve func(fp string) (host string, port int, ok bool)
	paired  func(fp string) bool
	log     *slog.Logger

	mu     sync.Mutex
	mounts map[string]*mount
	ln     net.Listener
	srv    *http.Server
	port   int
}

// New creates a manager. resolve finds a peer's current address; paired says
// whether a certificate is (still) a paired device.
func New(a api, resolve func(fp string) (string, int, bool), paired func(fp string) bool, log *slog.Logger) *Manager {
	return &Manager{api: a, resolve: resolve, paired: paired, log: log, mounts: map[string]*mount{}}
}

var driveRE = regexp.MustCompile(`^[A-Za-z]:$`)

// Add starts serving a paired device. Only paired devices can be mounted:
// Connect sessions are one-off and cannot be (spec §11.2).
func (m *Manager) Add(fp, name string) (*Info, error) {
	if !m.paired(fp) {
		return nil, errors.New("only paired devices can be mounted")
	}
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, x := range m.mounts {
		if x.DeviceID == fp {
			c := x.Info
			return &c, nil // already mounted: same endpoint
		}
	}
	if err := m.startLocked(); err != nil {
		return nil, err
	}
	b := make([]byte, 24)
	_, _ = rand.Read(b)
	secret := hex.EncodeToString(b)
	id := "m_" + hex.EncodeToString(b[:5])
	mt := &mount{
		secret: secret,
		fs:     newRemoteFS(m.api, fp, func() (string, int, bool) { return m.resolve(fp) }),
	}
	mt.Info = Info{ID: id, DeviceID: fp, Name: name}
	mt.URL = fmt.Sprintf("http://127.0.0.1:%d/%s/", m.port, secret)
	mt.Hint = Hint(runtime.GOOS, mt.URL)
	m.mounts[id] = mt
	c := mt.Info
	return &c, nil
}

// startLocked starts the shared loopback server on first use.
func (m *Manager) startLocked() error {
	if m.srv != nil {
		return nil
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return err
	}
	m.ln, m.port = ln, ln.Addr().(*net.TCPAddr).Port
	m.srv = &http.Server{Handler: http.HandlerFunc(m.serve), ReadHeaderTimeout: 10 * time.Second}
	go func() { _ = m.srv.Serve(ln) }()
	return nil
}

func (m *Manager) find(path string) (*mount, bool) {
	trim := strings.TrimPrefix(path, "/")
	seg, _, _ := strings.Cut(trim, "/")
	m.mu.Lock()
	defer m.mu.Unlock()
	for _, x := range m.mounts {
		if subtle.ConstantTimeCompare([]byte(seg), []byte(x.secret)) == 1 {
			return x, true
		}
	}
	return nil, false
}

// serve is the WebDAV endpoint. Anything but read-only methods is refused, the
// secret path is required, and a device that is no longer paired drops its
// mount at once (unpairing revokes the drive).
func (m *Manager) serve(w http.ResponseWriter, r *http.Request) {
	if h := r.Host; !strings.HasPrefix(h, "127.0.0.1:") && !strings.HasPrefix(h, "localhost:") {
		http.Error(w, "forbidden host", http.StatusForbidden)
		return
	}
	if r.URL.Path == "/" || r.URL.Path == "" {
		serveRoot(w, r)
		return
	}
	mt, ok := m.find(r.URL.Path)
	if !ok {
		http.NotFound(w, r)
		return
	}
	if !m.paired(mt.DeviceID) {
		m.Remove(mt.ID)
		http.Error(w, "this device is no longer paired", http.StatusForbidden)
		return
	}
	switch r.Method {
	case http.MethodGet, http.MethodHead, http.MethodOptions, "PROPFIND":
	default:
		w.Header().Set("Allow", "OPTIONS, GET, HEAD, PROPFIND")
		http.Error(w, "this drive is read-only", http.StatusMethodNotAllowed)
		return
	}
	h := &webdav.Handler{
		Prefix:     "/" + mt.secret,
		FileSystem: mt.fs,
		LockSystem: webdav.NewMemLS(),
	}
	h.ServeHTTP(w, r)
}

// serveRoot answers the probes some WebDAV clients (Windows' WebClient among
// them) send to the server root before using a deeper path. It reveals nothing:
// a single empty collection, never the secret path or any mount.
func serveRoot(w http.ResponseWriter, r *http.Request) {
	w.Header().Set("DAV", "1, 2")
	switch r.Method {
	case http.MethodOptions:
		w.Header().Set("Allow", "OPTIONS, GET, HEAD, PROPFIND")
		w.Header().Set("MS-Author-Via", "DAV")
		w.WriteHeader(http.StatusOK)
	case "PROPFIND":
		w.Header().Set("Content-Type", "application/xml; charset=utf-8")
		w.WriteHeader(http.StatusMultiStatus)
		_, _ = w.Write([]byte(`<?xml version="1.0" encoding="utf-8"?>` +
			`<D:multistatus xmlns:D="DAV:"><D:response><D:href>/</D:href><D:propstat><D:prop>` +
			`<D:resourcetype><D:collection/></D:resourcetype></D:prop><D:status>HTTP/1.1 200 OK</D:status>` +
			`</D:propstat></D:response></D:multistatus>`))
	default:
		http.NotFound(w, r)
	}
}

// Remove stops serving a mount (the OS mount, if any, is removed by the caller).
func (m *Manager) Remove(id string) (Info, bool) {
	m.mu.Lock()
	defer m.mu.Unlock()
	mt, ok := m.mounts[id]
	if !ok {
		return Info{}, false
	}
	delete(m.mounts, id)
	return mt.Info, true
}

// Sweep drops mounts whose device is no longer paired and returns them so the
// caller can remove the matching OS mount.
func (m *Manager) Sweep() []Info {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Info
	for id, mt := range m.mounts {
		if !m.paired(mt.DeviceID) {
			out = append(out, mt.Info)
			delete(m.mounts, id)
		}
	}
	return out
}

// RemoveDevice drops the mount of a device (it was unpaired).
func (m *Manager) RemoveDevice(fp string) []Info {
	m.mu.Lock()
	defer m.mu.Unlock()
	var out []Info
	for id, mt := range m.mounts {
		if mt.DeviceID == fp {
			out = append(out, mt.Info)
			delete(m.mounts, id)
		}
	}
	return out
}

// List returns the active mounts.
func (m *Manager) List() []Info {
	m.mu.Lock()
	defer m.mu.Unlock()
	out := make([]Info, 0, len(m.mounts))
	for _, mt := range m.mounts {
		out = append(out, mt.Info)
	}
	sort.Slice(out, func(i, j int) bool { return out[i].Name < out[j].Name })
	return out
}

// SetOSState records the outcome of the automatic OS mount.
func (m *Manager) SetOSState(id, drive string, mounted bool, osErr string) {
	m.mu.Lock()
	defer m.mu.Unlock()
	if mt, ok := m.mounts[id]; ok {
		mt.Drive, mt.Mounted, mt.OSError = drive, mounted, osErr
	}
}

// Close shuts the server down.
func (m *Manager) Close() {
	m.mu.Lock()
	srv := m.srv
	m.srv, m.mounts = nil, map[string]*mount{}
	m.mu.Unlock()
	if srv != nil {
		ctx, cancel := context.WithTimeout(context.Background(), 2*time.Second)
		defer cancel()
		_ = srv.Shutdown(ctx)
	}
}

// Hint is what a person can run to mount url by hand on goos.
func Hint(goos, url string) string {
	switch goos {
	case "windows":
		return "net use Z: " + url + " /persistent:no"
	case "darwin":
		return "mkdir -p ~/LANyard-drive && mount_webdav -S " + url + " ~/LANyard-drive"
	default:
		return "sudo mount -t davfs " + url + " /mnt/lanyard   (needs the davfs2 package; or use your file manager's \"Connect to server\")"
	}
}

// MountOS asks the operating system to mount url. Only Windows is automatic
// ("net use", no administrator rights for a non-persistent mapping); elsewhere
// mounting needs root or a file-manager step, so the hint is shown instead.
func MountOS(drive, url string) error {
	if runtime.GOOS != "windows" {
		return errors.New("automatic mounting is only available on Windows; use the command shown")
	}
	if !driveRE.MatchString(drive) {
		return errors.New("a drive letter like Z: is required")
	}
	if webClientStopped() {
		//lint:ignore ST1005 shown to the user verbatim; starts with a proper noun
		return errors.New("Windows' WebClient service is not running, and Windows needs it to mount web folders. " +
			"Start it once (Services -> WebClient -> Start, or `sc start WebClient` in an administrator prompt; set it to Automatic to keep it) and try again")
	}
	out, err := exec.Command("net", "use", strings.ToUpper(drive), url, "/persistent:no").CombinedOutput()
	if err != nil {
		return fmt.Errorf("net use failed: %s", strings.TrimSpace(string(out)))
	}
	return nil
}

// webClientStopped reports whether the Windows WebClient service (which
// implements web-folder mounting) is installed but not running.
func webClientStopped() bool {
	out, err := exec.Command("sc", "query", "WebClient").CombinedOutput()
	if err != nil {
		return false // unknown: let net use try and report its own error
	}
	text := strings.ToUpper(string(out))
	return strings.Contains(text, "STOPPED") || strings.Contains(text, "STOP_PENDING")
}

// UnmountOS removes a drive mapping created by MountOS.
func UnmountOS(drive string) error {
	if runtime.GOOS != "windows" || !driveRE.MatchString(drive) {
		return nil
	}
	out, err := exec.Command("net", "use", strings.ToUpper(drive), "/delete", "/y").CombinedOutput()
	if err != nil {
		return fmt.Errorf("net use /delete failed: %s", strings.TrimSpace(string(out)))
	}
	return nil
}
