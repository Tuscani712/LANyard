//go:build windows

package uiserver

import "golang.org/x/sys/windows"

var procGetLogicalDrives = windows.NewLazySystemDLL("kernel32.dll").NewProc("GetLogicalDrives")

// freeDriveLetters lists the unused drive letters from E: through Z:, so the
// mount picker can offer only letters that are actually available. A: and B:
// (legacy floppies) and C: (the system drive) are never offered.
func freeDriveLetters() []string {
	r, _, _ := procGetLogicalDrives.Call()
	used := uint32(r)
	out := make([]string, 0, 22)
	for c := 'Z'; c >= 'E'; c-- {
		if used&(1<<uint(c-'A')) == 0 {
			out = append(out, string(c)+":")
		}
	}
	return out
}
