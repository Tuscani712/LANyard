//go:build !windows

package discovery

import (
	"runtime"
	"syscall"
)

func reusePort(network, address string, c syscall.RawConn) error {
	var serr error
	err := c.Control(func(fd uintptr) {
		serr = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
		if serr != nil {
			return
		}
		// SO_REUSEPORT is not exported by package syscall on every platform.
		switch runtime.GOOS {
		case "linux":
			_ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, 0xf, 1)
		case "darwin", "freebsd", "netbsd", "openbsd":
			_ = syscall.SetsockoptInt(int(fd), syscall.SOL_SOCKET, 0x200, 1)
		}
	})
	if err != nil {
		return err
	}
	return serr
}
