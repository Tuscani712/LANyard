package shares

import (
	"errors"
	"os"
	"path/filepath"
	"testing"
	"time"

	"lanyard/internal/config"
)

func newTestManager(t *testing.T, selfID string) *Manager {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	m := New(cfg, selfID, nil)
	t.Cleanup(func() { m.StopAll() })
	return m
}

func TestCleanRel(t *testing.T) {
	good := map[string]string{
		"":               "",
		"a.txt":          "a.txt",
		"sub/b.bin":      "sub/b.bin",
		"./sub//b.bin":   "sub/b.bin",
		"..notes.txt":    "..notes.txt", // valid name, not traversal
		"deep/a/b/c.txt": "deep/a/b/c.txt",
	}
	for in, want := range good {
		got, err := CleanRel(in)
		if err != nil {
			t.Errorf("CleanRel(%q) unexpected error: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("CleanRel(%q) = %q, want %q", in, got, want)
		}
	}
	bad := []string{"..", "../x", "a/../../b", "/etc/passwd", `a\b`, "C:/x", "a\x00b", "ads:stream"}
	for _, in := range bad {
		if _, err := CleanRel(in); !errors.Is(err, ErrBadPath) {
			t.Errorf("CleanRel(%q) expected ErrBadPath, got %v", in, err)
		}
	}
}

func TestAddBrowseAndConfine(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "a.txt"), "hello")
	mustMkdir(t, filepath.Join(root, "sub"))
	writeFile(t, filepath.Join(root, "sub", "b.bin"), "xyz")

	m := newTestManager(t, "self")
	sh, err := m.Add(root, AddOptions{LifetimeType: LifetimeUntilStopped})
	if err != nil {
		t.Fatalf("Add: %v", err)
	}
	if sh.Kind != "folder" || sh.Label == "" {
		t.Fatalf("unexpected share: %+v", sh)
	}

	tree, err := sh.Tree("")
	if err != nil {
		t.Fatalf("Tree: %v", err)
	}
	if len(tree) != 2 {
		t.Fatalf("Tree returned %d entries, want 2", len(tree))
	}
	if !tree[0].IsDir {
		t.Errorf("expected directories first, got %+v", tree)
	}

	man, total, err := sh.Manifest("")
	if err != nil {
		t.Fatalf("Manifest: %v", err)
	}
	if len(man) != 2 || total != int64(len("hello")+len("xyz")) {
		t.Fatalf("Manifest files=%d total=%d", len(man), total)
	}

	f, info, err := sh.OpenFile("sub/b.bin")
	if err != nil {
		t.Fatalf("OpenFile: %v", err)
	}
	if info.Size() != 3 {
		t.Errorf("size = %d, want 3", info.Size())
	}
	f.Close()

	if _, _, err := sh.OpenFile("../escape"); !errors.Is(err, ErrBadPath) {
		t.Errorf("traversal should be rejected, got %v", err)
	}
}

func TestSingleFileShare(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "note.pdf"), "pdf-bytes")
	m := newTestManager(t, "self")
	sh, err := m.Add(filepath.Join(root, "note.pdf"), AddOptions{LifetimeType: LifetimePersistent})
	if err != nil {
		t.Fatalf("Add: %v", err)
	}
	if sh.Kind != "file" {
		t.Fatalf("kind = %q, want file", sh.Kind)
	}
	man, total, err := sh.Manifest("")
	if err != nil {
		t.Fatalf("Manifest: %v", err)
	}
	if len(man) != 1 || man[0].Path != "" || total != int64(len("pdf-bytes")) {
		t.Fatalf("manifest = %+v total=%d", man, total)
	}
	if _, _, err := sh.OpenFile("anything"); !errors.Is(err, ErrBadPath) {
		t.Errorf("file share should reject sub-paths, got %v", err)
	}
}

func TestTimedExpiry(t *testing.T) {
	m := newTestManager(t, "self")
	base := time.Now()
	m.now = func() time.Time { return base }

	sh, err := m.Add(t.TempDir(), AddOptions{LifetimeType: LifetimeTimed, DurationSec: 10})
	if err != nil {
		t.Fatalf("Add timed: %v", err)
	}
	if _, ok := m.Get(sh.ShareID); !ok {
		t.Fatal("share should be active before expiry")
	}
	m.now = func() time.Time { return base.Add(11 * time.Second) }
	if !m.sweep() {
		t.Fatal("sweep should remove the expired share")
	}
	if _, ok := m.Get(sh.ShareID); ok {
		t.Fatal("share should be gone after expiry")
	}

	if _, err := m.Add(t.TempDir(), AddOptions{LifetimeType: LifetimeTimed, DurationSec: 5}); err == nil {
		t.Error("timed share below 10s must be rejected")
	}
}

