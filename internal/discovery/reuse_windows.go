package discovery

import (
	"syscall"
)

// reusePort lets several instances share the beacon port (SO_REUSEADDR on
// Windows permits shared binding of UDP sockets).
func reusePort(network, address string, c syscall.RawConn) error {
	var serr error
	err := c.Control(func(fd uintptr) {
		serr = syscall.SetsockoptInt(syscall.Handle(fd), syscall.SOL_SOCKET, syscall.SO_REUSEADDR, 1)
	})
	if err != nil {
		return err
	}
	return serr
}
