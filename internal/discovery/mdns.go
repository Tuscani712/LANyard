package discovery

import (
	"context"
	"strconv"
	"strings"
	"time"

	"github.com/libp2p/zeroconf/v2"

	"lanyard/internal/xferlog"
)

const (
	mdnsService = "_lanyard._tcp"
	mdnsDomain  = "local."
)

type mdnsNode struct {
	server *zeroconf.Server
	cancel context.CancelFunc
}

func (n *mdnsNode) stop() {
	if n.cancel != nil {
		n.cancel()
	}
	if n.server != nil {
		n.server.Shutdown() // sends TTL-0 goodbye records
	}
}

// clip truncates to max bytes without splitting a UTF-8 rune.
func clip(s string, max int) string {
	if len(s) <= max {
		return s
	}
	s = s[:max]
	for len(s) > 0 && s[len(s)-1]&0xC0 == 0x80 {
		s = s[:len(s)-1]
	}
	if len(s) > 0 && s[len(s)-1] >= 0xC0 {
		s = s[:len(s)-1]
	}
	return s
}

func (m *Manager) txtRecords() []string {
	a := m.selfAnn()
	return []string{
		"v=" + a.Version,
		"id=" + a.ShortID,
		"did=" + clip(a.DeviceLabel, 32),
		"n=" + clip(a.Name, 60),
		"os=" + a.OS,
		"p=" + strconv.Itoa(a.Port),
	}
}

func (m *Manager) startMDNS(ctx context.Context) {
	txt := m.txtRecords()
	node := &mdnsNode{}
	m.mdns = node

	srv, err := zeroconf.Register(m.self.ShortID, mdnsService, mdnsDomain, m.self.Port, txt, nil)
	if err != nil {
		m.log.Warn("mDNS announce unavailable; relying on beacon/manual", "err", err)
		m.xfer(xferlog.Entry{Outcome: "browse", Level: xferlog.LevelWarn, Reason: "mDNS announce unavailable", Error: err.Error()})
	} else {
		node.server = srv
		m.xfer(xferlog.Entry{Outcome: "announce", Level: xferlog.LevelInfo, Reason: "mDNS announce started"})
	}

	bctx, cancel := context.WithCancel(ctx)
	node.cancel = cancel
	entries := make(chan *zeroconf.ServiceEntry, 16)

	// Browse blocks until bctx is cancelled and closes entries when it returns.
	m.wg.Add(2)
	go func() {
		defer m.wg.Done()
		if err := zeroconf.Browse(bctx, mdnsService, mdnsDomain, entries); err != nil && bctx.Err() == nil {
			m.log.Warn("mDNS browse unavailable; relying on beacon/manual", "err", err)
			m.xfer(xferlog.Entry{Outcome: "browse", Level: xferlog.LevelWarn, Reason: "mDNS browse unavailable", Error: err.Error()})
		}
	}()
	go func() {
		defer m.wg.Done()
		for e := range entries {
			a, ok := parseTXT(e.Text)
			if !ok || a.ShortID != e.Instance {
				continue
			}
			var ips []string
			for _, ip := range e.AddrIPv4 {
				ips = append(ips, ip.String())
			}
			for _, ip := range e.AddrIPv6 {
				ips = append(ips, ip.String())
			}
			if time.Until(e.Expiry) < 5*time.Second { // TTL 0 goodbye
				m.xfer(xferlog.Entry{
					Outcome: "expire", Level: xferlog.LevelInfo, FP: a.ShortID, Peer: a.Name,
					Reason: "mDNS record expired",
				})
				m.removeIfIdle(a.ShortID)
				continue
			}
			m.handle(a, ips, "mdns")
		}
	}()
}

func parseTXT(txt []string) (Announcement, bool) {
	var a Announcement
	for _, kv := range txt {
		k, v, ok := strings.Cut(kv, "=")
		if !ok {
			continue
		}
		switch k {
		case "v":
			a.Version = v
		case "id":
			a.ShortID = v
		case "did":
			a.DeviceLabel = v
		case "n":
			a.Name = v
		case "os":
			a.OS = v
		case "p":
			a.Port, _ = strconv.Atoi(v)
		}
	}
	return a, a.ShortID != "" && a.Version == ProtocolVersion
}
