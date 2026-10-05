package transfer

import (
	"context"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"lanyard/internal/approval"
	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
	"lanyard/internal/trust"
)

// pushRig is a receiver (real mTLS server, trust store, Inbox, approval queue)
// and a sender (client + transfer manager) wired together on loopback.
type pushRig struct {
	recvID, sendID *identity.Identity
	trR, trS       *trust.Store // receiver's and sender's trust stores
	approvals      *approval.Manager
	inboxDir       string
	mgr            *Manager
	client         *peerapi.Client
	host           string
	port           int
}

func newPushRig(t *testing.T) *pushRig {
	t.Helper()
	recvID, err := identity.LoadOrCreate(t.TempDir(), "recv")
	if err != nil {
		t.Fatal(err)
	}
	sendID, err := identity.LoadOrCreate(t.TempDir(), "send")
	if err != nil {
		t.Fatal(err)
	}
	cfgR, _ := config.Open(t.TempDir())
	cfgS, _ := config.Open(t.TempDir())
	trR := trust.New(cfgR, recvID.DeviceID, nil)
	trS := trust.New(cfgS, sendID.DeviceID, nil)
	shMgr := shares.New(cfgR, recvID.DeviceID, nil)
	t.Cleanup(func() { shMgr.StopAll() })

	inboxDir := t.TempDir()
	approvals := approval.New(nil)
	srv := peerapi.NewServer(recvID, func() discovery.Hello { return discovery.Hello{} }, shMgr, trR, trR, quiet)
	srv.SetInbox(inbox.New(inboxDir, nil))
	srv.SetApprovals(approvals)
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

	client := peerapi.NewClient(sendID)
	mgr := New(cfgS, client, quiet, 3, nil)
	mgr.backoffBase, mgr.backoffMax = 10*time.Millisecond, 40*time.Millisecond
	rig := &pushRig{recvID: recvID, sendID: sendID, trR: trR, trS: trS, approvals: approvals,
		inboxDir: inboxDir, mgr: mgr, client: client, host: "127.0.0.1", port: ln.Addr().(*net.TCPAddr).Port}
	mgr.SetOnDone(func(ji JobInfo) {
		ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
		defer cancel()
		client.EndConnectAfterTransfer(ctx, trS, ji.PeerID, ji.Host, ji.Port)
	})
	return rig
}

// connect sets up an accepted Connect session on both sides, as the pairing
// handshake would have, and returns the sender's and receiver's session IDs.
func (r *pushRig) connect(t *testing.T, keepSender, keepReceiver bool) (sendSess, recvSess string) {
	t.Helper()
	in, err := r.trR.CreateIncoming(trust.ModeConnect, r.sendID.DeviceID, "Sender PC", "sender-dev", "n1", trust.Permissions{})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := r.trR.Accept(in.ID, trust.Permissions{}); err != nil {
		t.Fatal(err)
	}
	out := r.trS.CreateOutgoing(trust.ModeConnect, r.recvID.DeviceID, "Receiver PC", "recv-dev", trust.Permissions{})
	r.trS.SetRemote(out.ID, in.ID, "n2")
	r.trS.SetStatus(out.ID, trust.StatusActive, "")
	r.trR.SetKeepConnected(in.ID, keepReceiver)
	r.trS.SetKeepConnected(out.ID, keepSender)
	return out.ID, in.ID
}

func (r *pushRig) push(t *testing.T, paths ...string) *View {
	t.Helper()
	v, err := r.mgr.Push(context.Background(), PushParams{
		PeerID: r.recvID.DeviceID, PeerName: "Receiver PC", Host: r.host, Port: r.port, Paths: paths,
	})
	if err != nil {
		t.Fatal(err)
	}
	return v
}

func (r *pushRig) waitPrompt(t *testing.T) approval.Request {
	t.Helper()
	var req approval.Request
	waitFor(t, "an acceptance prompt on the receiver", 10*time.Second, func() bool {
		p := r.approvals.Pending()
		if len(p) == 0 {
			return false
		}
		req = p[0]
		return true
	})
	return req
}

func writeTemp(t *testing.T, name string, data []byte) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), name)
	if err := os.WriteFile(p, data, 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func inboxFiles(t *testing.T, dir string) map[string]string {
	t.Helper()
	got := map[string]string{}
	_ = filepath.WalkDir(dir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && !strings.HasSuffix(p, ".lanpart") && !strings.HasSuffix(p, ".lanstate") {
			b, _ := os.ReadFile(p)
			got[d.Name()] = sum(b)
		}
		return nil
	})
	return got
}

