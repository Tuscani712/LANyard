package discovery

import (
	"context"
	"errors"
	"strings"
	"sync/atomic"
	"testing"
	"time"
)

// seedPeer inserts an already-verified peer directly into the registry so a
// test can drive the liveness sweep without a real announcement.
func seedPeer(m *Manager, shortID, fp string, addrs []string, port int) {
	m.reg.upsert(Announcement{ShortID: shortID, Name: "peer", Port: port}, addrs, "mdns")
	m.reg.mu.Lock()
	if p, ok := m.reg.peers[shortID]; ok {
		p.Verified = true
		p.DeviceID = fp
		p.fails = 0
	}
	m.reg.mu.Unlock()
}

func peerFails(m *Manager, shortID string) (int, bool) {
	m.reg.mu.Lock()
	defer m.reg.mu.Unlock()
	p, ok := m.reg.peers[shortID]
	if !ok {
		return 0, false
	}
	return p.fails, true
}

func failingProbe(context.Context, string, int) (string, *Hello, error) {
	return "", nil, errors.New("peer down")
}

// (i) A peer with an active transfer is not evicted even when every probe
// fails, and its miss counter is cleared so it is treated as alive.
func TestActiveTransferPeerNotEvicted(t *testing.T) {
	fp := strings.Repeat("a", 64)
	short := fp[:16]
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), failingProbe, discardLogger())
	seedPeer(m, short, fp, []string{"10.0.0.5"}, 47800)

	var active atomic.Bool
	active.Store(true)
	m.SetActiveTransfer(func(s, f string) bool {
		return active.Load() && (s == short || f == fp)
	})

	for i := 0; i < 12; i++ {
		m.sweep()
		m.wg.Wait()
	}
	if fails, ok := peerFails(m, short); !ok {
		t.Fatal("a peer with an active transfer must not be evicted")
	} else if fails != 0 {
		t.Fatalf("active transfer should clear misses, got fails=%d", fails)
	}

	// Once the transfer ends the peer is evictable again.
	active.Store(false)
	for i := 0; i < defaultEvictAfter; i++ {
		m.sweep()
		m.wg.Wait()
	}
	if _, ok := peerFails(m, short); ok {
		t.Fatal("an idle, unreachable peer should be evicted after the threshold")
	}
}

// (ii) After raising the tolerance a peer survives the old two-miss eviction
// point (the busy-link regression) and is only dropped after the new
// threshold of consecutive misses.
func TestLivenessToleranceSurvivesOldThreshold(t *testing.T) {
	fp := strings.Repeat("b", 64)
	short := fp[:16]
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), failingProbe, discardLogger())
	seedPeer(m, short, fp, []string{"10.0.0.6"}, 47800)

	if m.evictAfter != defaultEvictAfter || m.evictAfter <= 2 {
		t.Fatalf("default evictAfter = %d, want > 2", m.evictAfter)
	}

	m.sweep()
	m.wg.Wait()
	m.sweep()
	m.wg.Wait() // the old code evicted here
	if _, ok := peerFails(m, short); !ok {
		t.Fatal("peer must survive the old 2-miss point")
	}

	for i := 0; i < defaultEvictAfter; i++ {
		m.sweep()
		m.wg.Wait()
	}
	if _, ok := peerFails(m, short); ok {
		t.Fatal("peer should be evicted after the full consecutive-miss threshold")
	}
}

// (iv) The paired direct-probe result is a real online value: a reachable
// paired peer is registered as verified with its fingerprint, which is exactly
// what the peers API (and thus the UI's isOnline) reads. The matching JS change
// that stops disabling Send/Pull is verified by inspection.
func TestPairedPeerReachableReportedOnline(t *testing.T) {
	fp := strings.Repeat("c", 64)
	short := fp[:16]
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64),
		func(_ context.Context, host string, port int) (string, *Hello, error) {
			if host == "192.168.1.50" && port == 47800 {
				return fp, &Hello{Name: "Phone", DeviceID: "phone-label"}, nil
			}
			return "", nil, errors.New("unreachable")
		}, discardLogger())
	m.SetPairedProvider(func() []PairedPeer {
		return []PairedPeer{{Fingerprint: fp, ShortID: short, Name: "Phone", Addrs: []string{"192.168.1.50"}, Port: 47800}}
	})

	m.sweep()
	m.wg.Wait()

	var found *Peer
	for _, p := range m.Peers() {
		if p.DeviceID == fp {
			c := p
			found = &c
		}
	}
	if found == nil {
		t.Fatal("a reachable paired peer absent from mDNS must be reported online")
	}
	if !found.Verified {
		t.Fatal("the direct probe must mark the paired peer verified (this is the UI online value)")
	}
	if found.ShortID != short {
		t.Fatalf("short id = %q, want %q", found.ShortID, short)
	}
}

// (iii) A paired peer that is unreachable at its last-known address is not
// reported online.
func TestPairedPeerUnreachableReportedOffline(t *testing.T) {
	fp := strings.Repeat("d", 64)
	short := fp[:16]
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), failingProbe, discardLogger())
	m.SetPairedProvider(func() []PairedPeer {
		return []PairedPeer{{Fingerprint: fp, ShortID: short, Name: "Phone", Addrs: []string{"192.168.1.51"}, Port: 47800}}
	})

	m.sweep()
	m.wg.Wait()

	for _, p := range m.Peers() {
		if p.DeviceID == fp {
			t.Fatal("an unreachable paired peer must not be reported online")
		}
	}
}

// The direct probe is cached: a failing last-known address is not retried on
// every sweep, but is retried once the cache interval elapses.
func TestPairedProbeCache(t *testing.T) {
	fp := strings.Repeat("e", 64)
	short := fp[:16]
	var calls atomic.Int32
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64),
		func(context.Context, string, int) (string, *Hello, error) {
			calls.Add(1)
			return "", nil, errors.New("unreachable")
		}, discardLogger())
	m.SetPairedProvider(func() []PairedPeer {
		return []PairedPeer{{Fingerprint: fp, ShortID: short, Addrs: []string{"192.168.1.52"}, Port: 47800}}
	})
	m.SetPairedProbeInterval(time.Hour)

	m.sweep()
	m.wg.Wait()
	m.sweep()
	m.wg.Wait()
	if got := calls.Load(); got != 1 {
		t.Fatalf("cached paired probe ran %d times, want 1", got)
	}

	m.SetPairedProbeInterval(time.Nanosecond)
	m.sweep()
	m.wg.Wait()
	if got := calls.Load(); got != 2 {
		t.Fatalf("paired probe after cache expiry ran %d times, want 2", got)
	}
}
