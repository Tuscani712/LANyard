package xferlog

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
)

// LevelDebug entries are kept in the diagnostics history but filtered out of the
// default INFO desktop log, so routine healthy chatter does not spam it.
func TestDebugEntryReachesHistoryNotDefaultLog(t *testing.T) {
	rec := New(10)
	rec.Add(Entry{Area: AreaDiscovery, Level: LevelDebug, Outcome: "probe", FP: "abcdef0123456789"})

	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil)) // default level INFO
	rec.Record(log, Entry{Area: AreaDiscovery, Level: LevelDebug, Outcome: "probe", FP: "abcdef0123456789"})

	if strings.Contains(buf.String(), "probe") {
		t.Fatalf("debug entry leaked into the default log: %s", buf.String())
	}
	if !strings.Contains(rec.Report(), "probe") {
		t.Fatalf("debug entry missing from the diagnostics report:\n%s", rec.Report())
	}
}

// A debug-level entry is emitted when the handler is configured for DEBUG, so
// opt-in troubleshooting still sees successful probes.
func TestDebugEntryEmittedWhenLevelDebug(t *testing.T) {
	rec := New(10)
	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, &slog.HandlerOptions{Level: slog.LevelDebug}))
	rec.Record(log, Entry{Area: AreaDiscovery, Level: LevelDebug, Outcome: "probe", FP: "abcdef0123456789"})

	if !strings.Contains(buf.String(), "probe") || !strings.Contains(buf.String(), "level=DEBUG") {
		t.Fatalf("debug entry not logged at DEBUG level: %s", buf.String())
	}
}