func sessionOpen(tr *trust.Store, peerFP string) bool {
	_, ok := tr.GetByPeer(peerFP)
	return ok
}

// A Connect push waits for a person; on Accept it lands in the Inbox, and the
// session then ends on both sides (one session, one transfer).
func TestConnectPushIsAcceptedThenSessionCloses(t *testing.T) {
	r := newPushRig(t)
	r.connect(t, false, false)
	data := randBytes(61, 300<<10)
	v := r.push(t, writeTemp(t, "photo.bin", data), writeTemp(t, "note.txt", []byte("hi")))

	req := r.waitPrompt(t)
	if req.Reason != "connect" || req.Count != 2 || req.PeerName != "Sender PC" || req.Total != int64(len(data))+2 {
		t.Fatalf("unexpected prompt: %+v", req)
	}
	if got, _ := r.mgr.Get(v.ID); got.State == StateDone || !strings.Contains(got.Note, "Waiting") {
		t.Errorf("sender should be waiting for the other device, got state=%q note=%q", got.State, got.Note)
	}
	if len(inboxFiles(t, r.inboxDir)) != 0 {
		t.Fatal("nothing may be written before the person accepts")
	}
	r.approvals.Decide(req.ID, true)
	waitState(t, r.mgr, v.ID, StateDone, 20*time.Second)

	files := inboxFiles(t, r.inboxDir)
	if files["photo.bin"] != sum(data) || files["note.txt"] != sum([]byte("hi")) {
		t.Fatalf("inbox contents wrong: %v", files)
	}
	waitFor(t, "both sides to end the Connect session", 5*time.Second, func() bool {
		return !sessionOpen(r.trS, r.recvID.DeviceID) && r.trR.Access(r.sendID.DeviceID).SessionID == ""
	})
}

func TestConnectPushRejected(t *testing.T) {
	r := newPushRig(t)
	r.connect(t, false, false)
	v := r.push(t, writeTemp(t, "secret.bin", randBytes(62, 100<<10)))
	req := r.waitPrompt(t)
	r.approvals.Decide(req.ID, false)

	got := waitState(t, r.mgr, v.ID, StateFailed, 10*time.Second)
	if !strings.Contains(got.Error, "declined") {
		t.Errorf("sender should be told the transfer was declined, got %q", got.Error)
	}
	if len(inboxFiles(t, r.inboxDir)) != 0 {
		t.Error("a rejected push must leave the Inbox empty")
	}
	if !sessionOpen(r.trS, r.recvID.DeviceID) {
		t.Error("a rejected push is not a completed transfer; the session stays")
	}
}

func TestPushPromptTimesOut(t *testing.T) {
	r := newPushRig(t)
	r.approvals.SetTimeout(150 * time.Millisecond)
	r.connect(t, false, false)
	v := r.push(t, writeTemp(t, "x.bin", []byte("x")))
	got := waitState(t, r.mgr, v.ID, StateFailed, 10*time.Second)
	if !strings.Contains(got.Error, "did not answer") {
		t.Errorf("want 'did not answer in time', got %q", got.Error)
	}
}

// "Keep connected" on either side keeps that side's session open.
func TestKeepConnectedSurvivesTheTransfer(t *testing.T) {
	r := newPushRig(t)
	sendSess, recvSess := r.connect(t, false, true) // the receiver keeps it
	_ = sendSess
	v := r.push(t, writeTemp(t, "k.bin", []byte("keep")))
	r.approvals.Decide(r.waitPrompt(t).ID, true)
	waitState(t, r.mgr, v.ID, StateDone, 20*time.Second)
	waitFor(t, "the sender to end its side", 5*time.Second, func() bool { return !sessionOpen(r.trS, r.recvID.DeviceID) })
	time.Sleep(100 * time.Millisecond)
	if _, ok := r.trR.Get(recvSess); !ok {
		t.Error("the receiver chose Keep connected, so its session must stay open")
	}

	r2 := newPushRig(t)
	r2.connect(t, true, false) // the sender keeps it
	v2 := r2.push(t, writeTemp(t, "k2.bin", []byte("keep")))
	r2.approvals.Decide(r2.waitPrompt(t).ID, true)
	waitState(t, r2.mgr, v2.ID, StateDone, 20*time.Second)
	time.Sleep(200 * time.Millisecond)
	if !sessionOpen(r2.trS, r2.recvID.DeviceID) {
		t.Error("the sender chose Keep connected, so its session must stay open")
	}
}

