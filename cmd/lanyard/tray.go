package main

import "log/slog"

// trayOptions configures the system-tray icon. On platforms without an
// implementation startTray is a no-op.
type trayOptions struct {
	// IconPath is where the .ico is written so the OS can load it by path.
	IconPath string
	OnOpen   func()
	OnQuit   func()
	Log      *slog.Logger
}
