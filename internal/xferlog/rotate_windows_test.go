//go:build windows

package xferlog

import (
	"bytes"
	"log/slog"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"golang.org/x/sys/windows"
)

// A reader that shares read and write but NOT delete blocks the rename of the
// live log on Windows (ERROR_SHARING_VIOLATION). The old writer swallowed that
// error and silently kept appending to the live file. Rotation must instead
// fall back to copy+truncate and log a WARN, which is what this test proves.
func TestRotatingWriterFallsBackWhenRenameBlocked(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "lanyard.log")
	w, err := NewRotatingWriter(path, 50, 3)
	if err != nil {
		t.Fatalf("NewRotatingWriter: %v", err)
	}
	defer w.Close()

	var logs bytes.Buffer
	w.SetLogger(slog.New(slog.NewTextHandler(&logs, nil)))

	first := strings.Repeat("A", 40)
	if _, err := w.Write([]byte(first)); err != nil {
		t.Fatalf("seed write: %v", err)
	}

	// Hold the live log without FILE_SHARE_DELETE: the rename fails, but the
	// copy+truncate fallback (which only needs read and write sharing) works.
	p, _ := windows.UTF16PtrFromString(path)
	h, err := windows.CreateFile(p, windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil,
		windows.OPEN_EXISTING, 0, 0)
	if err != nil {
		t.Fatalf("CreateFile without FILE_SHARE_DELETE: %v", err)
	}
	defer windows.CloseHandle(h)

	second := strings.Repeat("B", 40)
	if _, err := w.Write([]byte(second)); err != nil {
		t.Fatalf("rotating write under held read handle: %v", err)
	}

	archive, err := os.ReadFile(path + ".1")
	if err != nil {
		t.Fatalf("fallback did not create the archive: %v", err)
	}
	if string(archive) != first {
		t.Fatalf("archive = %q, want the pre-rotation contents", archive)
	}
	live, err := os.ReadFile(path)
	if err != nil {
		t.Fatalf("read live log: %v", err)
	}
	if string(live) != second {
		t.Fatalf("live log = %q, want only the post-rotation write", live)
	}
	if !strings.Contains(logs.String(), "fallback") {
		t.Fatalf("rotation did not log a WARN about the fallback:\n%s", logs.String())
	}
}

// A reader that shares delete (what the Go stdlib requests) does not block
// rotation, so the normal rename path is used and no fallback is needed.
func TestRotatingWriterRotatesUnderSharedReadHandle(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "lanyard.log")
	w, err := NewRotatingWriter(path, 50, 3)
	if err != nil {
		t.Fatalf("NewRotatingWriter: %v", err)
	}
	defer w.Close()
	if _, err := w.Write(make([]byte, 40)); err != nil {
		t.Fatalf("seed write: %v", err)
	}

	p, _ := windows.UTF16PtrFromString(path)
	h, err := windows.CreateFile(p, windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE|windows.FILE_SHARE_DELETE,
		nil, windows.OPEN_EXISTING, 0, 0)
	if err != nil {
		t.Fatalf("CreateFile with FILE_SHARE_DELETE: %v", err)
	}
	defer windows.CloseHandle(h)

	if _, err := w.Write(make([]byte, 40)); err != nil {
		t.Fatalf("rotating write: %v", err)
	}
	if _, err := os.Stat(path + ".1"); err != nil {
		t.Fatalf("archive not created despite FILE_SHARE_DELETE on the reader: %v", err)
	}
}
