package main

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"
)

const maxLogBytes = 5 << 20

// newLogger logs to <data-dir>/lanyard.log and to stderr. The file comes first
// because a windowless Windows build has no usable stderr, and io.MultiWriter
// stops at the first writer that fails. The log is rotated once at 5 MB.
func newLogger(dir string) *slog.Logger {
	var w io.Writer = os.Stderr
	if dir != "" && os.MkdirAll(dir, 0o700) == nil {
		path := filepath.Join(dir, "lanyard.log")
		if fi, err := os.Stat(path); err == nil && fi.Size() > maxLogBytes {
			_ = os.Rename(path, path+".1")
		}
		if f, err := os.OpenFile(path, os.O_CREATE|os.O_WRONLY|os.O_APPEND, 0o600); err == nil {
			w = io.MultiWriter(f, os.Stderr)
		}
	}
	return slog.New(slog.NewTextHandler(w, &slog.HandlerOptions{Level: slog.LevelInfo}))
}
