// Package lanaddr lists this device's LAN-reachable addresses for a pairing
// link. Only private and link-local addresses are returned: a QR code is for
// same-LAN pairing, and a public address would be both useless and a leak.
//
// Ordering matters because a scanner tries the addresses in turn. The address
// the OS would use for the default route comes first, then other real
// interfaces; virtual bridges (docker*, br-*, veth*, lxc*, virbr*) are only
// used when nothing else exists.
package lanaddr

import (
	"net"
	"sort"
	"strings"
)

// maxPairingAddrs caps how many addresses a pairing link carries: a scanner
// tries them in order, and a shorter list keeps the QR code dense but scannable.
const maxPairingAddrs = 4

// Addrs returns the addresses for a pairing link: private and link-local IPv4,
// plus routable IPv6, with the default-route address first and at most
// maxPairingAddrs entries. IPv6 link-local (fe80::…) is excluded because it
// cannot be dialed from another host without a zone id.
func Addrs() []string {
	return collect(false, maxPairingAddrs)
}

// AllAddrs is the fuller list for diagnostics: it also includes IPv6 link-local
// addresses and is not capped.
func AllAddrs() []string {
	return collect(true, 0)
}

func collect(keepV6LinkLocal bool, limit int) []string {
	primary := principalIP()
	ifs, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var entries []ifaceAddr
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, err := ifc.Addrs()
		if err != nil {
			continue
		}
		for _, a := range addrs {
			if ipnet, ok := a.(*net.IPNet); ok {
				entries = append(entries, ifaceAddr{name: ifc.Name, ip: ipnet.IP})
			}
		}
	}
	return chooseAddrs(primary, entries, keepV6LinkLocal, limit)
}

// principalIP returns the local IP the OS would use to reach the default route.
// A connected UDP socket makes the kernel choose a source address without
// sending any packets.
func principalIP() string {
	c, err := net.Dial("udp", "192.0.2.1:9") // TEST-NET-1; nothing is sent
	if err != nil {
		return ""
	}
	defer c.Close()
	if u, ok := c.LocalAddr().(*net.UDPAddr); ok {
		return u.IP.String()
	}
	return ""
}

type ifaceAddr struct {
	name string
	ip   net.IP
}

// chooseAddrs filters, orders, caps and de-duplicates candidate addresses.
// Pure, so the ordering and filtering rules can be tested without the network.
func chooseAddrs(primary string, entries []ifaceAddr, keepV6LinkLocal bool, limit int) []string {
	seen := map[string]bool{}
	var normal, virtual []string
	hasPrimary := false
	for _, e := range entries {
		ip := e.ip
		if ip == nil || ip.IsLoopback() || ip.IsUnspecified() {
			continue
		}
		if !(ip.IsPrivate() || ip.IsLinkLocalUnicast()) {
			continue
		}
		// IPv6 link-local has no zone id here and is not dialable elsewhere.
		if !keepV6LinkLocal && ip.To4() == nil && ip.IsLinkLocalUnicast() {
			continue
		}
		s := ip.String()
		if seen[s] {
			continue
		}
		seen[s] = true
		if s == primary {
			hasPrimary = true
			continue
		}
		if isVirtualIface(e.name) {
			virtual = append(virtual, s)
		} else {
			normal = append(normal, s)
		}
	}
	sort.Strings(normal)
	sort.Strings(virtual)

	out := make([]string, 0, len(normal)+1)
	if hasPrimary {
		out = append(out, primary)
	}
	out = append(out, normal...)
	if len(out) == 0 {
		// Nothing real: fall back to virtual bridges rather than nothing.
		out = append(out, virtual...)
	}
	if limit > 0 && len(out) > limit {
		out = out[:limit]
	}
	return out
}

// isVirtualIface reports whether a Linux interface name is a known virtual
// bridge, tunnel or container link.
func isVirtualIface(name string) bool {
	for _, p := range []string{"docker", "br-", "veth", "lxc", "virbr"} {
		if strings.HasPrefix(name, p) {
			return true
		}
	}
	return false
}
