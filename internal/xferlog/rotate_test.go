package xferlog

import (
	"os"
	"path/filepath"
	"testing"
)

// The rotating writer keeps at most `rotations` archives and never lets a file
// grow past the cap: writing several times past the limit produces name.1 … .3
// and deletes the oldest.
func TestRotatingWriterCapsAndRotates(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "lanyard.log")
	w, err := NewRotatingWriter(path, 100, 3)
	if err != nil {
		t.Fatalf("NewRotatingWriter: %v", err)
	}
	defer w.Close()

	chunk := make([]byte, 60)
	for i := range chunk {
		chunk[i] = 'x'
	}
	for i := 0; i < 5; i++ {
		if _, err := w.Write(chunk); err != nil {
			t.Fatalf("Write %d: %v", i, err)
		}
	}

	for _, name := range []string{path, path + ".1", path + ".2", path + ".3"} {
		fi, err := os.Stat(name)
		if err != nil {
			t.Fatalf("expected %s to exist: %v", name, err)
		}
		if fi.Size() > 100 {
			t.Errorf("%s is %d bytes, over the 100-byte cap", filepath.Base(name), fi.Size())
		}
	}
	if _, err := os.Stat(path + ".4"); !os.IsNotExist(err) {
		t.Errorf("a fourth archive was kept (err=%v)", err)
	}
}

// A file smaller than the cap is not rotated.
func TestRotatingWriterNoRotateUnderCap(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "lanyard.log")
	w, err := NewRotatingWriter(path, 1000, 3)
	if err != nil {
		t.Fatalf("NewRotatingWriter: %v", err)
	}
	defer w.Close()
	if _, err := w.Write([]byte("small")); err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(path + ".1"); !os.IsNotExist(err) {
		t.Errorf("rotation happened under the cap (err=%v)", err)
	}
}
