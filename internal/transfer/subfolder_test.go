package transfer

import (
	"context"
	"os"
	"path/filepath"
	"testing"
	"time"
)

// Pulling a specific subfolder must fetch the files with the same layout: the
// manifest's paths are relative to the share root, like Tree's, so the
// downloader re-requests them correctly.
func TestPullSubfolderPath(t *testing.T) {
	e := newEnv(t, func(int) connPlan { return connPlan{} })
	a := []byte("alpha")
	b := []byte("bravo bravo")
	writeSrc(t, filepath.Join(e.srcDir, "sub", "dir", "a.txt"), a)
	writeSrc(t, filepath.Join(e.srcDir, "sub", "dir", "nested", "b.bin"), b)
	writeSrc(t, filepath.Join(e.srcDir, "other.txt"), []byte("not wanted"))

	dest := t.TempDir()
	v, err := e.mgr.Create(context.Background(), CreateParams{
		PeerID: e.peerFP, PeerName: "peer", Host: "127.0.0.1", Port: e.proxy.Port(),
		ShareID: e.share.ShareID, ShareLabel: e.share.Label, Dest: dest,
		Paths: []string{"sub/dir"},
	})
	if err != nil {
		t.Fatalf("Create: %v", err)
	}
	waitState(t, e.mgr, v.ID, StateDone, 20*time.Second)

	if got, err := os.ReadFile(filepath.Join(dest, "sub", "dir", "a.txt")); err != nil || string(got) != string(a) {
		t.Fatalf("a.txt: got %q err=%v", got, err)
	}
	if got, err := os.ReadFile(filepath.Join(dest, "sub", "dir", "nested", "b.bin")); err != nil || string(got) != string(b) {
		t.Fatalf("b.bin: got %q err=%v", got, err)
	}
	if _, err := os.Stat(filepath.Join(dest, "other.txt")); err == nil {
		t.Fatal("a file outside the requested subfolder was downloaded")
	}
}
