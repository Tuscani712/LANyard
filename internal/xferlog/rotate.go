package xferlog

import (
	"fmt"
	"io"
	"log/slog"
	"os"
	"sync"
)

// RotatingWriter is an io.Writer that appends to a file and rotates it when it
// would grow past maxBytes. It keeps `rotations` numbered archives
// (name.1 … name.N, name.1 the most recent); the oldest is deleted. The total
// on-disk cap is therefore about maxBytes × (rotations+1).
type RotatingWriter struct {
	mu        sync.Mutex
	path      string
	maxBytes  int64
	rotations int
	f         *os.File
	size      int64
	log       *slog.Logger
}

// NewRotatingWriter opens (creating if needed) path and returns a rotating
// writer. maxBytes ≤ 0 defaults to 5 MB; rotations < 0 is treated as 0 (keep
// only the current file, truncating in place).
func NewRotatingWriter(path string, maxBytes int64, rotations int) (*RotatingWriter, error) {
	if maxBytes <= 0 {
		maxBytes = 5 << 20
	}
	if rotations < 0 {
		rotations = 0
	}
	w := &RotatingWriter{path: path, maxBytes: maxBytes, rotations: rotations, log: slog.Default()}
	if err := w.open(); err != nil {
		return nil, err
	}
	return w, nil
}

// SetLogger sets the logger used to warn when a rotation has to fall back to
// copy+truncate because the live file could not be renamed. A nil logger is
// ignored (the default slog logger is kept).
func (w *RotatingWriter) SetLogger(l *slog.Logger) {
	if l != nil {
		w.log = l
	}
}

func (w *RotatingWriter) open() error {
	f, err := os.OpenFile(w.path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600)
	if err != nil {
		return err
	}
	w.f = f
	if fi, err := f.Stat(); err == nil {
		w.size = fi.Size()
	} else {
		w.size = 0
	}
	return nil
}

// Write appends p, rotating first when the file would exceed the cap.
func (w *RotatingWriter) Write(p []byte) (int, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		if err := w.open(); err != nil {
			return 0, err
		}
	}
	if w.size > 0 && w.size+int64(len(p)) > w.maxBytes {
		w.rotate()
	}
	n, err := w.f.Write(p)
	w.size += int64(n)
	return n, err
}

// rotate closes the current file, shifts the archives and reopens an empty one.
// A rename that fails (e.g. an external reader on Windows holds the live file
// without FILE_SHARE_DELETE) is never swallowed: the contents are copied into
// the newest archive and the live file is truncated, and a WARN is logged.
func (w *RotatingWriter) rotate() {
	if w.f != nil {
		_ = w.f.Close()
		w.f = nil
	}
	if w.rotations <= 0 {
		_ = os.Remove(w.path)
		w.size = 0
		_ = w.open()
		return
	}
	_ = os.Remove(fmt.Sprintf("%s.%d", w.path, w.rotations))
	for i := w.rotations - 1; i >= 1; i-- {
		_ = os.Rename(fmt.Sprintf("%s.%d", w.path, i), fmt.Sprintf("%s.%d", w.path, i+1))
	}
	if err := os.Rename(w.path, w.path+".1"); err != nil {
		if cerr := copyThenTruncate(w.path, w.path+".1"); cerr != nil {
			w.warn("log rotation failed: could not rename or copy the live log",
				"path", w.path, "rename_err", err, "copy_err", cerr)
		} else {
			w.warn("log rotation used the copy+truncate fallback after a rename failure",
				"path", w.path, "rename_err", err)
		}
	}
	// open() re-stats the file, so size is correct even when the fallback failed
	// and the live file was not truncated.
	_ = w.open()
}

func (w *RotatingWriter) warn(msg string, args ...any) {
	if w.log != nil {
		w.log.Warn(msg, args...)
	}
}

// copyThenTruncate copies src into dst and then truncates src to zero length.
// It is the rotation fallback for platforms where src cannot be renamed.
func copyThenTruncate(src, dst string) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	out, err := os.OpenFile(dst, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	if err != nil {
		return err
	}
	if _, err := io.Copy(out, in); err != nil {
		out.Close()
		return err
	}
	if err := out.Sync(); err != nil {
		out.Close()
		return err
	}
	if err := out.Close(); err != nil {
		return err
	}
	return os.Truncate(src, 0)
}

// Close closes the underlying file. Write reopens it if called again.
func (w *RotatingWriter) Close() error {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.f == nil {
		return nil
	}
	err := w.f.Close()
	w.f = nil
	return err
}
