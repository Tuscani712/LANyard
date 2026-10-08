package inbox

import (
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
