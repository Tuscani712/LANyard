package inbox

import "time"

// rateWindow is the trailing span over which the receive rate is smoothed.
// Three seconds is long enough that a brief pause cannot turn into a spike when
// progress resumes, yet short enough that the shown rate still feels live.
const rateWindow = 3 * time.Second

// rateSampleEvery bounds how often a cumulative-bytes observation is taken, so
// the sampler stays cheap under a fast LAN stream while still giving the
// three-second window enough points.
const rateSampleEvery = 200 * time.Millisecond

// rateSample is one observation of the cumulative bytes received for a push at
// a point in time.
type rateSample struct {
	at    time.Time
	bytes int64
}

// rollingRate returns the smoothed receive rate in bytes per second over the
// trailing window ending at now, given cumulative byte samples in time order.
//
// It deliberately divides the bytes observed inside the window by the full
// window width rather than by the span actually sampled, so a burst of bytes
// that arrives when a stalled transfer resumes cannot register as a spike. It
// returns 0 when there is nothing to report — fewer than two samples, no
// samples inside the window, or no bytes gained — which the caller renders as
// no speed at all.
func rollingRate(samples []rateSample, now time.Time, window time.Duration) float64 {
	if window <= 0 || len(samples) < 2 {
		return 0
	}
	start := now.Add(-window)
	var first, last *rateSample
	for i := range samples {
		s := &samples[i]
		if s.at.After(now) {
			break
		}
		if first == nil && !s.at.Before(start) {
			first = s
		}
		last = s
	}
	if first == nil || last == nil || first == last {
		return 0
	}
	gained := last.bytes - first.bytes
	if gained <= 0 {
		return 0
	}
	return float64(gained) / window.Seconds()
}
