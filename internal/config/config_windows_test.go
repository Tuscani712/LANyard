//go:build windows

package config

import (
	"os"
	"path/filepath"
	"testing"
	"time"

	"golang.org/x/sys/windows"
)

// holdWithoutDelete opens path for reading without FILE_SHARE_DELETE, so a
// rename onto it fails with a sharing violation until the handle is closed.
func holdWithoutDelete(t *testing.T, path string) windows.Handle {
	t.Helper()
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		t.Fatal(err)
	}
	h, err := windows.CreateFile(p, windows.GENERIC_READ,
		windows.FILE_SHARE_READ|windows.FILE_SHARE_WRITE, nil,
		windows.OPEN_EXISTING, 0, 0)
	if err != nil {
		t.Fatalf("CreateFile without FILE_SHARE_DELETE: %v", err)
	}
	return h
}

// A short retry lets the replace succeed once the holder releases the file.
func TestWriteFileAtomicRetriesUntilHolderReleases(t *testing.T) {
	oldRetries, oldDelay := renameRetries, renameRetryDelay
	renameRetries, renameRetryDelay = 20, 10*time.Millisecond
	defer func() { renameRetries, renameRetryDelay = oldRetries, oldDelay }()

	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	if err := os.WriteFile(path, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	h := holdWithoutDelete(t, path)
	released := make(chan struct{})
	go func() {
		time.Sleep(60 * time.Millisecond)
		windows.CloseHandle(h)
		close(released)
	}()

	if err := WriteFileAtomic(path, []byte("new"), 0o600); err != nil {
		t.Fatalf("WriteFileAtomic did not retry past a released holder: %v", err)
	}
	<-released
	got, err := os.ReadFile(path)
	if err != nil {
		t.Fatal(err)
	}
	if string(got) != "new" {
		t.Fatalf("contents = %q, want %q", got, "new")
	}
}

// If the holder never releases, the retry gives up and returns the error rather
// than discarding it.
func TestWriteFileAtomicFinalFailureUnderHeldHandle(t *testing.T) {
	oldRetries, oldDelay := renameRetries, renameRetryDelay
	renameRetries, renameRetryDelay = 3, 10*time.Millisecond
	defer func() { renameRetries, renameRetryDelay = oldRetries, oldDelay }()

	dir := t.TempDir()
	path := filepath.Join(dir, "config.json")
	if err := os.WriteFile(path, []byte("old"), 0o600); err != nil {
		t.Fatal(err)
	}
	h := holdWithoutDelete(t, path)
	defer windows.CloseHandle(h)

	if err := WriteFileAtomic(path, []byte("new"), 0o600); err == nil {
		t.Fatal("WriteFileAtomic returned nil while the destination was held")
	}
}
