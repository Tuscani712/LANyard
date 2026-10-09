package diag

import (
	"context"
	"strings"
	"testing"
	"time"
)

type fakeEnv struct {
	requestedPort int
	port          int
	fellBack      bool
	addrs         []string
	peers         []Peer
	target        string

	tcpFn   func(host string, port int) error
	probeFn func(host string, port int) (string, error)

	free     map[string]int64
	inboxDir string
	dlDir    string

	helloTime time.Time
	helloSet  bool
	now       time.Time
}

func (f *fakeEnv) RequestedPeerPort() int { return f.requestedPort }
func (f *fakeEnv) PeerPort() int          { return f.port }
func (f *fakeEnv) PeerPortFallback() bool { return f.fellBack }
func (f *fakeEnv) LocalAddrs() []string   { return f.addrs }
func (f *fakeEnv) Peers() []Peer          { return f.peers }
func (f *fakeEnv) TargetDevice() string   { return f.target }
func (f *fakeEnv) PeerByID(id string) (Peer, bool) {
	for _, p := range f.peers {
		if p.DeviceID == id || strings.HasPrefix(p.DeviceID, id) {
			return p, true
		}
	}
	return Peer{}, false
}
func (f *fakeEnv) DialTCP(_ context.Context, host string, port int) error {
	if f.tcpFn != nil {
		return f.tcpFn(host, port)
	}
	return nil
}
func (f *fakeEnv) Probe(_ context.Context, host string, port int) (string, error) {
	if f.probeFn != nil {
		return f.probeFn(host, port)
	}
	return "", nil
}
func (f *fakeEnv) FreeSpace(dir string) int64       { return f.free[dir] }
func (f *fakeEnv) InboxDir() string                 { return f.inboxDir }
func (f *fakeEnv) DownloadDir() string              { return f.dlDir }
func (f *fakeEnv) PeerHelloTime() (time.Time, bool) { return f.helloTime, f.helloSet }
func (f *fakeEnv) Now() time.Time                   { return f.now }

func baseEnv() *fakeEnv {
	return &fakeEnv{
		requestedPort: 47800, port: 47800,
		addrs: []string{"192.168.1.20"}, now: time.Now(),
	}
}

func TestRunOrderAndCount(t *testing.T) {
	checks := Run(context.Background(), baseEnv())
	want := []string{"peer-port", "local-addrs", "discovery", "firewall", "peer", "clock", "disk"}
	if len(checks) != len(want) {
		t.Fatalf("Run returned %d checks, want %d", len(checks), len(want))
	}
	for i, id := range want {
		if checks[i].ID != id {
			t.Errorf("check %d ID = %q, want %q", i, checks[i].ID, id)
		}
	}
}

func TestPeerPortPortInUseFallsBack(t *testing.T) {
	env := baseEnv()
	env.fellBack = true // the requested port failed to bind; the server moved
	env.requestedPort = 47800
	env.port = 50000
	c := checkPeerPort(context.Background(), env)
	if c.Status != StatusWarn || !strings.Contains(c.Detail, "50000") {
		t.Fatalf("got %+v, want warn about the fallback port", c)
	}
}

// A --port flag that differs from the settings value is not a fallback and must
// not warn.
func TestPeerPortNoFalseWarning(t *testing.T) {
	env := baseEnv()
	env.requestedPort = 12345 // --port override
	env.port = 12345
	env.fellBack = false
	if c := checkPeerPort(context.Background(), env); c.Status != StatusOK {
		t.Fatalf("got %+v, want ok", c)
	}
}

func TestPeerPortFailWhenNotListening(t *testing.T) {
	env := baseEnv()
	env.tcpFn = func(string, int) error { return context.DeadlineExceeded }
	if c := checkPeerPort(context.Background(), env); c.Status != StatusFail {
		t.Fatalf("got %+v, want fail", c)
	}
}

func TestLocalAddrsNoneIsFailure(t *testing.T) {
	env := baseEnv()
	env.addrs = nil
	if c := checkLocalAddrs(context.Background(), env); c.Status != StatusFail {
		t.Fatalf("got %+v, want fail", c)
	}
}

