package discovery

import (
	"context"
	"log/slog"
	"strings"
	"sync"
	"testing"

	"lanyard/internal/xferlog"
)

// captureHandler records every entry that passes its level threshold so a test
// can assert what did (and did not) reach the default desktop log.
type captureHandler struct {
	mu      sync.Mutex
	level   slog.Level
	records []slog.Record
}

func (h *captureHandler) Enabled(_ context.Context, l slog.Level) bool { return l >= h.level }

func (h *captureHandler) Handle(_ context.Context, r slog.Record) error {
	h.mu.Lock()
	h.records = append(h.records, r.Clone())
	h.mu.Unlock()
	return nil
}

func (h *captureHandler) WithAttrs([]slog.Attr) slog.Handler { return h }
func (h *captureHandler) WithGroup(string) slog.Handler      { return h }

// levelOf returns the level of the first captured record carrying outcome, and
// whether one was captured at all.
func (h *captureHandler) levelOf(outcome string) (slog.Level, bool) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, r := range h.records {
		match := false
		r.Attrs(func(a slog.Attr) bool {
			if a.Key == "outcome" && a.Value.String() == outcome {
				match = true
				return false
			}
			return true
		})
		if match {
			return r.Level, true
		}
	}
	return 0, false
}

func (h *captureHandler) hasLevel(l slog.Level) bool {
	h.mu.Lock()
	defer h.mu.Unlock()
	for _, r := range h.records {
		if r.Level == l {
			return true
		}
	}
	return false
}

func reachableProbe(context.Context, string, int) (string, *Hello, error) {
	return strings.Repeat("a", 64), &Hello{Name: "peer"}, nil
}

// A healthy liveness probe is routine chatter: it must land in the diagnostics
// history at DEBUG and must not appear in the default (INFO) desktop log, so a
// 5-second sweep cannot fill it.
func TestSuccessfulProbeIsDebugNotInfo(t *testing.T) {
	h := &captureHandler{level: slog.LevelInfo}
	rec := xferlog.New(200)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), reachableProbe, slog.New(h))
	m.SetXferLog(rec)

	fp := strings.Repeat("a", 64)
	seedPeer(m, fp[:16], fp, []string{"10.0.0.9"}, 47800)

	m.sweep()
	m.wg.Wait()

	if _, ok := h.levelOf("probe"); ok {
		t.Fatal("a successful probe must not reach the default INFO desktop log")
	}
	var found bool
	for _, e := range rec.Entries() {
		if e.Outcome == "probe" {
			found = true
			if e.Level != xferlog.LevelDebug {
				t.Fatalf("successful probe level = %q, want debug", e.Level)
			}
		}
	}
	if !found {
		t.Fatal("successful probe missing from the diagnostics history")
	}
}

// A failing probe is a real signal and must stay visible at WARN with its
// reason, even though the healthy repeats are demoted.
func TestFailedProbeStaysVisible(t *testing.T) {
	h := &captureHandler{level: slog.LevelInfo}
	rec := xferlog.New(200)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), failingProbe, slog.New(h))
	m.SetXferLog(rec)

	fp := strings.Repeat("b", 64)
	seedPeer(m, fp[:16], fp, []string{"10.0.0.10"}, 47800)

	m.sweep()
	m.wg.Wait()

	lvl, ok := h.levelOf("probe")
	if !ok {
		t.Fatal("a failed probe must stay in the default desktop log")
	}
	if lvl != slog.LevelWarn {
		t.Fatalf("failed probe level = %v, want WARN", lvl)
	}
}

// A peer that simply stops answering is a normal timeout/leave, so its
// eviction must be INFO, not ERROR.
func TestNormalEvictionIsNotError(t *testing.T) {
	h := &captureHandler{level: slog.LevelInfo}
	rec := xferlog.New(200)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), failingProbe, slog.New(h))
	m.SetXferLog(rec)

	fp := strings.Repeat("c", 64)
	seedPeer(m, fp[:16], fp, []string{"10.0.0.11"}, 47800)

	for i := 0; i < defaultEvictAfter; i++ {
		m.sweep()
		m.wg.Wait()
	}

	lvl, ok := h.levelOf("evict")
	if !ok {
		t.Fatal("eviction must remain in the desktop log")
	}
	if lvl != slog.LevelInfo {
		t.Fatalf("normal eviction level = %v, want INFO (not ERROR)", lvl)
	}
	if h.hasLevel(slog.LevelError) {
		t.Fatal("a normal peer timeout must not log at ERROR")
	}
}

// A peer presenting the wrong certificate is a genuine fault; that eviction
// keeps ERROR.
func TestCertificateMismatchEvictionIsError(t *testing.T) {
	probe := func(context.Context, string, int) (string, *Hello, error) {
		return strings.Repeat("9", 64), &Hello{Name: "impostor"}, nil
	}
	h := &captureHandler{level: slog.LevelInfo}
	rec := xferlog.New(200)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), probe, slog.New(h))
	m.SetXferLog(rec)

	fp := strings.Repeat("d", 64)
	seedPeer(m, fp[:16], fp, []string{"10.0.0.12"}, 47800)

	for i := 0; i < defaultEvictAfter; i++ {
		m.sweep()
		m.wg.Wait()
	}

	lvl, ok := h.levelOf("evict")
	if !ok {
		t.Fatal("eviction must remain in the desktop log")
	}
	if lvl != slog.LevelError {
		t.Fatalf("certificate-mismatch eviction level = %v, want ERROR", lvl)
	}
}
