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
	"lanyard/internal/xferlog"
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

	// now returns the current time. Injectable so the paired-probe cache is
	// deterministic in tests (coarse Windows/Wine timers otherwise flake the
	// "cache expired" step).
	now func() time.Time

	// xlog records discovery events (seen/lost, probes, evictions) in the
	// shared four-area diagnostics log. Nil is a valid no-op.
	xlog *xferlog.Recorder
}

// SetXferLog attaches the shared diagnostics recorder.
func (m *Manager) SetXferLog(r *xferlog.Recorder) { m.xlog = r }

// xfer records one discovery event.
func (m *Manager) xfer(e xferlog.Entry) {
	e.Area = xferlog.AreaDiscovery
	m.xlog.Record(m.log, e)
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
		now:          time.Now,
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

// SetNowFunc overrides the clock used by the paired-probe cache. Intended for
// tests so the cache expiry is deterministic.
func (m *Manager) SetNowFunc(fn func() time.Time) {
	if fn != nil {
		m.now = fn
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
	p, noticed := m.reg.upsert(a, usable, source)
	// A first sighting (or a real change: new address, renamed device) is worth
	// one INFO line; the same peer re-announcing every few seconds over mDNS or
	// the beacon is routine chatter and stays out of the default desktop log.
	level := xferlog.LevelDebug
	if noticed {
		level = xferlog.LevelInfo
	}
	m.xfer(xferlog.Entry{
		Outcome: "seen", Level: level,
		FP: a.ShortID, Peer: a.Name, Target: hostPort(usable[0], a.Port), Reason: source,
	})
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
		start := time.Now()
		certID, h, err := m.tryAddrs(addrs, port)
		elapsed := time.Since(start)
		m.reg.mu.Lock()
		defer m.reg.mu.Unlock()
		p, ok := m.reg.peers[shortID]
		if !ok {
			return
		}
		p.probing = false
		if err != nil {
			p.fails++
			m.xfer(xferlog.Entry{
				Outcome: "probe", Level: xferlog.LevelWarn, FP: shortID,
				Target: hostPort(addrs[0], port), Elapsed: elapsed, Error: err.Error(),
			})
			return
		}
		if !strings.HasPrefix(certID, shortID) {
			m.log.Warn("discarding peer whose certificate does not match its announced ID", "announced", shortID)
			m.xfer(xferlog.Entry{
				Outcome: "probe", Level: xferlog.LevelWarn, FP: shortID,
				Target: hostPort(addrs[0], port), Elapsed: elapsed,
				Reason: "certificate does not match announced id",
			})
			delete(m.reg.peers, shortID)
			m.reg.notify()
			return
		}
		m.xfer(xferlog.Entry{
			Outcome: "probe", Level: xferlog.LevelDebug, FP: shortID,
			Target: hostPort(addrs[0], port), Elapsed: elapsed,
		})
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
	start := time.Now()
	certID, h, err := m.probe(ctx, host, port)
	if err != nil {
		m.xfer(xferlog.Entry{
			Outcome: "probe", Level: xferlog.LevelWarn,
			Target: hostPort(host, port), Elapsed: time.Since(start), Error: err.Error(),
		})
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
	m.xfer(xferlog.Entry{
		Outcome: "seen", Level: xferlog.LevelInfo, FP: short, Peer: name,
		Target: hostPort(host, port), Reason: "manual", Elapsed: time.Since(start),
	})
	p, _ := m.reg.upsert(Announcement{ShortID: short, DeviceLabel: label, Name: name, OS: osName, Port: port}, []string{host}, "manual")
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
	start := time.Now()
	certID, _, err := m.tryAddrs(p.Addrs, p.Port)
	elapsed := time.Since(start)
	active := m.isActive(p.ShortID, p.DeviceID)
	target := ""
	if len(p.Addrs) > 0 {
		target = hostPort(p.Addrs[0], p.Port)
	}
	m.reg.mu.Lock()
	rp, ok := m.reg.peers[p.ShortID]
	if !ok {
		m.reg.mu.Unlock()
		return
	}
	if err == nil && strings.HasPrefix(certID, p.ShortID) {
		rp.fails = 0
		rp.LastSeen = time.Now()
		misses := 0
		m.reg.mu.Unlock()
		m.xfer(xferlog.Entry{
			Outcome: "probe", Level: xferlog.LevelDebug, FP: p.ShortID,
			Target: target, Elapsed: elapsed, Misses: misses,
		})
		return
	}
	if active {
		rp.fails = 0
		rp.LastSeen = time.Now()
		m.reg.mu.Unlock()
		m.xfer(xferlog.Entry{
			Outcome: "guard", Level: xferlog.LevelDebug, FP: p.ShortID,
			Target: target, Reason: "active transfer; peer kept", Elapsed: elapsed,
		})
		return
	}
	rp.fails++
	misses := rp.fails
	evicted := misses >= m.evictAfter
	if evicted {
		delete(m.reg.peers, p.ShortID)
		m.reg.notify()
	}
	m.reg.mu.Unlock()
	reason := "liveness probe failed"
	certMismatch := false
	if err != nil {
		reason = err.Error()
	} else if certID != "" {
		reason = "certificate does not match announced id"
		certMismatch = true
	}
	level := xferlog.LevelWarn
	if evicted {
		// A peer that simply stopped answering (timeout / left the network) is
		// a normal event, not an error. Only a certificate mismatch is a real
		// fault and keeps ERROR.
		level = xferlog.LevelInfo
		if certMismatch {
			level = xferlog.LevelError
			reason = "certificate does not match announced id"
		}
		m.xfer(xferlog.Entry{
			Outcome: "evict", Level: level, FP: p.ShortID, Target: target,
			Misses: misses, Reason: reason, Elapsed: elapsed,
		})
	} else {
		m.xfer(xferlog.Entry{
			Outcome: "probe", Level: level, FP: p.ShortID, Target: target,
			Misses: misses, Reason: reason, Elapsed: elapsed,
		})
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
			start := time.Now()
			target := ""
			if len(pp.Addrs) > 0 {
				target = hostPort(pp.Addrs[0], pp.Port)
			}
			certID, h, err := m.tryAddrs(pp.Addrs, pp.Port)
			elapsed := time.Since(start)
			if err != nil || (certID != pp.Fingerprint && !strings.HasPrefix(certID, short)) {
				reason := "paired probe failed"
				if err != nil {
					reason = err.Error()
				} else {
					reason = "certificate does not match paired fingerprint"
				}
				m.xfer(xferlog.Entry{
					Outcome: "probe", Level: xferlog.LevelWarn, FP: short,
					Target: target, Reason: reason, Elapsed: elapsed,
				})
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
			m.xfer(xferlog.Entry{
				Outcome: "seen", Level: xferlog.LevelInfo, FP: short, Peer: pp.Name,
				Target: target, Reason: "paired-probe", Elapsed: elapsed,
			})
		}(pp, short)
	}
}

// pairedProbeDue reports whether shortID may be probed now, recording the
// attempt so a failing address is not retried until the cache interval passes.
func (m *Manager) pairedProbeDue(shortID string) bool {
	m.pairedMu.Lock()
	defer m.pairedMu.Unlock()
	now := m.now()
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
		m.xfer(xferlog.Entry{
			Outcome: "guard", Level: xferlog.LevelInfo, FP: shortID,
			Reason: "active transfer; peer kept on goodbye",
		})
		return
	}
	m.reg.remove(shortID)
	m.xfer(xferlog.Entry{
		Outcome: "lost", Level: xferlog.LevelInfo, FP: shortID,
		Reason: "mDNS goodbye or expiry",
	})
}
