package transfer

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"lanyard/internal/peerapi"
	"lanyard/internal/shares"
)

// testClock lets a test move the sharer's clock without sleeping.
type testClock struct {
	mu sync.Mutex
	t  time.Time
}

func newTestClock() *testClock { return &testClock{t: time.Now()} }

func (c *testClock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *testClock) Advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

// extraShare publishes another folder from the same sharer.
func (e *env) extraShare(t *testing.T, opt shares.AddOptions, files map[string][]byte) (*shares.Share, string) {
	t.Helper()
	dir := t.TempDir()
	for name, b := range files {
		writeSrc(t, filepath.Join(dir, filepath.FromSlash(name)), b)
	}
	sh, err := e.shMgr.Add(dir, opt)
	if err != nil {
		t.Fatal(err)
	}
	return sh, dir
}

func (e *env) createFor(sh *shares.Share, dest string) (*View, error) {
	return e.mgr.Create(context.Background(), CreateParams{
		PeerID: e.peerFP, PeerName: "peer", Host: "127.0.0.1", Port: e.proxy.Port(),
		ShareID: sh.ShareID, ShareLabel: sh.Label, Dest: dest,
	})
}

func waitFor(t *testing.T, what string, within time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(within)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(15 * time.Millisecond)
	}
}

// A one-time share is retired only by a verified, completed download:
// pausing, resuming and interruptions leave it alone.
func TestOneTimeConsumedOnlyByVerifiedCompletion(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 2 << 20}
		}
		return connPlan{}
	})
	data := map[string][]byte{"one.bin": randBytes(31, 6<<20), "two.bin": randBytes(32, 3<<20)}
	sh, _ := e.extraShare(t, shares.AddOptions{LifetimeType: shares.LifetimeOneTime, Label: "once"}, data)

	dest := t.TempDir()
	v, err := e.createFor(sh, dest)
	if err != nil {
		t.Fatal(err)
	}
	waitDone(t, e.mgr, v.ID, 1<<20, 10*time.Second)
	if err := e.mgr.Pause(v.ID); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e.mgr, v.ID)
	if _, ok := e.shMgr.Get(sh.ShareID); !ok {
		t.Fatal("an interrupted/paused download must not consume a one-time share")
	}

	e.proxy.Release()
	if err := e.mgr.Resume(v.ID); err != nil {
		t.Fatal(err)
	}
	waitState(t, e.mgr, v.ID, StateDone, 30*time.Second)
	waitFor(t, "the sender to retire the one-time share", 5*time.Second, func() bool {
		_, ok := e.shMgr.Get(sh.ShareID)
		return !ok
	})
	for name, b := range data {
		if fileSum(t, filepath.Join(dest, name)) != sum(b) {
			t.Errorf("%s differs", name)
		}
	}

	// A second download of the consumed share is refused with a clear reason.
	_, err = e.createFor(sh, t.TempDir())
	var se *peerapi.StatusError
	if !errors.As(err, &se) || se.Code != 410 || !strings.Contains(se.Msg, "already been downloaded") {
		t.Fatalf("second download should get 410 'already been downloaded', got %v", err)
	}
}

// The sender only trusts digests it can confirm, so a made-up "I'm done"
// cannot burn someone else's one-time share.
func TestCompletionRejectedWithoutProof(t *testing.T) {
	e := newEnv(t, nil)
	sh, _ := e.extraShare(t, shares.AddOptions{LifetimeType: shares.LifetimeOneTime, Label: "once"},
		map[string][]byte{"a.bin": randBytes(33, 1<<20)})
	ctx := context.Background()
	port := e.proxy.Port()

	cases := map[string][]peerapi.VerifiedFile{
		"wrong digest": {{Path: "a.bin", SHA256: strings.Repeat("0", 64)}},
		"unknown file": {{Path: "nope.bin", SHA256: strings.Repeat("0", 64)}},
		"nothing":      nil,
	}
	for name, files := range cases {
		consumed, err := e.client.CompleteShare(ctx, "127.0.0.1", port, e.peerFP, sh.ShareID, files)
		if err == nil || consumed {
			t.Errorf("%s: completion should be rejected (consumed=%v err=%v)", name, consumed, err)
		}
	}
	if _, ok := e.shMgr.Get(sh.ShareID); !ok {
		t.Fatal("rejected completions must leave the share alone")
	}

	// A real digest consumes it; the same call on a normal share is a no-op.
	good := sum(randBytes(33, 1<<20))
	consumed, err := e.client.CompleteShare(ctx, "127.0.0.1", port, e.peerFP, sh.ShareID,
		[]peerapi.VerifiedFile{{Path: "a.bin", SHA256: good}})
	if err != nil || !consumed {
		t.Fatalf("a correct digest should consume the share: consumed=%v err=%v", consumed, err)
	}
	consumed, err = e.client.CompleteShare(ctx, "127.0.0.1", port, e.peerFP, e.share.ShareID,
		[]peerapi.VerifiedFile{{Path: "x", SHA256: good}})
	if err != nil || consumed {
		t.Fatalf("completion on a non-one-time share must be a harmless no-op, got consumed=%v err=%v", consumed, err)
	}
}

