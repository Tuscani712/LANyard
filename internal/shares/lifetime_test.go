package shares

import (
	"context"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	"lanyard/internal/config"
)

// clock is a hand-wound time source so lifetime rules can be tested exactly.
type clock struct {
	mu sync.Mutex
	t  time.Time
}

func newClock() *clock { return &clock{t: time.Date(2026, 10, 5, 12, 0, 0, 0, time.UTC)} }

func (c *clock) Now() time.Time {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.t
}

func (c *clock) Advance(d time.Duration) {
	c.mu.Lock()
	c.t = c.t.Add(d)
	c.mu.Unlock()
}

func newManagerWithClock(t *testing.T, cfg *config.Store, c *clock) *Manager {
	t.Helper()
	m := New(cfg, "self", nil)
	m.SetClock(c.Now)
	t.Cleanup(func() { m.StopAll() })
	return m
}

func tempShareDir(t *testing.T) string {
	t.Helper()
	d := t.TempDir()
	if err := os.WriteFile(filepath.Join(d, "a.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	return d
}

// A timed share is restored after a restart only while it has not expired, and
// the expired entry is forgotten on disk too.
func TestTimedShareAcrossRestart(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	clk := newClock()
	dir := tempShareDir(t)

	m1 := newManagerWithClock(t, cfg, clk)
	timed, err := m1.Add(dir, AddOptions{LifetimeType: LifetimeTimed, DurationSec: 60})
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m1.Add(dir, AddOptions{LifetimeType: LifetimePersistent, Label: "keep"}); err != nil {
		t.Fatal(err)
	}
	if _, err := m1.Add(dir, AddOptions{LifetimeType: LifetimeUntilStopped, Label: "temp"}); err != nil {
		t.Fatal(err)
	}

	// Restart 20 s later: timed (unexpired) and persistent survive; until_stopped does not.
	clk.Advance(20 * time.Second)
	m2 := newManagerWithClock(t, cfg, clk)
	if err := m2.Load(); err != nil {
		t.Fatal(err)
	}
	if _, ok := m2.Get(timed.ShareID); !ok {
		t.Error("an unexpired timed share should survive a restart")
	}
	labels := map[string]bool{}
	for _, s := range m2.List() {
		labels[s.Label] = true
	}
	if !labels["keep"] || labels["temp"] {
		t.Errorf("restored labels = %v, want persistent only (plus the timed one)", labels)
	}

	// Restart after expiry: the timed share is gone and gone from config.
	clk.Advance(2 * time.Minute)
	m3 := newManagerWithClock(t, cfg, clk)
	if err := m3.Load(); err != nil {
		t.Fatal(err)
	}
	if _, ok := m3.Get(timed.ShareID); ok {
		t.Error("an expired timed share must not be restored")
	}
	if got := len(m3.List()); got != 1 {
		t.Errorf("after expiry %d shares restored, want only the persistent one", got)
	}
	if raw := string(cfg.Get().Shares); strings.Contains(raw, timed.ShareID) {
		t.Error("expired share is still in the saved config")
	}
}

// A one-time share that nobody downloaded still ends after the 24 h safety
// expiry, including across a restart.
func TestOneTimeSafetyExpiry(t *testing.T) {
	cfg, _ := config.Open(t.TempDir())
	clk := newClock()
	m1 := newManagerWithClock(t, cfg, clk)
	s, err := m1.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimeOneTime})
	if err != nil {
		t.Fatal(err)
	}
	if s.Lifetime.ExpiresAt == nil || s.Lifetime.ExpiresAt.Sub(clk.Now()) != OneTimeSafetyExpiry {
		t.Fatalf("one-time share should carry a %v safety expiry", OneTimeSafetyExpiry)
	}
	clk.Advance(23 * time.Hour)
	m2 := newManagerWithClock(t, cfg, clk)
	_ = m2.Load()
	if _, ok := m2.Get(s.ShareID); !ok {
		t.Error("one-time share should still be there at 23 h")
	}
	clk.Advance(2 * time.Hour)
	m3 := newManagerWithClock(t, cfg, clk)
	_ = m3.Load()
	if _, ok := m3.Get(s.ShareID); ok {
		t.Error("one-time share should be gone after 24 h")
	}
}

