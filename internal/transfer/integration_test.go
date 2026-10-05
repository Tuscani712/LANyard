package transfer

import (
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"io"
	"log/slog"
	"math/rand"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
)

var quiet = slog.New(slog.NewTextHandler(io.Discard, nil))

// connPlan describes how the proxy mistreats one client connection.
type connPlan struct {
	cutAfter  int64 // close after forwarding this many server->client bytes (0 = never)
	holdAfter int64 // stop forwarding after this many bytes until Release (0 = never)
}

// proxy is a TCP forwarder in front of the peer service. TLS passes through
// untouched, so it can drop, stall or refuse connections like a bad network.
type proxy struct {
	ln     net.Listener
	target string
	plan   func(i int) connPlan

	mu      sync.Mutex
	n       int
	refuse  bool
	conns   []net.Conn
	release chan struct{}
	sent    atomic.Int64 // server->client bytes forwarded, all connections
}

func newProxy(t *testing.T, target string, plan func(i int) connPlan) *proxy {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	p := &proxy{ln: ln, target: target, plan: plan, release: make(chan struct{})}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go p.handle(c)
		}
	}()
	t.Cleanup(func() { ln.Close(); p.Kill() })
	return p
}

func (p *proxy) Port() int { return p.ln.Addr().(*net.TCPAddr).Port }

func (p *proxy) SetRefuse(v bool) { p.mu.Lock(); p.refuse = v; p.mu.Unlock() }

// Release lets held connections continue.
func (p *proxy) Release() {
	p.mu.Lock()
	defer p.mu.Unlock()
	select {
	case <-p.release:
	default:
		close(p.release)
	}
}

// Kill drops every open connection.
func (p *proxy) Kill() {
	p.mu.Lock()
	cs := p.conns
	p.conns = nil
	p.mu.Unlock()
	for _, c := range cs {
		c.Close()
	}
}

func (p *proxy) handle(c net.Conn) {
	p.mu.Lock()
	idx := p.n
	p.n++
	refuse := p.refuse
	p.mu.Unlock()
	if refuse {
		c.Close()
		return
	}
	up, err := net.Dial("tcp", p.target)
	if err != nil {
		c.Close()
		return
	}
	p.mu.Lock()
	p.conns = append(p.conns, c, up)
	p.mu.Unlock()
	var pl connPlan
	if p.plan != nil {
		pl = p.plan(idx)
	}
	go func() { io.Copy(up, c); up.Close() }()
	buf := make([]byte, 32*1024)
	var sent int64
	for {
		n, err := up.Read(buf)
		if n > 0 {
			if _, werr := c.Write(buf[:n]); werr != nil {
				break
			}
			sent += int64(n)
			p.sent.Add(int64(n))
			if pl.cutAfter > 0 && sent >= pl.cutAfter {
				break
			}
			if pl.holdAfter > 0 && sent >= pl.holdAfter {
				<-p.release
			}
		}
		if err != nil {
			break
		}
	}
	c.Close()
	up.Close()
}

type env struct {
	srcDir string
	shMgr  *shares.Manager
	share  *shares.Share
	proxy  *proxy
	cfg    *config.Store
	client *peerapi.Client
	mgr    *Manager
	peerFP string
}

func newEnv(t *testing.T, plan func(i int) connPlan) *env {
	t.Helper()
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	shMgr := shares.New(cfg, id.DeviceID, nil)
	t.Cleanup(func() { shMgr.StopAll() })
	srcDir := t.TempDir()
	sh, err := shMgr.Add(srcDir, shares.AddOptions{LifetimeType: shares.LifetimeUntilStopped})
	if err != nil {
		t.Fatal(err)
	}
	srv := peerapi.NewServer(id, func() discovery.Hello { return discovery.Hello{} }, shMgr, nil, peerapi.AllowAll(), quiet)
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go srv.Serve(ln)
	t.Cleanup(func() {
		ctx, cancel := context.WithTimeout(context.Background(), time.Second)
		defer cancel()
		srv.Shutdown(ctx)
	})
	client := peerapi.NewClient(id)
	e := &env{srcDir: srcDir, shMgr: shMgr, share: sh, cfg: cfg, client: client, peerFP: id.DeviceID}
	e.proxy = newProxy(t, ln.Addr().String(), plan)
	e.mgr = e.newManager()
	return e
}