// A folder download already under way when the share expires is allowed to
// finish (grace); a brand-new download is refused with "expired".
func TestGraceLetsRunningFolderJobFinish(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 3 << 20}
		}
		return connPlan{}
	})
	clk := newTestClock()
	e.shMgr.SetClock(clk.Now)
	files := map[string][]byte{}
	for i := 0; i < 4; i++ {
		files[fmt.Sprintf("f%d.bin", i)] = randBytes(int64(40+i), 2<<20)
	}
	sh, _ := e.extraShare(t, shares.AddOptions{LifetimeType: shares.LifetimeTimed, DurationSec: 30, Label: "timed"}, files)

	dest := t.TempDir()
	v, err := e.createFor(sh, dest)
	if err != nil {
		t.Fatal(err)
	}
	waitDone(t, e.mgr, v.ID, 1<<20, 10*time.Second)

	clk.Advance(31 * time.Second) // the share expires mid-download
	views := e.shMgr.LocalViews()
	found := false
	for _, lv := range views {
		if lv.ShareID == sh.ShareID {
			found = true
			if lv.State != "finishing" || lv.EndReason != "expired" {
				t.Errorf("expired share should be 'finishing' (expired), got %s/%s", lv.State, lv.EndReason)
			}
		}
	}
	if !found {
		t.Fatal("an expired share with an active transfer must still be visible to its owner")
	}
	if got := len(e.shMgr.List()); got != 1 { // only the always-on base share is listed to peers
		t.Errorf("peers should see %d share(s), the expired one must be hidden; got %d", 1, got)
	}

	e.proxy.Release()
	waitState(t, e.mgr, v.ID, StateDone, 30*time.Second)
	for name, b := range files {
		if fileSum(t, filepath.Join(dest, name)) != sum(b) {
			t.Errorf("%s differs after finishing in the grace period", name)
		}
	}

	_, err = e.createFor(sh, t.TempDir())
	var se *peerapi.StatusError
	if !errors.As(err, &se) || se.Code != 410 || !strings.Contains(se.Msg, "expired") {
		t.Fatalf("a new download after expiry should get 410 'expired', got %v", err)
	}

	// Once nothing is transferring any more the share is retired.
	clk.Advance(shares.ActiveWindow + time.Second)
	waitFor(t, "sweep to retire the expired share", 5*time.Second, func() bool {
		e.shMgr.Sweep()
		for _, lv := range e.shMgr.LocalViews() {
			if lv.ShareID == sh.ShareID {
				return false
			}
		}
		return true
	})
}

// "Stop now" during the grace period cuts the running download, and the
// receiver is told why instead of retrying forever.
func TestStopNowEndsRunningDownload(t *testing.T) {
	e := newEnv(t, func(i int) connPlan {
		if i == 0 {
			return connPlan{holdAfter: 2 << 20}
		}
		return connPlan{}
	})
	clk := newTestClock()
	e.shMgr.SetClock(clk.Now)
	sh, _ := e.extraShare(t, shares.AddOptions{LifetimeType: shares.LifetimeTimed, DurationSec: 30, Label: "timed"},
		map[string][]byte{"big.bin": randBytes(50, 12<<20)})

	dest := t.TempDir()
	v, err := e.createFor(sh, dest)
	if err != nil {
		t.Fatal(err)
	}
	waitDone(t, e.mgr, v.ID, 1<<20, 10*time.Second)
	clk.Advance(31 * time.Second)
	if !e.shMgr.Stop(sh.ShareID) {
		t.Fatal("the finishing share should still be stoppable")
	}
	e.proxy.Release()

	got := waitState(t, e.mgr, v.ID, StateFailed, 20*time.Second)
	if !strings.Contains(got.Error, "stopped this share") {
		t.Errorf("receiver should be told the sender stopped the share, got %q", got.Error)
	}
	if _, err := os.Stat(filepath.Join(dest, "big.bin.lanpart")); err != nil {
		t.Error("the partial file should be kept so it can be reused")
	}
}