func TestRisky(t *testing.T) {
	if _, err := os.UserHomeDir(); err == nil {
		if Risky(mustHome(t)) == "" {
			t.Error("home directory should be flagged risky")
		}
	}
	if Risky(t.TempDir()) != "" {
		t.Error("temp dir should not be risky")
	}
}

func mustHome(t *testing.T) string {
	t.Helper()
	h, err := os.UserHomeDir()
	if err != nil {
		t.Skip("no home dir")
	}
	return h
}

func writeFile(t *testing.T, path, content string) {
	t.Helper()
	if err := os.WriteFile(path, []byte(content), 0o644); err != nil {
		t.Fatal(err)
	}
}

func mustMkdir(t *testing.T, path string) {
	t.Helper()
	if err := os.MkdirAll(path, 0o755); err != nil {
		t.Fatal(err)
	}
}

// Manifest must report paths relative to the share root even for a subfolder,
// matching Tree and OpenFile, so a pull of sub/dir re-requests the right files.
func TestManifestSubpathPathsAreShareRootRelative(t *testing.T) {
	root := t.TempDir()
	mustMkdir(t, filepath.Join(root, "sub", "dir", "nested"))
	writeFile(t, filepath.Join(root, "sub", "dir", "a.txt"), "alpha")
	writeFile(t, filepath.Join(root, "sub", "dir", "nested", "b.bin"), "bravo")
	writeFile(t, filepath.Join(root, "other.txt"), "nope")

	m := newTestManager(t, "self")
	sh, err := m.Add(root, AddOptions{LifetimeType: LifetimeUntilStopped})
	if err != nil {
		t.Fatalf("Add: %v", err)
	}
	man, _, err := sh.Manifest("sub/dir")
	if err != nil {
		t.Fatalf("Manifest: %v", err)
	}
	got := map[string]bool{}
	for _, e := range man {
		got[e.Path] = true
	}
	for _, want := range []string{"sub/dir/a.txt", "sub/dir/nested/b.bin"} {
		if !got[want] {
			t.Errorf("manifest missing share-root-relative path %q; got %v", want, got)
		}
	}
	for _, p := range man {
		if _, _, err := sh.OpenFile(p.Path); err != nil {
			t.Errorf("manifest path %q must open against the share root: %v", p.Path, err)
		}
	}
}

// Traversal and absolute paths are rejected on the manifest path too.
func TestManifestRejectsBadPaths(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "a.txt"), "x")
	m := newTestManager(t, "self")
	sh, err := m.Add(root, AddOptions{LifetimeType: LifetimeUntilStopped})
	if err != nil {
		t.Fatalf("Add: %v", err)
	}
	for _, bad := range []string{"../escape", "/etc/passwd", "a/../../b"} {
		if _, _, err := sh.Manifest(bad); !errors.Is(err, ErrBadPath) {
			t.Errorf("Manifest(%q) expected ErrBadPath, got %v", bad, err)
		}
	}
}

// A symlink inside the share is never followed or listed, and cannot be opened.
func TestSymlinksAreNotFollowed(t *testing.T) {
	root := t.TempDir()
	writeFile(t, filepath.Join(root, "real.txt"), "real")
	outside := filepath.Join(t.TempDir(), "secret.txt")
	writeFile(t, outside, "secret")
	if err := os.Symlink(outside, filepath.Join(root, "link.txt")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}
	if err := os.Symlink(filepath.Join(root, "sub"), filepath.Join(root, "dirlink")); err != nil {
		t.Skipf("symlinks unavailable: %v", err)
	}

	m := newTestManager(t, "self")
	sh, err := m.Add(root, AddOptions{LifetimeType: LifetimeUntilStopped})
	if err != nil {
		t.Fatalf("Add: %v", err)
	}
	man, _, err := sh.Manifest("")
	if err != nil {
		t.Fatalf("Manifest: %v", err)
	}
	for _, e := range man {
		if e.Path == "link.txt" || e.Path == "dirlink" {
			t.Errorf("symlink %q must not be listed", e.Path)
		}
	}
	if _, _, err := sh.OpenFile("link.txt"); err == nil {
		t.Fatal("opening a symlink must fail")
	}
}
