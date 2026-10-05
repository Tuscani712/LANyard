package discovery

import (
	"context"
	"encoding/json"
	"net"
	"time"

	"lanyard/internal/config"
)

// The beacon is the fallback for networks that block mDNS: a small JSON
// datagram broadcast on UDP config.BeaconPort. "probe" asks listeners to
// answer immediately, which is how a freshly started instance finds the
// devices that are already running.
type beaconMsg struct {
	Type string `json:"t"` // "beacon" | "probe"
	Announcement
}

func (m *Manager) startBeacon(ctx context.Context) {
	lc := net.ListenConfig{Control: reusePort}
	pc, err := lc.ListenPacket(ctx, "udp4", hostPort("0.0.0.0", config.BeaconPort))
	if err != nil {
		m.log.Warn("beacon listener unavailable (port busy); mDNS and manual connect still work", "err", err)
		return
	}
	conn := pc.(*net.UDPConn)

	send := func(t string, to *net.UDPAddr) {
		b, _ := json.Marshal(beaconMsg{Type: t, Announcement: m.selfAnn()})
		if to != nil {
			_, _ = conn.WriteToUDP(b, to)
			return
		}
		for _, dst := range broadcastTargets() {
			_, _ = conn.WriteToUDP(b, dst)
		}
	}

	m.wg.Add(2)
	go func() { // receiver
		defer m.wg.Done()
		defer conn.Close()
		go func() { <-ctx.Done(); conn.Close() }()
		buf := make([]byte, 2048)
		var lastReply time.Time
		for {
			n, src, err := conn.ReadFromUDP(buf)
			if err != nil {
				if ctx.Err() != nil {
					return
				}
				continue
			}
			var msg beaconMsg
			if json.Unmarshal(buf[:n], &msg) != nil || msg.Version != ProtocolVersion {
				continue
			}
			if msg.ShortID == m.self.ShortID {
				continue
			}
			m.handle(msg.Announcement, []string{src.IP.String()}, "beacon")
			if msg.Type == "probe" && time.Since(lastReply) > 300*time.Millisecond {
				lastReply = time.Now()
				send("beacon", src)
			}
		}
	}()
	go func() { // sender
		defer m.wg.Done()
		send("probe", nil)
		t := time.NewTicker(5 * time.Second)
		defer t.Stop()
		for {
			select {
			case <-ctx.Done():
				return
			case <-t.C:
				send("beacon", nil)
			}
		}
	}()
}

// broadcastTargets returns the limited broadcast address plus each local
// IPv4 subnet's directed broadcast address.
func broadcastTargets() []*net.UDPAddr {
	port := config.BeaconPort
	out := []*net.UDPAddr{{IP: net.IPv4bcast, Port: port}}
	ifs, err := net.Interfaces()
	if err != nil {
		return out
	}
	for _, ifc := range ifs {
		if ifc.Flags&net.FlagUp == 0 || ifc.Flags&net.FlagBroadcast == 0 || ifc.Flags&net.FlagLoopback != 0 {
			continue
		}
		addrs, _ := ifc.Addrs()
		for _, a := range addrs {
			ipn, ok := a.(*net.IPNet)
			if !ok {
				continue
			}
			ip4 := ipn.IP.To4()
			if ip4 == nil || len(ipn.Mask) != 4 && len(ipn.Mask) != 16 {
				continue
			}
			mask := ipn.Mask
			if len(mask) == 16 {
				mask = mask[12:]
			}
			bc := make(net.IP, 4)
			for i := range bc {
				bc[i] = ip4[i] | ^mask[i]
			}
			out = append(out, &net.UDPAddr{IP: bc, Port: port})
		}
	}
	return out
}
