package mount

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"io"
	"log/slog"
	"math/rand"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync/atomic"
	"testing"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
	"lanyard/internal/trust"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

// gate is an Authorizer the test can revoke, like the sharer unpairing us.
type gate struct{ revoked *atomic.Bool }

func (g gate) Access(string) trust.Access {
	if g.revoked.Load() {
		return trust.Access{}
	}
	return trust.Access{Paired: true, Browse: true, Push: true}
}

type rig struct {
	revokedRef *atomic.Bool
	m          *Manager
	info       *Info
	fp         string
	paired     atomic.Bool
	share      *shares.Share
	one        *shares.Share
	data       []byte
	httpc      *http.Client
	shMgr      *shares.Manager
	srcDir     string
	upCalls    atomic.Int64
}

func newRig(t *testing.T) *rig {
	t.Helper()
	id, err := identity.LoadOrCreate(t.TempDir(), "peer")
	if err != nil {
		t.Fatal(err)
	}
	cfg, _ := config.Open(t.TempDir())
	shMgr := shares.New(cfg, id.DeviceID, nil)
	t.Cleanup(func() { shMgr.StopAll() })

	src := t.TempDir()
	data := make([]byte, 3<<20)
	rand.New(rand.NewSource(7)).Read(data)
	must(t, os.MkdirAll(filepath.Join(src, "docs", "deep"), 0o755))
	must(t, os.WriteFile(filepath.Join(src, "big.bin"), data, 0o644))
	must(t, os.WriteFile(filepath.Join(src, "docs", "readme.txt"), []byte("hello drive"), 0o644))
	must(t, os.WriteFile(filepath.Join(src, "docs", "deep", "x.txt"), []byte("x"), 0o644))
	folder, err := shMgr.Add(src, shares.AddOptions{LifetimeType: shares.LifetimeUntilStopped, Label: "Projects"})
	must(t, err)
	fileDir := t.TempDir()
	must(t, os.WriteFile(filepath.Join(fileDir, "report.pdf"), []byte("%PDF-fake"), 0o644))
	one, err := shMgr.Add(filepath.Join(fileDir, "report.pdf"), shares.AddOptions{LifetimeType: shares.LifetimeUntilStopped, Label: "Report"})
	must(t, err)

	var revokedFlag atomic.Bool
	srv := peerapi.NewServer(id, func() discovery.Hello { return discovery.Hello{} }, shMgr, nil, gate{revoked: &revokedFlag}, quiet)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	must(t, err)
	go srv.Serve(ln)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		srv.Shutdown(ctx)
	})
	port := ln.Addr().(*net.TCPAddr).Port

	r := &rig{fp: id.DeviceID, share: folder, one: one, data: data, shMgr: shMgr, srcDir: src}
	r.paired.Store(true)
	r.revokedRef = &revokedFlag
	client := peerapi.NewClient(id)
	r.m = New(client, func(fp string) (string, int, bool) {
		r.upCalls.Add(1)
		return "127.0.0.1", port, true
	}, func(fp string) bool { return r.paired.Load() }, quiet)
	t.Cleanup(r.m.Close)
	info, err := r.m.Add(id.DeviceID, "Peer PC")
	must(t, err)
	r.info = info
	r.httpc = &http.Client{Timeout: 10 * time.Second}
	return r
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func (r *rig) do(t *testing.T, method, path string, hdr map[string]string) (*http.Response, string) {
	t.Helper()
	req, err := http.NewRequest(method, strings.TrimSuffix(r.info.URL, "/")+path, nil)
	must(t, err)
	for k, v := range hdr {
		req.Header.Set(k, v)
	}
	resp, err := r.httpc.Do(req)
	must(t, err)
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	return resp, string(b)
}

func sum(b []byte) string { h := sha256.Sum256(b); return hex.EncodeToString(h[:]) }