func (e *env) newManager() *Manager {
	m := New(e.cfg, e.client, quiet, 3, nil)
	m.backoffBase = 10 * time.Millisecond
	m.backoffMax = 40 * time.Millisecond
	return m
}

func (e *env) create(t *testing.T, dest string) *View {
	t.Helper()
	v, err := e.mgr.Create(context.Background(), CreateParams{
		PeerID: e.peerFP, PeerName: "peer", Host: "127.0.0.1", Port: e.proxy.Port(),
		ShareID: e.share.ShareID, ShareLabel: e.share.Label, Dest: dest,
	})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	return v
}

func randBytes(seed int64, n int) []byte {
	b := make([]byte, n)
	rand.New(rand.NewSource(seed)).Read(b)
	return b
}

func writeSrc(t *testing.T, path string, data []byte) {
	t.Helper()
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(path, data, 0o644); err != nil {
		t.Fatal(err)
	}
}

func sum(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }

func fileSum(t *testing.T, path string) string {
	t.Helper()
	b, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read %s: %v", path, err)
	}
	return sum(b)
}

func waitState(t *testing.T, m *Manager, id string, want string, within time.Duration) *View {
	t.Helper()
	deadline := time.Now().Add(within)
	for {
		v, ok := m.Get(id)
		if ok && v.State == want {
			return v
		}
		if time.Now().After(deadline) {
			got := "(missing)"
			var note, errs string
			if ok {
				got, note, errs = v.State, v.Note, v.Error
			}
			t.Fatalf("job %s: wanted %q within %v, got %q (note=%q error=%q)", id, want, within, got, note, errs)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

func waitDone(t *testing.T, m *Manager, id string, atLeast int64, within time.Duration) {
	t.Helper()
	deadline := time.Now().Add(within)
	for {
		v, _ := m.Get(id)
		if v != nil && v.Done >= atLeast {
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("job %s never reached %d bytes", id, atLeast)
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// waitIdle waits until the job's run loop has fully exited.
func waitIdle(t *testing.T, m *Manager, id string) {
	t.Helper()
	m.mu.Lock()
	j := m.jobs[id]
	m.mu.Unlock()
	deadline := time.Now().Add(5 * time.Second)
	for {
		j.mu.Lock()
		running := j.running
		j.mu.Unlock()
		if !running {
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("job never went idle")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// TestResumeAfterRepeatedDrops cuts the connection mid-file three times and
// checks the result is byte-identical AND that resume really used Range
// requests (restarting from zero each time would resend ~13 MB more).
func TestResumeAfterRepeatedDrops(t *testing.T) {
	cuts := []int64{4 << 20, 6 << 20, 3 << 20}
	e := newEnv(t, func(i int) connPlan {
		if i < len(cuts) {
			return connPlan{cutAfter: cuts[i]}
		}
		return connPlan{}
	})
	const size = 32 << 20
	data := randBytes(1, size)
	writeSrc(t, filepath.Join(e.srcDir, "big.bin"), data)

	dest := t.TempDir()
	v := e.create(t, dest)
	waitState(t, e.mgr, v.ID, StateDone, 60*time.Second)

	if got := fileSum(t, filepath.Join(dest, "big.bin")); got != sum(data) {
		t.Fatalf("content mismatch after resume: %s", got)
	}
	if sent := e.proxy.sent.Load(); sent > size+4<<20 {
		t.Fatalf("server sent %d bytes for a %d byte file: resume is restarting from zero", sent, size)
	}
	for _, ext := range []string{".lanpart", ".lanstate"} {
		if _, err := os.Stat(filepath.Join(dest, "big.bin"+ext)); err == nil {
			t.Errorf("leftover %s file", ext)
		}
	}
}

// TestSourceChangedMidTransfer pauses a download, rewrites the source with a
// different size, and resumes: the receiver must end up with the NEW content.
func TestSourceChangedMidTransfer(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 3 << 20}
		}
		return connPlan{}
	})
	old := randBytes(2, 12<<20)
	src := filepath.Join(e.srcDir, "doc.bin")
	writeSrc(t, src, old)

	dest := t.TempDir()
	v := e.create(t, dest)
	waitDone(t, e.mgr, v.ID, 2<<20, 10*time.Second)
	if err := e.mgr.Pause(v.ID); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e.mgr, v.ID)
	e.proxy.Release()

	changed := randBytes(3, 9<<20+12345)
	writeSrc(t, src, changed)
	future := time.Now().Add(time.Hour)
	_ = os.Chtimes(src, future, future)

	if err := e.mgr.Resume(v.ID); err != nil {
		t.Fatal(err)
	}
	waitState(t, e.mgr, v.ID, StateDone, 30*time.Second)
	if got := fileSum(t, filepath.Join(dest, "doc.bin")); got != sum(changed) {
		t.Fatalf("expected the new content after the source changed, got %s", got)
	}
	fin, _ := e.mgr.Get(v.ID)
	if fin.Total != int64(len(changed)) {
		t.Errorf("job total %d, want %d", fin.Total, len(changed))
	}
}

// TestCorruptPartialIsDetected flips a byte inside the committed prefix of a
// partial file. Re-hashing the prefix must catch it and re-download the file.
func TestCorruptPartialIsDetected(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 3 << 20}
		}
		return connPlan{}
	})
	data := randBytes(4, 10<<20)
	writeSrc(t, filepath.Join(e.srcDir, "d.bin"), data)

	dest := t.TempDir()
	v := e.create(t, dest)
	waitDone(t, e.mgr, v.ID, 2<<20, 10*time.Second)
	if err := e.mgr.Pause(v.ID); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e.mgr, v.ID)
	e.proxy.Release()

	part := filepath.Join(dest, "d.bin.lanpart")
	pb, err := os.ReadFile(part)
	if err != nil || len(pb) < 1<<20 {
		t.Fatalf("expected a sizeable partial file, err=%v len=%d", err, len(pb))
	}
	pb[100] ^= 0xFF
	if err := os.WriteFile(part, pb, 0o644); err != nil {
		t.Fatal(err)
	}

	if err := e.mgr.Resume(v.ID); err != nil {
		t.Fatal(err)
	}
	waitState(t, e.mgr, v.ID, StateDone, 30*time.Second)
	if got := fileSum(t, filepath.Join(dest, "d.bin")); got != sum(data) {
		t.Fatalf("corruption was not repaired: %s", got)
	}
}

