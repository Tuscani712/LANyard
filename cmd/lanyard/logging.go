package main

import (
	"io"
	"log/slog"
	"os"
	"path/filepath"

	"lanyard/internal/xferlog"
)

const (
	maxLogBytes  = 5 << 20 // per file
	logRotations = 3       // archives kept: lanyard.log.1 … .3
)

// newLogger logs to <data-dir>/lanyard.log and to stderr. The file comes first
// because a windowless Windows build has no usable stderr, and io.MultiWriter
// stops at the first writer that fails. The file rotates at 5 MB and keeps
// three archives, so the on-disk cap is about 5 MB × 4.
func newLogger(dir string) *slog.Logger {
	var w io.Writer = os.Stderr
	if dir != "" && os.MkdirAll(dir, 0o700) == nil {
		path := filepath.Join(dir, "lanyard.log")
		if rw, err := xferlog.NewRotatingWriter(path, maxLogBytes, logRotations); err == nil {
			w = io.MultiWriter(rw, os.Stderr)
		}
	}
	return slog.New(slog.NewTextHandler(w, &slog.HandlerOptions{Level: slog.LevelInfo}))
}
