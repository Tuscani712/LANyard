package main

import "log/slog"

// nativeUIOptions configures the native desktop window. On platforms without
// an implementation runNativeUI reports that it is unavailable.
type nativeUIOptions struct {
	URL      string // full loopback URL including the one-time token
	Title    string
	DataPath string // WebView2 user-data folder (Windows)
	OnQuit   func()
	Done     <-chan struct{}
	Log      *slog.Logger
}
