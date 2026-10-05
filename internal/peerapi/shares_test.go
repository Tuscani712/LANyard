package peerapi

import "testing"

func TestParseRange(t *testing.T) {
	const size = 100
	cases := []struct {
		in         string
		start, end int64
		ok         bool
	}{
		{"bytes=0-9", 0, 9, true},
		{"bytes=10-", 10, 99, true},
		{"bytes=50-200", 50, 99, true},
		{"bytes=-10", 0, 0, false}, // suffix ranges unsupported
		{"bytes=0-9,20-29", 0, 0, false},
		{"items=0-9", 0, 0, false},
		{"bytes=100-", 0, 0, false}, // start past EOF
		{"bytes=9-5", 0, 0, false},
		{"bytes=x-y", 0, 0, false},
	}
	for _, c := range cases {
		s, e, ok := parseRange(c.in, size)
		if ok != c.ok || (ok && (s != c.start || e != c.end)) {
			t.Errorf("parseRange(%q) = (%d,%d,%v), want (%d,%d,%v)", c.in, s, e, ok, c.start, c.end, c.ok)
		}
	}
}
