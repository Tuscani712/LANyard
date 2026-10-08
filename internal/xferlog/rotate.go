package xferlog

import (
	"fmt"
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
	w := &RotatingWriter{path: path, maxBytes: maxBytes, rotations: rotations}
	if err := w.open(); err != nil {
		return nil, err
	}
	return w, nil
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
func (w *RotatingWriter) rotate() {
	if w.f != nil {
		_ = w.f.Close()
		w.f = nil
	}
	if w.rotations <= 0 {
		_ = os.Remove(w.path)
	} else {
		_ = os.Remove(fmt.Sprintf("%s.%d", w.path, w.rotations))
		for i := w.rotations - 1; i >= 1; i-- {
			_ = os.Rename(fmt.Sprintf("%s.%d", w.path, i), fmt.Sprintf("%s.%d", w.path, i+1))
		}
		_ = os.Rename(w.path, w.path+".1")
	}
	w.size = 0
	_ = w.open()
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
