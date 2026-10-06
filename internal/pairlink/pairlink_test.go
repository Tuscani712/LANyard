package pairlink

import (
	"strings"
	"testing"
)

const (
	testFP    = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	testNonce = "00112233445566778899aabbccddeeff"
)

func TestRoundTrip(t *testing.T) {
	in := Payload{
		Fingerprint: testFP,
		Name:        "Kitchen PC",
		Addrs:       []string{"192.168.1.20:47800", "10.0.0.5:47800"},
		Nonce:       testNonce,
	}
	out, err := Parse(Build(in))
	if err != nil {
		t.Fatalf("Parse(Build()): %v", err)
	}
	if out.Fingerprint != in.Fingerprint || out.Name != in.Name || out.Nonce != in.Nonce {
		t.Fatalf("round trip = %+v, want %+v", out, in)
	}
	if len(out.Addrs) != 2 || out.Addrs[0] != in.Addrs[0] || out.Addrs[1] != in.Addrs[1] {
		t.Fatalf("addrs = %v, want %v", out.Addrs, in.Addrs)
	}
}

func TestRoundTripWithoutName(t *testing.T) {
	out, err := Parse(Build(Payload{Fingerprint: testFP, Addrs: []string{"192.168.1.20:47800"}, Nonce: testNonce}))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if out.Name != "" {
		t.Fatalf("name = %q, want empty", out.Name)
	}
}

func TestParseRejectsMalformed(t *testing.T) {
	good := Build(Payload{Fingerprint: testFP, Addrs: []string{"192.168.1.20:47800"}, Nonce: testNonce})
	cases := map[string]string{
		"empty":         "",
		"wrong scheme":  strings.Replace(good, "lanyard://", "http://", 1),
		"wrong host":    strings.Replace(good, "lanyard://pair", "lanyard://other", 1),
		"missing fp":    "lanyard://pair?addr=192.168.1.20:47800&n=" + testNonce,
		"missing addr":  "lanyard://pair?fp=" + testFP + "&n=" + testNonce,
		"missing nonce": "lanyard://pair?fp=" + testFP + "&addr=192.168.1.20:47800",
		"extra param":   good + "&evil=1",
		"duplicate fp":  "lanyard://pair?fp=" + testFP + "&fp=" + testFP + "&addr=192.168.1.20:47800&n=" + testNonce,
		"short fp":      "lanyard://pair?fp=abcd&addr=192.168.1.20:47800&n=" + testNonce,
		"nonhex fp":     "lanyard://pair?fp=" + strings.Repeat("z", 64) + "&addr=192.168.1.20:47800&n=" + testNonce,
		"short nonce":   "lanyard://pair?fp=" + testFP + "&addr=192.168.1.20:47800&n=abcd",
		"bad host name": "lanyard://pair?fp=" + testFP + "&addr=example.com:47800&n=" + testNonce,
		"bad port":      "lanyard://pair?fp=" + testFP + "&addr=192.168.1.20:99999&n=" + testNonce,
		"no port":       "lanyard://pair?fp=" + testFP + "&addr=192.168.1.20&n=" + testNonce,
	}
	for name, raw := range cases {
		if _, err := Parse(raw); err == nil {
			t.Errorf("%s: expected an error, got nil", name)
		}
	}
}

func TestParseCapsAddrs(t *testing.T) {
	var addrs []string
	for i := 0; i < MaxAddrs+1; i++ {
		addrs = append(addrs, "192.168.1.20:47800")
	}
	if _, err := Parse(Build(Payload{Fingerprint: testFP, Addrs: addrs, Nonce: testNonce})); err == nil {
		t.Fatal("expected an error for too many addresses")
	}
}

func TestParseCapsLength(t *testing.T) {
	long := "lanyard://pair?fp=" + testFP + "&addr=192.168.1.20:47800&n=" + testNonce + "&name=" + strings.Repeat("a", MaxLen)
	if _, err := Parse(long); err == nil {
		t.Fatal("expected an error for an oversized link")
	}
}

func TestParseCapsName(t *testing.T) {
	long := strings.Repeat("n", maxNameLen+50)
	out, err := Parse(Build(Payload{Fingerprint: testFP, Name: long, Addrs: []string{"192.168.1.20:47800"}, Nonce: testNonce}))
	if err != nil {
		t.Fatalf("Parse: %v", err)
	}
	if len(out.Name) != maxNameLen {
		t.Fatalf("name length = %d, want %d", len(out.Name), maxNameLen)
	}
}
