package main

import (
	"log/slog"
	"sync/atomic"
)

// minimizeToTray is the "minimize to system tray" setting: when set, the
// native window hides to the tray on minimize or close instead of using the
// taskbar or quitting.
var minimizeToTray atomic.Bool

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
