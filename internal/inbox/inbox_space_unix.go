//go:build !windows

package inbox

import "syscall"

// freeSpace reports the bytes available to the current user on the volume
// holding path, or 0 if it cannot be determined.
func freeSpace(path string) int64 {
	var st syscall.Statfs_t
	if err := syscall.Statfs(path, &st); err != nil {
		return 0
	}
	return int64(st.Bavail) * int64(st.Bsize)
}
