//go:build !windows

package uiserver

import "os"

func isHiddenWindows(os.FileInfo) bool { return false }
