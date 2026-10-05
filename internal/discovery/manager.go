package discovery

import (
	"context"
	"errors"
	"log/slog"
	"net"
	"strings"
	"sync"
	"time"

	"lanyard/internal/identity"
)

type Manager struct {
	selfMu sync.RWMutex
	self   Announcement
	selfID string // full Device ID
	probe  Prober
	reg    *registry
	log    *slog.Logger
	wg     sync.WaitGroup
	mdns   *mdnsNode
}

func New(self Announcement, selfFullID string, probe Prober, log *slog.Logger) *Manager {
	self.Version = ProtocolVersion
	self.ShortID = identity.ShortID(selfFullID)
	return &Manager{self: self, selfID: selfFullID, probe: probe, reg: newRegistry(), log: log}
}

// selfAnn returns a copy of our announcement (it can change at runtime).
func (m *Manager) selfAnn() Announcement {
	m.selfMu.RLock()
	defer m.selfMu.RUnlock()
	return m.self
}

// SetIdentity updates the name and Device ID label we advertise and re-announces
// them over mDNS right away; the beacon picks them up on its next tick.
func (m *Manager) SetIdentity(name, label string) {
	m.selfMu.Lock()
	m.self.Name, m.self.DeviceLabel = name, label
	m.selfMu.Unlock()
	if m.mdns != nil && m.mdns.server != nil {
		m.mdns.server.SetText(m.txtRecords())
	}
}

func (m *Manager) Peers() []Peer                        { return m.reg.list() }
func (m *Manager) Subscribe() (<-chan struct{}, func()) { return m.reg.subscribe() }

// Start launches every discovery path and returns. Cancel ctx and call Stop to
// shut down; Stop sends the mDNS goodbye.
func (m *Manager) Start(ctx context.Context) {
	m.startMDNS(ctx)
	m.startBeacon(ctx)
	m.wg.Add(1)
	go func() { defer m.wg.Done(); m.livenessLoop(ctx) }()
}

func (m *Manager) Stop() {
	if m.mdns != nil {
		m.mdns.stop()
	}
	m.wg.Wait()
}

// handle is the common sink for announcements from any source.
func (m *Manager) handle(a Announcement, ips []string, source string) {
	if a.ShortID == "" || a.ShortID == m.self.ShortID || a.Port <= 0 || a.Port > 65535 {
		return
	}
	var usable []string
	for _, s := range ips {
		if ip := net.ParseIP(s); usableIP(ip) {
			usable = append(usable, s)
		}
	}
	if len(usable) == 0 {
		return
	}
	p := m.reg.upsert(a, usable, source)
	if !p.Verified {
		m.verifyAsync(p.ShortID)
	}
}

// verifyAsync dials the peer and checks that the certificate it presents
// matches the ID it advertised. Spoofed announcements are discarded.
func (m *Manager) verifyAsync(shortID string) {
	m.reg.mu.Lock()
	p, ok := m.reg.peers[shortID]
	if !ok || p.probing || time.Since(p.lastProbe) < 5*time.Second {
		m.reg.mu.Unlock()
		return
	}
	p.probing = true
	p.lastProbe = time.Now()
	addrs := append([]string(nil), p.Addrs...)
	port := p.Port
	m.reg.mu.Unlock()

	m.wg.Add(1)
	go func() {
		defer m.wg.Done()
		certID, h, err := m.tryAddrs(addrs, port)
		m.reg.mu.Lock()
		defer m.reg.mu.Unlock()
		p, ok := m.reg.peers[shortID]
		if !ok {
			return
		}
		p.probing = false
		if err != nil {
			p.fails++
			return
		}
		if !strings.HasPrefix(certID, shortID) {
			m.log.Warn("discarding peer whose certificate does not match its announced ID", "announced", shortID)
			delete(m.reg.peers, shortID)
			m.reg.notify()
			return
		}
		p.fails = 0
		p.LastSeen = time.Now()
		if !p.Verified || p.DeviceID != certID {
			p.Verified, p.DeviceID = true, certID
			if h != nil && h.Name != "" {
				p.Name = h.Name
			}
			if h != nil && h.DeviceID != "" {
				p.DeviceLabel = h.DeviceID
			}
			m.reg.notify()
		}
	}()
}

func (m *Manager) tryAddrs(addrs []string, port int) (string, *Hello, error) {
	last := errors.New("no addresses")
	for _, a := range addrs {
		ctx, cancel := context.WithTimeout(context.Background(), 3*time.Second)
		id, h, err := m.probe(ctx, a, port)
		cancel()
		if err == nil {
			return id, h, nil
		}
		last = err
	}
	return "", nil, last
}

// AddManual dials host:port directly (VPNs, other subnets, blocked multicast).
func (m *Manager) AddManual(ctx context.Context, host string, port int) (*Peer, error) {
	certID, h, err := m.probe(ctx, host, port)
	if err != nil {
		return nil, err
	}
	if certID == m.selfID {
		return nil, errors.New("that is this device")
	}
	name, osName, label := host, "", ""
	if h != nil {
		name, osName, label = h.Name, h.OS, h.DeviceID
	}
	short := identity.ShortID(certID)
	p := m.reg.upsert(Announcement{ShortID: short, DeviceLabel: label, Name: name, OS: osName, Port: port}, []string{host}, "manual")
	m.reg.mu.Lock()
	if rp, ok := m.reg.peers[short]; ok {
		rp.Verified, rp.DeviceID = true, certID
		p = *rp
		m.reg.notify()
	}
	m.reg.mu.Unlock()
	return &p, nil
}

// livenessLoop probes known peers and drops ones that stop answering. It is
// source-agnostic, so it works even though mDNS re-announces infrequently.
func (m *Manager) livenessLoop(ctx context.Context) {
	t := time.NewTicker(15 * time.Second)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		for _, p := range m.reg.list() {
			p := p
			m.wg.Add(1)
			go func() {
				defer m.wg.Done()
				certID, _, err := m.tryAddrs(p.Addrs, p.Port)
				m.reg.mu.Lock()
				defer m.reg.mu.Unlock()
				rp, ok := m.reg.peers[p.ShortID]
				if !ok {
					return
				}
				if err == nil && strings.HasPrefix(certID, p.ShortID) {
					rp.fails = 0
					rp.LastSeen = time.Now()
					return
				}
				rp.fails++
				if rp.fails >= 2 {
					delete(m.reg.peers, p.ShortID)
					m.reg.notify()
				}
			}()
		}
	}
}