func TestListingAndReading(t *testing.T) {
	r := newRig(t)

	// Root: one entry per share; a folder share is a directory, a file share is the file.
	resp, body := r.do(t, "PROPFIND", "/", map[string]string{"Depth": "1"})
	if resp.StatusCode != http.StatusMultiStatus {
		t.Fatalf("PROPFIND root: %d %s", resp.StatusCode, body)
	}
	for _, want := range []string{"Projects", "report.pdf"} {
		if !strings.Contains(body, want) {
			t.Errorf("root listing misses %q:\n%s", want, body)
		}
	}

	// Inside a share, and deeper.
	_, body = r.do(t, "PROPFIND", "/Projects", map[string]string{"Depth": "1"})
	for _, want := range []string{"docs", "big.bin"} {
		if !strings.Contains(body, want) {
			t.Errorf("share listing misses %q", want)
		}
	}
	_, body = r.do(t, "PROPFIND", "/Projects/docs", map[string]string{"Depth": "1"})
	if !strings.Contains(body, "readme.txt") || !strings.Contains(body, "deep") {
		t.Errorf("sub-folder listing wrong:\n%s", body)
	}

	// Reading: whole files, nested files and the single-file share.
	if resp, got := r.do(t, "GET", "/Projects/docs/readme.txt", nil); resp.StatusCode != 200 || got != "hello drive" {
		t.Errorf("GET readme: %d %q", resp.StatusCode, got)
	}
	if resp, got := r.do(t, "GET", "/report.pdf", nil); resp.StatusCode != 200 || got != "%PDF-fake" {
		t.Errorf("GET file share: %d %q", resp.StatusCode, got)
	}
	if resp, got := r.do(t, "GET", "/Projects/big.bin", nil); resp.StatusCode != 200 || sum([]byte(got)) != sum(r.data) {
		t.Errorf("GET big.bin: %d, digest mismatch", resp.StatusCode)
	}
	if resp, _ := r.do(t, "GET", "/Projects/nope.txt", nil); resp.StatusCode != http.StatusNotFound {
		t.Errorf("missing file: %d, want 404", resp.StatusCode)
	}
}

// A player seeking in a big file must fetch just that range, not the file.
func TestRangeReadsOnlyWhatIsAsked(t *testing.T) {
	r := newRig(t)
	resp, got := r.do(t, "GET", "/Projects/big.bin", map[string]string{"Range": "bytes=2000000-2000999"})
	if resp.StatusCode != http.StatusPartialContent {
		t.Fatalf("status %d, want 206", resp.StatusCode)
	}
	if got != string(r.data[2000000:2001000]) {
		t.Error("range content mismatch")
	}
	if !strings.HasPrefix(resp.Header.Get("Content-Range"), "bytes 2000000-2000999/") {
		t.Errorf("Content-Range = %q", resp.Header.Get("Content-Range"))
	}
}

// The drive is read-only: every write verb is refused and nothing changes.
func TestReadOnly(t *testing.T) {
	r := newRig(t)
	for _, m := range []string{"PUT", "DELETE", "MKCOL", "MOVE", "COPY", "PROPPATCH", "LOCK", "POST"} {
		resp, _ := r.do(t, m, "/Projects/docs/readme.txt", map[string]string{"Destination": r.info.URL + "Projects/x"})
		if resp.StatusCode != http.StatusMethodNotAllowed {
			t.Errorf("%s: %d, want 405", m, resp.StatusCode)
		}
	}
	b, _ := os.ReadFile(filepath.Join(r.srcDir, "docs", "readme.txt"))
	if string(b) != "hello drive" {
		t.Error("the source file was modified through the mount")
	}
	// The file system itself also refuses, in case a verb slips through.
	fsys := newRemoteFS(r.m.api, r.fp, func() (string, int, bool) { return "127.0.0.1", 1, true })
	if err := fsys.Mkdir(context.Background(), "/x", 0o755); err == nil {
		t.Error("Mkdir must fail")
	}
	if _, err := fsys.OpenFile(context.Background(), "/Projects/docs/readme.txt", os.O_RDWR, 0); err == nil {
		t.Error("opening for write must fail")
	}
}

func TestSecretPathAndHost(t *testing.T) {
	r := newRig(t)
	base := r.info.URL
	bad := strings.Replace(base, base[strings.LastIndex(strings.TrimSuffix(base, "/"), "/")+1:], "0000/", 1)
	resp, err := r.httpc.Get(bad + "Projects/big.bin")
	must(t, err)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("wrong secret: %d, want 404", resp.StatusCode)
	}
	req, _ := http.NewRequest("GET", r.info.URL+"Projects/docs/readme.txt", nil)
	req.Host = "evil.example.com"
	resp, err = r.httpc.Do(req)
	must(t, err)
	resp.Body.Close()
	if resp.StatusCode != http.StatusForbidden {
		t.Errorf("foreign Host header: %d, want 403 (DNS rebinding)", resp.StatusCode)
	}
}

