//go:build windows

package uiserver

import (
	"os"
	"syscall"
)

func isHiddenWindows(info os.FileInfo) bool {
	if d, ok := info.Sys().(*syscall.Win32FileAttributeData); ok {
		return d.FileAttributes&syscall.FILE_ATTRIBUTE_HIDDEN != 0
	}
	return false
}
