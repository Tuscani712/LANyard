// Package lanaddr lists this device's LAN-reachable addresses for a pairing
// link. Only private and link-local addresses are returned: a QR code is for
// same-LAN pairing, and a public address would be both useless and a leak.
package lanaddr

import (
	"net"
	"sort"
)

// Addrs returns the usable private and link-local addresses of the host,
// sorted and de-duplicated, without ports.
func Addrs() []string {
	ifs, err := net.Interfaces()
	if err != nil {
		return nil
	}
	var ips []net.IP
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
				ips = append(ips, ipnet.IP)
			}
		}
	}
	return Filter(ips)
}

// Filter keeps only private and link-local addresses, sorted and de-duplicated.
func Filter(ips []net.IP) []string {
	seen := map[string]bool{}
	var out []string
	for _, ip := range ips {
		if ip == nil || ip.IsLoopback() || ip.IsUnspecified() {
			continue
		}
		if !(ip.IsPrivate() || ip.IsLinkLocalUnicast()) {
			continue
		}
		s := ip.String()
		if seen[s] {
			continue
		}
		seen[s] = true
		out = append(out, s)
	}
	sort.Strings(out)
	return out
}