// Unpairing revokes the drive immediately.
func TestUnpairRevokes(t *testing.T) {
	r := newRig(t)
	if resp, _ := r.do(t, "GET", "/Projects/docs/readme.txt", nil); resp.StatusCode != 200 {
		t.Fatalf("baseline read failed: %d", resp.StatusCode)
	}
	r.paired.Store(false)
	if resp, _ := r.do(t, "GET", "/Projects/docs/readme.txt", nil); resp.StatusCode != http.StatusForbidden {
		t.Errorf("after unpairing: %d, want 403", resp.StatusCode)
	}
	if n := len(r.m.List()); n != 0 {
		t.Errorf("the mount should be gone, %d left", n)
	}
	if _, err := r.m.Add("someone-unpaired", "x"); err == nil {
		t.Error("an unpaired device must not be mountable")
	}
}

// A share the sender stops disappears from the drive.
func TestStoppedShareLeavesTheDrive(t *testing.T) {
	r := newRig(t)
	r.shMgr.Stop(r.one.ShareID)
	time.Sleep(cacheTTL + 100*time.Millisecond) // listings are cached briefly
	if resp, _ := r.do(t, "GET", "/report.pdf", nil); resp.StatusCode != http.StatusNotFound {
		t.Errorf("a stopped share must vanish from the mount, got %d", resp.StatusCode)
	}
	_, body := r.do(t, "PROPFIND", "/", map[string]string{"Depth": "1"})
	if strings.Contains(body, "report.pdf") || !strings.Contains(body, "Projects") {
		t.Errorf("root listing wrong after stopping a share:\n%s", body)
	}
}

func TestHintAndDriveValidation(t *testing.T) {
	if !strings.HasPrefix(Hint("windows", "http://127.0.0.1:1/s/"), "net use Z:") {
		t.Error("Windows hint should be a net use command")
	}
	if !strings.Contains(Hint("darwin", "http://127.0.0.1:1/s/"), "mount_webdav") {
		t.Error("macOS hint should use mount_webdav")
	}
	if !strings.Contains(Hint("linux", "http://127.0.0.1:1/s/"), "davfs") {
		t.Error("Linux hint should use davfs")
	}
	for _, ok := range []string{"Z:", "a:"} {
		if !driveRE.MatchString(ok) {
			t.Errorf("%q should be a drive letter", ok)
		}
	}
	for _, bad := range []string{"", "ZZ:", "Z", `C:\`, "1:", "Z: && calc"} {
		if driveRE.MatchString(bad) {
			t.Errorf("%q must not be accepted as a drive letter", bad)
		}
	}
}

// If the sharer unpairs us, reading through the drive fails cleanly with 403
// (not a download that dies halfway).
func TestSharerRevokesAccess(t *testing.T) {
	r := newRig(t)
	if resp, _ := r.do(t, "GET", "/Projects/docs/readme.txt", nil); resp.StatusCode != 200 {
		t.Fatalf("baseline read failed: %d", resp.StatusCode)
	}
	r.revokedRef.Store(true)
	resp, _ := r.do(t, "GET", "/Projects/docs/readme.txt", nil)
	// The WebDAV layer reports any failure to open as 404; what matters is a
	// clean refusal up front rather than a transfer that starts and dies.
	if resp.StatusCode != http.StatusForbidden && resp.StatusCode != http.StatusNotFound {
		t.Errorf("after the sharer revoked us: %d, want a clean 403/404", resp.StatusCode)
	}
}

// Windows' WebClient probes the server root first; it must get a harmless
// answer that never mentions the secret path.
func TestRootProbeRevealsNothing(t *testing.T) {
	r := newRig(t)
	base := r.info.URL[:strings.Index(r.info.URL[len("http://"):], "/")+len("http://")]
	secret := strings.Trim(strings.TrimPrefix(r.info.URL, base), "/")
	for _, method := range []string{"OPTIONS", "PROPFIND"} {
		req, _ := http.NewRequest(method, base+"/", nil)
		req.Header.Set("Depth", "1")
		resp, err := r.httpc.Do(req)
		must(t, err)
		b, _ := io.ReadAll(resp.Body)
		resp.Body.Close()
		if resp.StatusCode != 200 && resp.StatusCode != 207 {
			t.Errorf("%s /: %d", method, resp.StatusCode)
		}
		if strings.Contains(string(b), secret) || strings.Contains(resp.Header.Get("Location"), secret) {
			t.Errorf("%s / leaked the secret path", method)
		}
		if method == "OPTIONS" && resp.Header.Get("DAV") == "" {
			t.Error("OPTIONS / should advertise DAV support")
		}
	}
	req, _ := http.NewRequest("GET", base+"/", nil)
	resp, err := r.httpc.Do(req)
	must(t, err)
	resp.Body.Close()
	if resp.StatusCode != http.StatusNotFound {
		t.Errorf("GET / = %d, want 404", resp.StatusCode)
	}
}
