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
		{at: base.Add(4 * time.Second), bytes: 8 * testMB},
		{at: base.Add(5 * time.Second), bytes: 10 * testMB},
	}
	got := rollingRate(samples, base.Add(5*time.Second), RateWindow)
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
	got := rollingRate(samples, base.Add(10*time.Second), RateWindow)
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
	if got := rollingRate([]rateSample{{at: base, bytes: 0}}, base, RateWindow); got != 0 {
		t.Fatalf("single-sample rate = %.1f, want 0", got)
	}
	samples := []rateSample{
		{at: base, bytes: 0},
		{at: base.Add(500 * time.Millisecond), bytes: 1 * testMB},
	}
	got := rollingRate(samples, base.Add(500*time.Millisecond), RateWindow)
	if got <= 0 {
		t.Fatalf("fresh-start rate = %.1f, want positive", got)
	}
	if got > float64(2*testMB) {
		t.Fatalf("fresh-start rate %.1f looks like a spike", got)
	}
}

// ETA is derived from the same smoothed rate: a burst after a pause must not
// make the ETA jump down (that would be the instantaneous rate), and the ETA
// must equal remaining / smoothed-bytes-per-second.
func TestComputeRateETANoJumpAfterBurst(t *testing.T) {
	base := time.Unix(0, 0)
	samples := []rateSample{{at: base, bytes: 0}}
	for i := 1; i <= 5; i++ {
		samples = append(samples, rateSample{at: base.Add(time.Duration(i) * time.Second), bytes: int64(i) * 2 * testMB})
	}
	// A long pause, then a 20 MB burst in 100 ms: instantaneous is 200 MB/s.
	at := base.Add(10*time.Second + 100*time.Millisecond)
	samples = append(samples,
		rateSample{at: at.Add(-100 * time.Millisecond), bytes: 10 * testMB},
		rateSample{at: at, bytes: 30 * testMB},
	)
	remaining := int64(100*testMB) - 30*testMB
	r := computeRateETA(samples, at, RateWindow, remaining)
	if r.bytesPerSecond <= 0 {
		t.Fatalf("smoothed rate = %.1f, want positive", r.bytesPerSecond)
	}
	if want := int(float64(remaining) / r.bytesPerSecond); r.etaSeconds != want {
		t.Fatalf("ETA = %d, want remaining/smoothed = %d", r.etaSeconds, want)
	}
	instETA := int(float64(remaining) / (float64(20*testMB) / 0.1))
	if r.etaSeconds <= instETA {
		t.Fatalf("ETA %d used the burst (instantaneous would be %d)", r.etaSeconds, instETA)
	}
}

// After a stall the smoothed rate reads as nothing rather than the stale value.
func TestComputeRateETAAfterStall(t *testing.T) {
	base := time.Unix(0, 0)
	samples := []rateSample{
		{at: base, bytes: 0},
		{at: base.Add(1 * time.Second), bytes: 2 * testMB},
	}
	r := computeRateETA(samples, base.Add(60*time.Second), RateWindow, 100*testMB)
	if r.bytesPerSecond != 0 || r.etaSeconds != 0 {
		t.Fatalf("after stall = (%.1f, %d), want (0, 0)", r.bytesPerSecond, r.etaSeconds)
	}
}

// displayDue must allow at most one displayed-text change per RateDisplayEvery:
// sampling may tick far faster, but the text may not.
func TestDisplayDueAtMostOncePerSecond(t *testing.T) {
	base := time.Unix(0, 0)
	var last time.Time
	var changes []time.Time
	for i := 0; i <= 200; i++ { // 20 s in 100 ms steps (faster than the sampler)
		now := base.Add(time.Duration(i) * 100 * time.Millisecond)
		if displayDue(last, now, RateDisplayEvery) {
			changes = append(changes, now)
			last = now
		}
	}
	if len(changes) == 0 {
		t.Fatal("no display updates at all")
	}
	for i := 1; i < len(changes); i++ {
		if gap := changes[i].Sub(changes[i-1]); gap < RateDisplayEvery {
			t.Fatalf("display changed after %v (< %v) at step %d", gap, RateDisplayEvery, i)
		}
	}
}

