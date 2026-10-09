package inbox

import "time"

// RateWindow is the trailing span over which the receive rate is smoothed. Five
// seconds is long enough that a brief pause cannot turn into a spike when
// progress resumes, yet short enough that the shown rate still feels live. It is
// the one shared definition of the window in Go: the sending path (transfer)
// reports its speed over the same span, and web/app.js mirrors the value.
const RateWindow = 5 * time.Second

// RateSampleEvery bounds how often a cumulative-bytes observation is taken, so
// the sampler stays cheap under a fast LAN stream while still giving the window
// enough points.
const RateSampleEvery = 200 * time.Millisecond

// RateDisplayEvery is the minimum gap between two updates of the displayed
// speed/ETA text. The underlying sampling keeps running at RateSampleEvery (and
// byte progress keeps advancing with every read); only the text the UI renders
// is gated to this slower cadence so it cannot flicker four times a second.
// web/app.js mirrors the value.
const RateDisplayEvery = time.Second

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

// rateAndETA is the smoothed transfer rate and the ETA derived from it.
type rateAndETA struct {
	bytesPerSecond float64
	etaSeconds     int
}

// computeRateETA is a pure function: from cumulative byte samples in time order,
// the evaluation time, the trailing window and the bytes still to transfer, it
// returns the smoothed rate and the ETA computed from that same rate alone. The
// ETA is remaining / smoothed-bytes-per-second, never remaining / instantaneous
// bytes, so a burst after a pause cannot make it jump. It is 0 when there is no
// rate or nothing left to transfer.
func computeRateETA(samples []rateSample, now time.Time, window time.Duration, remaining int64) rateAndETA {
	bps := rollingRate(samples, now, window)
	eta := 0
	if bps > 0 && remaining > 0 {
		eta = int(float64(remaining) / bps)
	}
	return rateAndETA{bytesPerSecond: bps, etaSeconds: eta}
}

// displayDue reports whether the displayed speed/ETA text may refresh at now,
// given the last time it was refreshed and the minimum gap between updates. A
// zero last time (the first update) is always due. It is a pure function so the
// "at most one change per RateDisplayEvery" property is testable without a
// clock.
func displayDue(last, now time.Time, every time.Duration) bool {
	if every <= 0 {
		return true
	}
	return last.IsZero() || now.Sub(last) >= every
}

// RollingCountPerSecond returns the event rate over the trailing window ending
// at now, given event timestamps in time order. It is used for the
// files-per-second rate that keeps a many-small-files ETA honest: dividing the
// number of completions inside the window by the full window width (rather than
// the span actually observed) mirrors rollingRate's anti-spike behaviour and
// yields a stable figure even when a burst of files lands together. It returns
// 0 when there is no window or no event inside it.
func RollingCountPerSecond(times []time.Time, now time.Time, window time.Duration) float64 {
	if window <= 0 || len(times) == 0 {
		return 0
	}
	start := now.Add(-window)
	n := 0
	for _, t := range times {
		if t.After(now) {
			break
		}
		if !t.Before(start) {
			n++
		}
	}
	if n == 0 {
		return 0
	}
	return float64(n) / window.Seconds()
}

// MaxETA combines the byte-based and file-based ETAs by taking the larger: a
// many-small-files push is latency-bound, so its byte rate can make the byte
// ETA swing wildly (hours) while the file rate stays honest. Taking the max
// never reports a finish sooner than either estimate allows, so the shown ETA
// is the conservative of the two. A non-positive value on either side is
// ignored.
func MaxETA(byteETA, fileETA int) int {
	if fileETA > byteETA {
		return fileETA
	}
	return byteETA
}
