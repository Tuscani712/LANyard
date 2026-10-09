//go:build !windows

package uiserver

import "errors"

var errNoPicker = errors.New("a native file dialog is not available in this window; open LANyard as a desktop app to choose files")

// PickHook is set by a platform's native window (Linux/GTK) to supply the
// operating system's own file dialog. Nil means there is no file picker (for
// example a plain browser session); callers show an in-app message.
var PickHook func(kind, title, start string) ([]string, error)

func pickPaths(kind, title, start string) ([]string, error) {
	if h := PickHook; h != nil {
		return h(kind, title, start)
	}
	return nil, errNoPicker
}
