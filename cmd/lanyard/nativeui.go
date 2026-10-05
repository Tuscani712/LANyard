package main

import "log/slog"

// nativeUIOptions configures the native desktop window. On platforms without
// an implementation runNativeUI reports that it is unavailable.
type nativeUIOptions struct {
	Base   string // e.g. http://127.0.0.1:47810
	Token  string
	Title  string
	OnQuit func()
	OnOpen func()
	Done   <-chan struct{}
	Log    *slog.Logger
}