// TestFolderResumeAcrossRestart pauses a multi-file job, builds a brand new
// Manager from the same config (as after an app restart) and resumes. Files
// already finished must not be fetched again, and mtimes must be preserved.
func TestFolderResumeAcrossRestart(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 5 << 20}
		}
		return connPlan{}
	})
	want := map[string][]byte{}
	var total int64
	old := time.Now().Add(-48 * time.Hour).Truncate(time.Second)
	for i := 0; i < 6; i++ {
		name := fmt.Sprintf("sub/f%d.bin", i)
		b := randBytes(int64(10+i), 2<<20)
		want[name] = b
		total += int64(len(b))
		p := filepath.Join(e.srcDir, filepath.FromSlash(name))
		writeSrc(t, p, b)
		_ = os.Chtimes(p, old, old)
	}

	dest := t.TempDir()
	v := e.create(t, dest)
	waitDone(t, e.mgr, v.ID, 4<<20, 10*time.Second)
	if err := e.mgr.Pause(v.ID); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e.mgr, v.ID)
	e.proxy.Release()
	paused, _ := e.mgr.Get(v.ID)
	doneBefore := paused.FilesDone
	bytesBefore := e.proxy.sent.Load()

	// "Restart": a new Manager over the same persisted config.
	m2 := e.newManager()
	if err := m2.Load(); err != nil {
		t.Fatal(err)
	}
	lv, ok := m2.Get(v.ID)
	if !ok {
		t.Fatal("job was not restored after restart")
	}
	if lv.State != StatePaused {
		t.Errorf("restored state = %q, want Paused", lv.State)
	}
	e.mgr = m2
	if err := m2.Resume(v.ID); err != nil {
		t.Fatal(err)
	}
	waitState(t, m2, v.ID, StateDone, 30*time.Second)

	for name, b := range want {
		p := filepath.Join(dest, filepath.FromSlash(name))
		if fileSum(t, p) != sum(b) {
			t.Errorf("%s differs", name)
		}
		info, _ := os.Stat(p)
		if d := info.ModTime().Sub(old); d < -2*time.Second || d > 2*time.Second {
			t.Errorf("%s mtime %v not preserved (want %v)", name, info.ModTime(), old)
		}
	}
	// Phase two should only fetch what was missing, not the whole folder.
	phase2 := e.proxy.sent.Load() - bytesBefore
	if limit := total - int64(doneBefore)*(2<<20) + 3<<20; phase2 > limit {
		t.Errorf("after restart the server sent %d bytes, expected at most ~%d (done before: %d files)", phase2, limit, doneBefore)
	}
}

