package inbox

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"testing"
	"time"

	"lanyard/internal/xferlog"
)

// Pushing is one of the four logged areas. A stalled incoming push is removed
// by the reaper and that removal must be recorded with the pushing tag and the
// stall outcome.
func TestStalledPushIsLogged(t *testing.T) {
	m := New(t.TempDir(), nil)
	defer m.Close()
	m.SetIdleTimeout(20 * time.Millisecond)
	m.SetStallTimeout(20 * time.Millisecond)
	rec := xferlog.New(50)
	m.SetXferLog(rec)

	if _, err := m.Offer("peer-fp-1234", "paired", []FileReq{{RelPath: "a.bin", Size: 1 << 20}}, 1<<30); err != nil {
		t.Fatalf("Offer: %v", err)
	}

	deadline := time.Now().Add(3 * time.Second)
	for m.Count() > 0 && time.Now().Before(deadline) {
		time.Sleep(10 * time.Millisecond)
	}
	if m.Count() != 0 {
		t.Fatal("stalled push was not removed by the reaper")
	}

	for _, e := range rec.Entries() {
		if e.Area == xferlog.AreaPushing && e.Outcome == "stall" {
			return
		}
	}
	t.Errorf("stalled push removal not logged as pushing/stall: %+v", rec.Entries())
}

// A receive that finishes records one line in the transfer log carrying the
// average speed over the whole push, so a completed transfer says how fast it
// went.
func TestFinishedPushLogsAverageSpeed(t *testing.T) {
	m := New(t.TempDir(), nil)
	defer m.Close()
	rec := xferlog.New(50)
	m.SetXferLog(rec)

	data := []byte("hello, lanyard")
	sum := sha256.Sum256(data)
	p, err := m.Offer("peer-fp-1234", "paired", []FileReq{{RelPath: "a.txt", Size: int64(len(data))}}, 0)
	if err != nil {
		t.Fatalf("Offer: %v", err)
	}
	if _, err := m.Receive(p.ID, "peer-fp-1234", "a.txt", hex.EncodeToString(sum[:]), bytes.NewReader(data)); err != nil {
		t.Fatalf("Receive: %v", err)
	}
	// Ensure the push has a measurable, non-zero lifetime.
	time.Sleep(2 * time.Millisecond)
	if !m.Finish(p.ID, "peer-fp-1234") {
		t.Fatal("Finish returned false")
	}

	for _, e := range rec.Entries() {
		if e.Step != xferlog.StepComplete || e.Direction != xferlog.DirectionReceive {
			continue
		}
		if e.Bytes != int64(len(data)) {
			t.Errorf("finish log bytes = %d, want %d", e.Bytes, len(data))
		}
		if e.SpeedBps <= 0 {
			t.Errorf("finish log average speed = %d, want a positive value", e.SpeedBps)
		}
		return
	}
	t.Fatalf("finish was not logged with the average speed: %+v", rec.Entries())
}
