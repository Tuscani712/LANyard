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

// Liveness tuning. A peer is evicted only after this many consecutive failed
// probes, so a busy or briefly-idle link is not dropped. The sweep runs every
// livenessInterval, so the default ~6 misses is roughly a 30 s failure window.
const (
	defaultProbeTimeout     = 3 * time.Second
	defaultEvictAfter       = 6
	livenessInterval        = 5 * time.Second
	defaultPairedProbeEvery = 5 * time.Second
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

	// active reports whether a transfer with this peer is in flight (either
	// direction). A peer with an active transfer is never evicted and its
	// LastSeen is refreshed even when probes fail. Injectable for tests.
	active func(shortID, fingerprint string) bool

	// paired supplies trusted devices (fingerprint + last-known address) that
	// must be probed directly when mDNS is silent. Injectable for tests.
	paired func() []PairedPeer

	probeTimeout time.Duration
	evictAfter   int

	pairedMu     sync.Mutex
	pairedProbed map[string]time.Time
	pairedEvery  time.Duration
}

func New(self Announcement, selfFullID string, probe Prober, log *slog.Logger) *Manager {
	self.Version = ProtocolVersion
	self.ShortID = identity.ShortID(selfFullID)
	return &Manager{
		self: self, selfID: selfFullID, probe: probe, reg: newRegistry(), log: log,
		probeTimeout: defaultProbeTimeout,
		evictAfter:   defaultEvictAfter,
		pairedProbed: map[string]time.Time{},
		pairedEvery:  defaultPairedProbeEvery,
	}
}

// SetActiveTransfer installs the predicate that reports whether a transfer with
// the given peer (short id or full fingerprint) is currently in flight. While
// it returns true the peer is treated as alive: probe misses do not count and
// it is never evicted.
func (m *Manager) SetActiveTransfer(fn func(shortID, fingerprint string) bool) {
	m.active = fn
}

// SetPairedProvider installs a source of trusted devices to probe directly at
// their last-known address when they are absent from the discovery registry.
func (m *Manager) SetPairedProvider(fn func() []PairedPeer) {
	m.paired = fn
}

// SetLivenessThresholds overrides how many consecutive probe misses evict a
// peer and how long a single probe may take. Intended for tests.
func (m *Manager) SetLivenessThresholds(evictAfter int, probeTimeout time.Duration) {
	if evictAfter > 0 {
		m.evictAfter = evictAfter
	}
	if probeTimeout > 0 {
		m.probeTimeout = probeTimeout
	}
}

// SetPairedProbeInterval overrides how often a missing paired peer is probed
// again (a short cache so the UI never drives probes). Intended for tests.
func (m *Manager) SetPairedProbeInterval(d time.Duration) {
	if d > 0 {
		m.pairedEvery = d
	}
}

// isActive consults the injected predicate, treating a nil predicate as idle.
func (m *Manager) isActive(shortID, fingerprint string) bool {
	return m.active != nil && m.active(shortID, fingerprint)
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
		ctx, cancel := context.WithTimeout(context.Background(), m.probeTimeout)
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
// When expectedFP is set the presented certificate must match it exactly before
// the peer is recorded, so a pairing link can pin a fingerprint (fail closed).
func (m *Manager) AddManual(ctx context.Context, host string, port int, expectedFP string) (*Peer, error) {
	certID, h, err := m.probe(ctx, host, port)
	if err != nil {
		return nil, err
	}
	if certID == m.selfID {
		return nil, errors.New("that is this device")
	}
	if expectedFP != "" && certID != expectedFP {
		return nil, errors.New("the device's certificate does not match the pairing link")
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
	t := time.NewTicker(livenessInterval)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		}
		m.sweep()
	}
}

