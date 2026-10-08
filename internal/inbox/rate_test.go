package inbox

import (
	"math"
	"testing"
	"time"
)

const testMB = int64(1) << 20

// A steady stream must read back as its true rate over the window.
func TestRollingRateSteady(t *testing.T) {
	base := time.Unix(0, 0)
	samples := []rateSample{
		{at: base, bytes: 0},
		{at: base.Add(1 * time.Second), bytes: 2 * testMB},
		{at: base.Add(2 * time.Second), bytes: 4 * testMB},
		{at: base.Add(3 * time.Second), bytes: 6 * testMB},
	}
	got := rollingRate(samples, base.Add(3*time.Second), rateWindow)
	if want := float64(2 * testMB); math.Abs(got-want) > 1 {
		t.Fatalf("steady rate = %.1f, want %.1f", got, want)
	}
}

// A transfer that stalls and then resumes must not report a spike: the bytes
// that arrive on resume are smoothed across the whole window, not divided by
// the short time since the pause ended.
func TestRollingRateNoSpikeAfterPause(t *testing.T) {
	base := time.Unix(0, 0)
	samples := []rateSample{
		{at: base, bytes: 0},
		{at: base.Add(1 * time.Second), bytes: 2 * testMB},
		{at: base.Add(2 * time.Second), bytes: 4 * testMB},
		{at: base.Add(9 * time.Second), bytes: 4 * testMB},  // stall ends
		{at: base.Add(10 * time.Second), bytes: 5 * testMB}, // resumed, burst
	}
	steady := float64(2 * testMB)
	got := rollingRate(samples, base.Add(10*time.Second), rateWindow)
	if got > steady {
		t.Fatalf("resumed rate %.1f spiked above the steady rate %.1f", got, steady)
	}
	if got <= 0 {
		t.Fatalf("resumed rate = %.1f, want a smoothed positive value", got)
	}
}

// A fresh transfer has no history: a lone sample reads as nothing rather than
// an invented rate, and once a second sample exists it stays below the raw
// instantaneous value.
func TestRollingRateFreshStart(t *testing.T) {
	base := time.Unix(0, 0)
	if got := rollingRate([]rateSample{{at: base, bytes: 0}}, base, rateWindow); got != 0 {
		t.Fatalf("single-sample rate = %.1f, want 0", got)
	}
	samples := []rateSample{
		{at: base, bytes: 0},
		{at: base.Add(500 * time.Millisecond), bytes: 1 * testMB},
	}
	got := rollingRate(samples, base.Add(500*time.Millisecond), rateWindow)
	if got <= 0 {
		t.Fatalf("fresh-start rate = %.1f, want positive", got)
	}
	if got > float64(2*testMB) {
		t.Fatalf("fresh-start rate %.1f looks like a spike", got)
	}
}

// The sampler publishes the smoothed rate, and after a long silence it
// publishes nothing rather than the stale value from before the stall.
func TestObserveRatePublishesSmoothedSpeed(t *testing.T) {
	base := time.Unix(0, 0)
	p := &Push{}
	p.observeRate(base)
	p.live.Store(6 * testMB)
	p.observeRate(base.Add(3 * time.Second))
	if want := float64(2 * testMB); math.Abs(p.speed()-want) > 1 {
		t.Fatalf("speed = %.1f, want %.1f", p.speed(), want)
	}
	p.observeRate(base.Add(10 * time.Second))
	if got := p.speed(); got != 0 {
		t.Fatalf("speed after a stall = %.1f, want 0", got)
	}
}