func TestDiscoveryCases(t *testing.T) {
	now := time.Now()
	env := baseEnv()
	env.now = now
	env.peers = []Peer{{Source: "mdns", LastSeen: now.Add(-5 * time.Second)}}
	if c := checkDiscovery(context.Background(), env); c.Status != StatusOK {
		t.Fatalf("mdns peer: got %+v, want ok", c)
	}

	env.peers = []Peer{{Source: "manual", LastSeen: now.Add(-5 * time.Second)}}
	if c := checkDiscovery(context.Background(), env); c.Status != StatusWarn || !strings.Contains(c.Detail, "manually") {
		t.Fatalf("manual only: got %+v, want warn", c)
	}

	env.peers = nil
	if c := checkDiscovery(context.Background(), env); c.Status != StatusWarn {
		t.Fatalf("none: got %+v, want warn", c)
	}
}

func TestFirewallHintWhenLanBlocked(t *testing.T) {
	env := baseEnv()
	env.probeFn = func(host string, _ int) (string, error) {
		if host == "127.0.0.1" {
			return "cert", nil
		}
		return "", context.DeadlineExceeded
	}
	c := checkFirewall(context.Background(), env)
	if c.Status != StatusWarn || !strings.Contains(c.Detail, "firewall") {
		t.Fatalf("got %+v, want a firewall hint", c)
	}
}

func TestPeerFingerprintMismatchIsFailure(t *testing.T) {
	env := baseEnv()
	peerFP := strings.Repeat("a", 64)
	env.target = peerFP
	env.peers = []Peer{{DeviceID: peerFP, Name: "Bob", Addrs: []string{"192.168.1.30"}, Port: 47800}}
	env.probeFn = func(string, int) (string, error) { return strings.Repeat("b", 64), nil }
	c := checkPeer(context.Background(), env)
	if c.Status != StatusFail || !strings.Contains(c.Detail, "identity has changed") {
		t.Fatalf("got %+v, want identity-changed failure", c)
	}
	if strings.Contains(c.Detail, "identity has changed") == false {
		t.Fatalf("got %+v, want the identity-changed message", c)
	}
	// There is no trust action at all, and the wording warns against trusting.
	if !strings.Contains(strings.ToLower(c.Fix), "do not trust") {
		t.Errorf("the fix should warn the user not to trust the change: %q", c.Fix)
	}
}

func TestPeerSuccess(t *testing.T) {
	env := baseEnv()
	peerFP := strings.Repeat("a", 64)
	env.target = peerFP
	env.peers = []Peer{{DeviceID: peerFP, Name: "Bob", Addrs: []string{"192.168.1.30"}, Port: 47800}}
	env.probeFn = func(string, int) (string, error) { return peerFP, nil }
	if c := checkPeer(context.Background(), env); c.Status != StatusOK {
		t.Fatalf("got %+v, want ok", c)
	}
}

func TestPeerSkippedWithoutTarget(t *testing.T) {
	if c := checkPeer(context.Background(), baseEnv()); c.Status != StatusSkip {
		t.Fatalf("got %+v, want skip", c)
	}
}

func TestClockSkippedWhenProtocolHasNoTimestamp(t *testing.T) {
	if c := checkClock(context.Background(), baseEnv()); c.Status != StatusSkip {
		t.Fatalf("got %+v, want skip", c)
	}
}

func TestDiskWarnsWhenLow(t *testing.T) {
	env := baseEnv()
	env.inboxDir = "/inbox"
	env.dlDir = "/downloads"
	env.free = map[string]int64{"/inbox": lowDisk - 1, "/downloads": 10 << 30}
	c := checkDisk(context.Background(), env)
	if c.Status != StatusWarn || !strings.Contains(c.Detail, "Inbox") {
		t.Fatalf("got %+v, want a low-disk warning", c)
	}
}

func TestReportRedactsFullFingerprints(t *testing.T) {
	fp := strings.Repeat("a", 64)
	report := Report([]Check{{ID: "x", Title: "T", Status: StatusFail, Detail: "peer fp " + fp, Fix: "seen " + fp}})
	if strings.Contains(report, fp) {
		t.Fatal("report leaked a full fingerprint")
	}
	if !strings.Contains(report, "<fingerprint>") {
		t.Fatalf("expected redaction marker, got:\n%s", report)
	}
}