// sweep runs one liveness pass: probe every registry peer, then directly probe
// paired peers that are missing from the registry (mDNS silent).
func (m *Manager) sweep() {
	for _, p := range m.reg.list() {
		p := p
		m.wg.Add(1)
		go func() { defer m.wg.Done(); m.probeRegistryPeer(p) }()
	}
	m.probePairedPeers()
}

// probeRegistryPeer probes one peer once. A peer with an active transfer is
// treated as alive regardless of the probe result: its miss counter is cleared
// and LastSeen refreshed, so a busy link cannot evict it.
func (m *Manager) probeRegistryPeer(p Peer) {
	certID, _, err := m.tryAddrs(p.Addrs, p.Port)
	active := m.isActive(p.ShortID, p.DeviceID)
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
	if active {
		rp.fails = 0
		rp.LastSeen = time.Now()
		return
	}
	rp.fails++
	if rp.fails >= m.evictAfter {
		delete(m.reg.peers, p.ShortID)
		m.reg.notify()
	}
}

// probePairedPeers directly dials paired devices that are absent (or not yet
// verified) in the registry, using the last-known address from the trust store.
// A successful probe records the peer as verified, which is what the peers API
// (and therefore the UI's online state) reports. A short per-peer cache means
// the same address is not probed on every pass.
func (m *Manager) probePairedPeers() {
	if m.paired == nil {
		return
	}
	for _, pp := range m.paired() {
		if pp.Fingerprint == "" || pp.Port <= 0 || len(pp.Addrs) == 0 {
			continue
		}
		short := pp.ShortID
		if short == "" {
			short = identity.ShortID(pp.Fingerprint)
		}
		m.reg.mu.Lock()
		rp, ok := m.reg.peers[short]
		verified := ok && rp.Verified
		m.reg.mu.Unlock()
		if verified || !m.pairedProbeDue(short) {
			continue
		}
		m.wg.Add(1)
		go func(pp PairedPeer, short string) {
			defer m.wg.Done()
			certID, h, err := m.tryAddrs(pp.Addrs, pp.Port)
			if err != nil || (certID != pp.Fingerprint && !strings.HasPrefix(certID, short)) {
				return
			}
			m.reg.upsert(Announcement{ShortID: short, DeviceLabel: pp.Name, Name: pp.Name, Port: pp.Port}, pp.Addrs, "paired")
			m.reg.mu.Lock()
			if rp, ok := m.reg.peers[short]; ok {
				rp.Verified = true
				rp.DeviceID = certID
				rp.fails = 0
				rp.LastSeen = time.Now()
				if h != nil && h.Name != "" {
					rp.Name = h.Name
				}
				if h != nil && h.DeviceID != "" {
					rp.DeviceLabel = h.DeviceID
				}
			}
			m.reg.mu.Unlock()
			m.reg.notify()
		}(pp, short)
	}
}

// pairedProbeDue reports whether shortID may be probed now, recording the
// attempt so a failing address is not retried until the cache interval passes.
func (m *Manager) pairedProbeDue(shortID string) bool {
	m.pairedMu.Lock()
	defer m.pairedMu.Unlock()
	now := time.Now()
	if t, ok := m.pairedProbed[shortID]; ok && now.Sub(t) < m.pairedEvery {
		return false
	}
	m.pairedProbed[shortID] = now
	return true
}

// removeIfIdle drops a peer that stopped advertising, unless it has an active
// transfer; in that case it is kept and its LastSeen refreshed.
func (m *Manager) removeIfIdle(shortID string) {
	m.reg.mu.Lock()
	p, ok := m.reg.peers[shortID]
	var fp string
	if ok {
		fp = p.DeviceID
	}
	m.reg.mu.Unlock()
	if !ok {
		return
	}
	if m.isActive(shortID, fp) {
		m.reg.mu.Lock()
		if rp, ok := m.reg.peers[shortID]; ok {
			rp.fails = 0
			rp.LastSeen = time.Now()
		}
		m.reg.mu.Unlock()
		return
	}
	m.reg.remove(shortID)
}