// The sampler publishes smoothed speed and, once due, an ETA from that same
// speed; after a long silence it publishes nothing rather than the stale value.
func TestObserveRatePublishesSmoothedSpeedAndETA(t *testing.T) {
	base := time.Unix(0, 0)
	p := &Push{Total: 100 * testMB}
	p.observeRate(base)
	p.live.Store(10 * testMB)
	p.observeRate(base.Add(5 * time.Second))
	if want := float64(2 * testMB); math.Abs(p.speed()-want) > 1 {
		t.Fatalf("speed = %.1f, want %.1f", p.speed(), want)
	}
	if want := int(float64(90*testMB) / float64(2*testMB)); p.eta() != want {
		t.Fatalf("eta = %d, want %d", p.eta(), want)
	}
	p.observeRate(base.Add(20 * time.Second))
	if got := p.speed(); got != 0 {
		t.Fatalf("speed after a stall = %.1f, want 0", got)
	}
	if got := p.eta(); got != 0 {
		t.Fatalf("eta after a stall = %d, want 0", got)
	}
}

// The displayed speed text must change at most once per RateDisplayEvery even
// though observeRate is driven far more often than that.
func TestObserveRateDisplayAtMostOncePerSecond(t *testing.T) {
	base := time.Unix(0, 0)
	p := &Push{Total: 1000 * testMB}
	var changes []time.Time
	last := math.NaN()
	for i := 0; i <= 200; i++ { // 20 s at 100 ms, 10 MB/s
		now := base.Add(time.Duration(i) * 100 * time.Millisecond)
		p.live.Store(int64(i) * testMB / 10)
		p.observeRate(now)
		if s := p.speed(); s != last {
			changes = append(changes, now)
			last = s
		}
	}
	for i := 1; i < len(changes); i++ {
		if gap := changes[i].Sub(changes[i-1]); gap < RateDisplayEvery {
			t.Fatalf("displayed speed changed after %v (< %v)", gap, RateDisplayEvery)
		}
	}
}

// MaxETA must take the conservative (larger) of the two estimates, so a
// many-small-files push whose byte ETA is optimistic is not understated, and an
// unknown side (0) never wins over a known one.
func TestMaxETACombination(t *testing.T) {
	cases := []struct {
		byteETA, fileETA, want int
	}{
		{0, 0, 0},
		{100, 0, 100},
		{0, 300, 300},
		{100, 200, 200}, // the file rate is the honest one, e.g. many small files
		{900, 200, 900}, // the byte ETA is the conservative one
		{200, 200, 200}, // tie
	}
	for _, c := range cases {
		if got := MaxETA(c.byteETA, c.fileETA); got != c.want {
			t.Errorf("MaxETA(%d, %d) = %d, want %d", c.byteETA, c.fileETA, got, c.want)
		}
	}
}

// RollingCountPerSecond counts only events inside the trailing window and
// divides by the full window, mirroring rollingRate's anti-spike smoothing.
func TestRollingCountPerSecond(t *testing.T) {
	base := time.Unix(0, 0)
	// Five completions, one per second, ending at t=5.
	times := make([]time.Time, 0, 5)
	for i := 1; i <= 5; i++ {
		times = append(times, base.Add(time.Duration(i)*time.Second))
	}
	if got := RollingCountPerSecond(times, base.Add(5*time.Second), RateWindow); got != 1 {
		t.Fatalf("5 in a 5s window = %v, want 1/s", got)
	}
	// A later evaluation prunes stale events: only the last two are inside.
	if got := RollingCountPerSecond(times, base.Add(9*time.Second), RateWindow); got != 0.4 {
		t.Fatalf("2 in a 5s window = %v, want 0.4/s", got)
	}
	if got := RollingCountPerSecond(nil, base, RateWindow); got != 0 {
		t.Fatalf("no events = %v, want 0", got)
	}
	if got := RollingCountPerSecond(times, base.Add(time.Hour), RateWindow); got != 0 {
		t.Fatalf("all events stale = %v, want 0", got)
	}
	if got := RollingCountPerSecond(times, base.Add(5*time.Second), 0); got != 0 {
		t.Fatalf("zero window = %v, want 0", got)
	}
}
