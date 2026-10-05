//go:build windows

package inbox

import "golang.org/x/sys/windows"

// freeSpace reports the bytes available to the current user on the volume
// holding path, or 0 if it cannot be determined.
func freeSpace(path string) int64 {
	p, err := windows.UTF16PtrFromString(path)
	if err != nil {
		return 0
	}
	var free, total, avail uint64
	if err := windows.GetDiskFreeSpaceEx(p, &free, &total, &avail); err != nil {
		return 0
	}
	return int64(free)
}
