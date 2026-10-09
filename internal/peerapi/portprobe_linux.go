//go:build linux

package peerapi

import (
	"os"
	"path/filepath"
	"strconv"
	"strings"
)

// portHolder names the process listening on a TCP port using /proc, returning
// "" when it cannot be determined (permissions, another net namespace, or no
// match). It is best-effort only: the temporary-port message never depends on
// it being found.
func portHolder(port int) string {
	inode := tcpListenInode(port)
	if inode == "" {
		return ""
	}
	return pidCommForSocket(inode)
}

// tcpListenInode returns the socket inode of a LISTEN entry on the given port
// from /proc/net/tcp, or "".
func tcpListenInode(port int) string {
	b, err := os.ReadFile("/proc/net/tcp")
	if err != nil {
		return ""
	}
	want := strings.ToUpper(strconv.FormatInt(int64(port), 16))
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) < 10 {
			continue
		}
		host, p, ok := strings.Cut(f[1], ":")
		if !ok || host == "" || p != want {
			continue
		}
		// f[3] is the TCP state; 0A is LISTEN.
		if f[3] != "0A" {
			continue
		}
		return f[9]
	}
	return ""
}

// pidCommForSocket scans /proc/<pid>/fd for a socket with the given inode and
// returns the owning process's name.
func pidCommForSocket(inode string) string {
	want := "socket:[" + inode + "]"
	procs, err := filepath.Glob("/proc/[0-9]*")
	if err != nil {
		return ""
	}
	for _, p := range procs {
		fds, err := os.ReadDir(filepath.Join(p, "fd"))
		if err != nil {
			continue
		}
		for _, fd := range fds {
			link, err := os.Readlink(filepath.Join(p, "fd", fd.Name()))
			if err != nil || link != want {
				continue
			}
			if b, err := os.ReadFile(filepath.Join(p, "comm")); err == nil {
				return strings.TrimSpace(string(b))
			}
			return filepath.Base(p)
		}
	}
	return ""
}