// TestWaitingForPeerThenAutoResume makes the peer unreachable until the
// offline limit passes (job -> Waiting for peer), then announces the peer
// again and checks the job resumes by itself and completes.
func TestWaitingForPeerThenAutoResume(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 2 << 20}
		}
		return connPlan{}
	})
	e.mgr.offlineLimit = 400 * time.Millisecond
	data := randBytes(5, 8<<20)
	writeSrc(t, filepath.Join(e.srcDir, "w.bin"), data)

	dest := t.TempDir()
	v := e.create(t, dest)
	waitDone(t, e.mgr, v.ID, 1<<20, 10*time.Second)

	e.proxy.SetRefuse(true)
	e.proxy.Kill()
	e.proxy.Release()
	waitState(t, e.mgr, v.ID, StateWaiting, 10*time.Second)

	e.proxy.SetRefuse(false)
	e.mgr.PeerAvailable(e.peerFP, "127.0.0.1", e.proxy.Port())
	waitState(t, e.mgr, v.ID, StateDone, 30*time.Second)
	if got := fileSum(t, filepath.Join(dest, "w.bin")); got != sum(data) {
		t.Fatalf("content mismatch: %s", got)
	}
}

// TestNonRetryableErrorFails: a share that disappears (410) must fail fast
// instead of retrying forever.
func TestNonRetryableErrorFails(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 1 << 20}
		}
		return connPlan{}
	})
	e.mgr.offlineLimit = time.Minute
	writeSrc(t, filepath.Join(e.srcDir, "g.bin"), randBytes(6, 6<<20))
	v := e.create(t, t.TempDir())
	waitDone(t, e.mgr, v.ID, 512<<10, 10*time.Second)

	// Stop the share on the server, then drop the connection.
	e.shMgr.Stop(e.share.ShareID)
	e.proxy.Kill()
	e.proxy.Release()
	got := waitState(t, e.mgr, v.ID, StateFailed, 10*time.Second)
	if got.Error == "" {
		t.Error("failed job should carry an error message")
	}
}