// Paired peers push without a prompt unless the transfer is above the limit
// the receiver set (ask-over).
func TestPairedPushAsksOnlyAboveTheLimit(t *testing.T) {
	r := newPushRig(t)
	r.trR.Pair(trust.Entry{
		DeviceID: "sender-dev", Name: "Sender PC", Fingerprint: r.sendID.DeviceID, Mode: trust.ModePair,
		Permissions: trust.Permissions{Browse: true, Push: true, AskOver: 1000},
	})

	small := r.push(t, writeTemp(t, "small.bin", randBytes(63, 500)))
	waitState(t, r.mgr, small.ID, StateDone, 20*time.Second)
	if n := len(r.approvals.Pending()); n != 0 {
		t.Fatalf("a push under the limit must not prompt, %d pending", n)
	}

	big := r.push(t, writeTemp(t, "big.bin", randBytes(64, 50_000)))
	req := r.waitPrompt(t)
	if req.Reason != "large" {
		t.Errorf("reason = %q, want large", req.Reason)
	}
	r.approvals.Decide(req.ID, true)
	waitState(t, r.mgr, big.ID, StateDone, 20*time.Second)
	if got := inboxFiles(t, r.inboxDir); len(got) != 2 {
		t.Errorf("expected both files in the Inbox, got %v", got)
	}
	// Pairing is persistent: no session to close.
	if _, ok := r.trR.Entry(r.sendID.DeviceID); !ok {
		t.Error("a finished transfer must not unpair anyone")
	}
}

// A folder push lists every file in one offer. Thousands of files must not hit
// a body limit (the receiver once capped the offer at 1 MB, about 8,000 files).
func TestPushOfferWithManyFiles(t *testing.T) {
	r := newPushRig(t)
	r.trR.Pair(trust.Entry{
		DeviceID: "sender-dev", Name: "Sender PC", Fingerprint: r.sendID.DeviceID, Mode: trust.ModePair,
		Permissions: trust.Permissions{Browse: true, Push: true},
	})
	files := make([]inbox.FileReq, 15000)
	for i := range files {
		files[i] = inbox.FileReq{RelPath: fmt.Sprintf("some/folder/with/a/long/path/file-%06d.dat", i), Size: 100, MTime: time.Now()}
	}
	offer, err := r.client.PushOffer(context.Background(), r.host, r.port, r.recvID.DeviceID, files)
	if err != nil {
		t.Fatalf("a 15,000 file offer must be accepted: %v", err)
	}
	if len(offer.Files) != len(files) {
		t.Errorf("offer answered %d files, want %d", len(offer.Files), len(files))
	}
}

// Large and small files, a nested folder and an empty file travel together:
// small ones in one request each, the large one in chunks, all verified.
func TestMixedPush(t *testing.T) {
	r := newPushRig(t)
	r.trR.Pair(trust.Entry{
		DeviceID: "sender-dev", Name: "Sender PC", Fingerprint: r.sendID.DeviceID, Mode: trust.ModePair,
		Permissions: trust.Permissions{Browse: true, Push: true},
	})
	dir := t.TempDir()
	want := map[string]string{}
	add := func(rel string, data []byte) {
		p := filepath.Join(dir, "pack", filepath.FromSlash(rel))
		_ = os.MkdirAll(filepath.Dir(p), 0o755)
		if err := os.WriteFile(p, data, 0o644); err != nil {
			t.Fatal(err)
		}
		want[filepath.Base(rel)] = sum(data)
	}
	add("big.bin", randBytes(81, 3<<20)) // > 512 KB: chunked path
	add("small1.txt", randBytes(82, 10_000))
	add("sub/small2.txt", randBytes(83, 500))
	add("sub/deep/empty.txt", nil)
	for i := 0; i < 60; i++ {
		add(fmt.Sprintf("many/f%02d.dat", i), randBytes(int64(100+i), 200+i*7))
	}
	v := r.push(t, filepath.Join(dir, "pack"))
	waitState(t, r.mgr, v.ID, StateDone, 30*time.Second)
	got := inboxFiles(t, r.inboxDir)
	if len(got) != len(want) {
		t.Fatalf("inbox has %d files, want %d", len(got), len(want))
	}
	for name, h := range want {
		if got[name] != h {
			t.Errorf("%s differs in the Inbox", name)
		}
	}
}
