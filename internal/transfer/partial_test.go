package transfer

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"

	"lanyard/internal/shares"
)

// Cancelling a download is terminal: its partial spool must be deleted so no
// orphaned .lanpart/.lanstate and no normal-looking file is left behind.
func TestCancelDownloadDeletesPartialFiles(t *testing.T) {
	m := historyManager(t)
	_, cancel := context.WithCancel(context.Background())
	defer cancel()

	dest := t.TempDir()
	sub := filepath.Join(dest, "sub")
	if err := os.MkdirAll(sub, 0o755); err != nil {
		t.Fatal(err)
	}
	final := filepath.Join(sub, "f.bin")
	for _, p := range []string{final + ".lanpart", final + ".lanstate"} {
		if err := os.WriteFile(p, []byte("partial"), 0o644); err != nil {
			t.Fatal(err)
		}
	}

	m.mu.Lock()
	m.jobs["dl"] = &Job{
		ID: "dl", Direction: "download", State: StateTransferring,
		Dest: dest, cancel: cancel, wake: make(chan struct{}, 1),
		Files: []*FileJob{{Rel: "sub/f.bin", Local: "sub/f.bin", Size: 10, State: FilePartial}},
	}
	m.mu.Unlock()

	// deletePartials=false: a cancelled download is terminal, so the partial is
	// still removed (the keep-partial option only applied to a resumable pause).
	if err := m.Cancel("dl", false); err != nil {
		t.Fatalf("Cancel: %v", err)
	}

	if _, err := os.Stat(final + ".lanpart"); !os.IsNotExist(err) {
		t.Errorf("the .lanpart file should be gone after Cancel, err=%v", err)
	}
	if _, err := os.Stat(final + ".lanstate"); !os.IsNotExist(err) {
		t.Errorf("the .lanstate file should be gone after Cancel, err=%v", err)
	}
	if _, err := os.Stat(final); !os.IsNotExist(err) {
		t.Errorf("no normal-looking final file may be left behind, err=%v", err)
	}
	if v, ok := m.Get("dl"); !ok || v.State != StateCancelled {
		t.Fatalf("cancelled row = %+v ok=%v, want a Cancelled history entry", v, ok)
	}
}

// A pull that fails because the sender stopped the share keeps its .lanpart for
// a later resume, but must never leave a normal-looking final file in place.
func TestFailedPullLeavesOnlyPartial(t *testing.T) {
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

	waitState(t, e.mgr, v.ID, StateFailed, 20*time.Second)

	if _, err := os.Stat(filepath.Join(dest, "big.bin.lanpart")); err != nil {
		t.Errorf("a failed pull should keep its .lanpart for resume: %v", err)
	}
	if _, err := os.Stat(filepath.Join(dest, "big.bin")); !os.IsNotExist(err) {
		t.Errorf("a failed pull must not leave a normal-looking final file, err=%v", err)
	}
}