func TestBackoffSequence(t *testing.T) {
	m := &Manager{backoffBase: time.Second, backoffMax: 30 * time.Second}
	want := []time.Duration{1, 2, 4, 8, 16, 30, 30}
	for i, w := range want {
		if got := m.backoff(i + 1); got != w*time.Second {
			t.Errorf("backoff(%d) = %v, want %v", i+1, got, w*time.Second)
		}
	}
}

func TestContentRangeStart(t *testing.T) {
	if v, ok := contentRangeStart("bytes 100-199/400"); !ok || v != 100 {
		t.Errorf("got %d %v", v, ok)
	}
	for _, bad := range []string{"", "bytes */400", "garbage"} {
		if _, ok := contentRangeStart(bad); ok {
			t.Errorf("%q should not parse", bad)
		}
	}
}

func TestHashPrefix(t *testing.T) {
	p := filepath.Join(t.TempDir(), "x")
	data := randBytes(7, 3<<20+17)
	if err := os.WriteFile(p, data, 0o644); err != nil {
		t.Fatal(err)
	}
	h := sha256.New()
	ok, err := hashPrefix(context.Background(), p, 2<<20, h)
	if err != nil || !ok {
		t.Fatalf("hashPrefix: ok=%v err=%v", ok, err)
	}
	if hex.EncodeToString(h.Sum(nil)) != sum(data[:2<<20]) {
		t.Error("prefix digest mismatch")
	}
	ok, _ = hashPrefix(context.Background(), p, int64(len(data))+1, sha256.New())
	if ok {
		t.Error("a file shorter than the requested prefix must not be trusted")
	}
}

// TestPullNeverOverwrites: a different file already at the destination must
// be left alone; the download is saved as "name (1).ext". Repeating the same
// download must not create "(2)", and a pause/resume must keep the same name.
func TestPullNeverOverwrites(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 3 << 20}
		}
		return connPlan{}
	})
	data := randBytes(21, 9<<20)
	writeSrc(t, filepath.Join(e.srcDir, "report.v2.bin"), data)
	srcInfo, _ := os.Stat(filepath.Join(e.srcDir, "report.v2.bin"))

	dest := t.TempDir()
	mine := []byte("my own, different file")
	if err := os.WriteFile(filepath.Join(dest, "report.v2.bin"), mine, 0o644); err != nil {
		t.Fatal(err)
	}

	v := e.create(t, dest)
	waitDone(t, e.mgr, v.ID, 2<<20, 10*time.Second)
	if err := e.mgr.Pause(v.ID); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e.mgr, v.ID)
	e.proxy.Release()
	if err := e.mgr.Resume(v.ID); err != nil {
		t.Fatal(err)
	}
	waitState(t, e.mgr, v.ID, StateDone, 30*time.Second)

	if got, _ := os.ReadFile(filepath.Join(dest, "report.v2.bin")); !bytes.Equal(got, mine) {
		t.Fatal("the existing file was overwritten")
	}
	saved := filepath.Join(dest, "report.v2 (1).bin")
	if fileSum(t, saved) != sum(data) {
		t.Fatal("download did not land in 'report.v2 (1).bin'")
	}
	if info, _ := os.Stat(saved); info.ModTime().Sub(srcInfo.ModTime()).Abs() > 2*time.Second {
		t.Errorf("mtime not preserved on the renamed copy")
	}
	if _, err := os.Stat(filepath.Join(dest, "report.v2 (2).bin")); err == nil {
		t.Error("pause/resume created a second copy")
	}

	// Same download again into the same folder: the (1) copy is recognised.
	v2 := e.create(t, dest)
	waitState(t, e.mgr, v2.ID, StateDone, 30*time.Second)
	if _, err := os.Stat(filepath.Join(dest, "report.v2 (2).bin")); err == nil {
		t.Error("repeating a download piled up a duplicate")
	}
	if got, _ := os.ReadFile(filepath.Join(dest, "report.v2.bin")); !bytes.Equal(got, mine) {
		t.Fatal("the existing file was overwritten by the repeat")
	}
}

