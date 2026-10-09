package xferlog

import "testing"

// FormatSpeed picks a readable unit adaptively and never renders a moving rate
// as 0.0 MB/s. The boundaries match web/app.js's fmtRate (decimal, 1000-based).
func TestFormatSpeedBoundaries(t *testing.T) {
	cases := []struct {
		bps  int64
		want string
	}{
		{0, ""},
		{-5, ""},
		{1, "1 B/s"},
		{999, "999 B/s"},
		{1000, "1.0 KB/s"},
		{1500, "1.5 KB/s"},
		{999*1000 + 999, "1000.0 KB/s"},
		{1_000_000, "1.0 MB/s"},
		{2_500_000, "2.5 MB/s"},
		{1_000_000_000, "1.0 GB/s"},
		{1_000_000_000_000, "1.0 TB/s"},
	}
	for _, c := range cases {
		if got := FormatSpeed(c.bps); got != c.want {
			t.Errorf("FormatSpeed(%d) = %q, want %q", c.bps, got, c.want)
		}
	}
}
