//go:build !windows

package uiserver

import "errors"

var errNoPicker = errors.New("a native file dialog is not available on this system; type the path instead")

func pickPaths(kind, title, start string) ([]string, error) { return nil, errNoPicker }