// TestIdenticalCopyIsSkipped: a file already present with the same size and
// modification time is treated as downloaded, not duplicated.
func TestIdenticalCopyIsSkipped(t *testing.T) {
	e := newEnv(t, nil)
	data := randBytes(22, 1<<20)
	src := filepath.Join(e.srcDir, "same.bin")
	writeSrc(t, src, data)
	info, _ := os.Stat(src)

	dest := t.TempDir()
	have := filepath.Join(dest, "same.bin")
	if err := os.WriteFile(have, data, 0o644); err != nil {
		t.Fatal(err)
	}
	_ = os.Chtimes(have, info.ModTime(), info.ModTime())

	v := e.create(t, dest)
	waitState(t, e.mgr, v.ID, StateDone, 10*time.Second)
	if _, err := os.Stat(filepath.Join(dest, "same (1).bin")); err == nil {
		t.Error("an identical existing copy was duplicated")
	}
	if e.proxy.sent.Load() > 200<<10 {
		t.Errorf("identical file was downloaded again (%d bytes)", e.proxy.sent.Load())
	}
}

func TestUniqueRelNaming(t *testing.T) {
	dest := t.TempDir()
	for _, n := range []string{"a.txt", "a (1).txt", ".env", "dir/b.tar.gz"} {
		p := filepath.Join(dest, filepath.FromSlash(n))
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		_ = os.WriteFile(p, []byte("x"), 0o644)
	}
	cases := map[string]string{
		"a.txt":        "a (2).txt",
		".env":         ".env (1)",
		"dir/b.tar.gz": "dir/b.tar (1).gz",
		"noext":        "noext (1)",
	}
	for local, want := range cases {
		job := &Job{Dest: dest}
		f := &FileJob{Local: local, Size: 5, MTime: 1}
		job.Files = []*FileJob{f}
		if local == "noext" {
			_ = os.WriteFile(filepath.Join(dest, "noext"), []byte("x"), 0o644)
		}
		if got := uniqueRel(job, f); got != want {
			t.Errorf("uniqueRel(%q) = %q, want %q", local, got, want)
		}
	}
}

// A pull that cannot fit on the disk fails up front with a clear message and
// writes nothing; once there is room, Resume completes it.
func TestPullRefusesWhenTheDiskIsTooSmall(t *testing.T) {
	e := newEnv(t, nil)
	data := randBytes(71, 6<<20)
	writeSrc(t, filepath.Join(e.srcDir, "big.bin"), data)
	var free atomic.Int64
	free.Store(10 << 20) // less than 6 MB + the safety margin
	e.mgr.freeSpace = func(string) int64 { return free.Load() }

	dest := t.TempDir()
	v := e.create(t, dest)
	got := waitState(t, e.mgr, v.ID, StateFailed, 10*time.Second)
	if !strings.Contains(got.Error, "free space") || !strings.Contains(got.Error, "Resume") {
		t.Errorf("want a free-space message, got %q", got.Error)
	}
	if entries, _ := os.ReadDir(dest); len(entries) != 0 {
		t.Errorf("nothing should be written when the disk is too small, found %d entries", len(entries))
	}

	free.Store(10 << 30)
	if err := e.mgr.Resume(v.ID); err != nil {
		t.Fatal(err)
	}
	waitState(t, e.mgr, v.ID, StateDone, 30*time.Second)
	if fileSum(t, filepath.Join(dest, "big.bin")) != sum(data) {
		t.Error("content differs after resuming with enough space")
	}
}

func TestHumanBytes(t *testing.T) {
	cases := map[int64]string{0: "0 B", 1023: "1023 B", 1024: "1.0 KiB", 5 << 20: "5.0 MiB", 3 << 30: "3.0 GiB", 2 << 40: "2.0 TiB"}
	for n, want := range cases {
		if got := humanBytes(n); got != want {
			t.Errorf("humanBytes(%d) = %q, want %q", n, got, want)
		}
	}
}