// After expiry no new requests are served, but a peer that was already
// transferring may finish within the grace period; strangers may not.
func TestGraceLetsActiveTransferFinish(t *testing.T) {
	cfg, _ := config.Open(t.TempDir())
	clk := newClock()
	m := newManagerWithClock(t, cfg, clk)
	s, _ := m.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimeTimed, DurationSec: 30})

	_, cancel := context.WithCancel(context.Background())
	release := m.TrackTransfer(s.ShareID, "alice", cancel)

	clk.Advance(31 * time.Second) // expired
	if _, ok := m.GetVisible(s.ShareID, true, "", "alice", nil); ok {
		t.Error("an expired share must not serve new (non-transfer) requests")
	}
	if got := len(m.List()); got != 0 {
		t.Errorf("expired share still listed (%d)", got)
	}
	if _, ok := m.GetForTransfer(s.ShareID, true, "", "alice", nil); !ok {
		t.Error("alice is mid-transfer and should be allowed to finish")
	}
	if _, ok := m.GetForTransfer(s.ShareID, true, "", "mallory", nil); ok {
		t.Error("a peer that was not transferring must get nothing after expiry")
	}
	views := m.LocalViews()
	if len(views) != 1 || views[0].State != "finishing" || views[0].ActiveTransfers != 1 || views[0].DrainUntil == nil {
		t.Errorf("local view should show a finishing share with 1 active transfer, got %+v", views)
	}

	// Sweep keeps it while the stream is open...
	if m.sweep() {
		t.Error("sweep must not retire a share that still has an open stream")
	}
	// ...and retires it once the stream ended and the window passed.
	release()
	clk.Advance(ActiveWindow + time.Second)
	if !m.sweep() {
		t.Error("sweep should retire the finished share")
	}
	if _, ok := m.GetForTransfer(s.ShareID, true, "", "alice", nil); ok {
		t.Error("retired share must be gone")
	}
	if reason, ok := m.Ended(s.ShareID, "alice"); !ok || reason != "expired" {
		t.Errorf("alice should be told 'expired', got %q %v", reason, ok)
	}
	if _, ok := m.Ended(s.ShareID, "mallory"); ok {
		t.Error("a peer that never used the share must not learn it existed")
	}
}

// Grace is capped: after graceMax even an active transfer is cut.
func TestGraceIsCapped(t *testing.T) {
	cfg, _ := config.Open(t.TempDir())
	clk := newClock()
	m := newManagerWithClock(t, cfg, clk)
	s, _ := m.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimeTimed, DurationSec: 30})
	cancelled := make(chan struct{})
	ctx, cancel := context.WithCancel(context.Background())
	go func() { <-ctx.Done(); close(cancelled) }()
	m.TrackTransfer(s.ShareID, "alice", cancel)

	clk.Advance(30*time.Second + GraceMax + time.Second)
	if _, ok := m.GetForTransfer(s.ShareID, true, "", "alice", nil); ok {
		t.Error("grace must end after GraceMax even for an active peer")
	}
	if !m.sweep() {
		t.Fatal("sweep should retire the share after GraceMax")
	}
	select {
	case <-cancelled:
	case <-time.After(time.Second):
		t.Error("the open stream should be cancelled when grace runs out")
	}
}

