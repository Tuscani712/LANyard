//go:build !windows

package uiserver

import "errors"

var errNoPicker = errors.New("a native file dialog is not available on this system; type the path instead")

// PickHook is set by a platform's native window (Linux/GTK) to supply the
// operating system's own file dialog. Nil means "type the path instead".
var PickHook func(kind, title, start string) ([]string, error)

func pickPaths(kind, title, start string) ([]string, error) {
	if h := PickHook; h != nil {
		return h(kind, title, start)
	}
	return nil, errNoPicker
}