// "Stop now" ends everything immediately and cancels open streams.
func TestStopNowCancelsStreams(t *testing.T) {
	cfg, _ := config.Open(t.TempDir())
	clk := newClock()
	m := newManagerWithClock(t, cfg, clk)
	s, _ := m.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimeUntilStopped})
	ctx, cancel := context.WithCancel(context.Background())
	m.TrackTransfer(s.ShareID, "alice", cancel)
	if !m.Stop(s.ShareID) {
		t.Fatal("Stop should find the share")
	}
	select {
	case <-ctx.Done():
	case <-time.After(time.Second):
		t.Error("Stop must cancel in-flight streams")
	}
	if _, ok := m.GetForTransfer(s.ShareID, true, "", "alice", nil); ok {
		t.Error("a stopped share must serve nothing, not even to active peers")
	}
	if reason, ok := m.Ended(s.ShareID, "alice"); !ok || reason != "stopped" {
		t.Errorf("want reason 'stopped', got %q %v", reason, ok)
	}
}

// StopAll is a hard stop with no grace (spec §11.3).
func TestStopAllHasNoGrace(t *testing.T) {
	cfg, _ := config.Open(t.TempDir())
	m := newManagerWithClock(t, cfg, newClock())
	a, _ := m.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimePersistent})
	_, cancel := context.WithCancel(context.Background())
	m.TrackTransfer(a.ShareID, "alice", cancel)
	if n := m.StopAll(); n != 1 {
		t.Fatalf("StopAll stopped %d, want 1", n)
	}
	if _, ok := m.GetForTransfer(a.ShareID, true, "", "alice", nil); ok {
		t.Error("StopAll must not leave a grace window")
	}
}

// Only one-time shares are consumed, only once, and the consumer may finish.
func TestConsumeOneTime(t *testing.T) {
	cfg, _ := config.Open(t.TempDir())
	clk := newClock()
	m := newManagerWithClock(t, cfg, clk)
	ot, _ := m.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimeOneTime})
	plain, _ := m.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimePersistent})

	if m.Consume(plain.ShareID, "alice") {
		t.Error("a persistent share must not be consumable")
	}
	m.TouchTransfer(ot.ShareID, "alice")
	m.TouchTransfer(ot.ShareID, "bob")
	if !m.Consume(ot.ShareID, "alice") {
		t.Fatal("first completion should consume the one-time share")
	}
	if m.Consume(ot.ShareID, "bob") {
		t.Error("a one-time share can be consumed only once")
	}
	if _, ok := m.GetVisible(ot.ShareID, true, "", "carol", nil); ok {
		t.Error("a consumed share must vanish for new requests")
	}
	if _, ok := m.GetForTransfer(ot.ShareID, true, "", "bob", nil); !ok {
		t.Error("bob was mid-transfer and may finish (grace)")
	}
	if reason, ok := m.Ended(ot.ShareID, "bob"); !ok || reason != "completed" {
		t.Errorf("bob should be told 'completed', got %q %v", reason, ok)
	}
	// A consumed share is not persisted, so a restart cannot revive it.
	m2 := newManagerWithClock(t, cfg, clk)
	_ = m2.Load()
	if _, ok := m2.Get(ot.ShareID); ok {
		t.Error("a consumed one-time share must not come back after a restart")
	}
}

// When the consumer is the only one transferring, a consumed share retires at
// once rather than lingering as "finishing".
func TestConsumeRetiresImmediatelyWhenIdle(t *testing.T) {
	cfg, _ := config.Open(t.TempDir())
	m := newManagerWithClock(t, cfg, newClock())
	ot, _ := m.Add(tempShareDir(t), AddOptions{LifetimeType: LifetimeOneTime})
	m.TouchTransfer(ot.ShareID, "alice")
	if !m.Consume(ot.ShareID, "alice") {
		t.Fatal("should consume")
	}
	if got := len(m.LocalViews()); got != 0 {
		t.Errorf("consumed share should be gone for its owner too, still have %d view(s)", got)
	}
	if reason, ok := m.Ended(ot.ShareID, "alice"); !ok || reason != "completed" {
		t.Errorf("alice should still be told 'completed', got %q %v", reason, ok)
	}
}
