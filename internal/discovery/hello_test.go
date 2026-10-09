package discovery

import (
	"encoding/json"
	"strings"
	"testing"
)

// The desktop advertises its real receiver caps in hello so a sender can batch.
func TestHelloOfferLimitsMarshal(t *testing.T) {
	h := Hello{
		DeviceID: "Desk", Fingerprint: "fp", Name: "Desk", OS: "linux",
		Version: "1.1.0", Port: 41234,
		MaxOfferBytes: 128 << 20, MaxOfferFiles: 500000,
	}
	b, err := json.Marshal(h)
	if err != nil {
		t.Fatal(err)
	}
	got := string(b)
	for _, want := range []string{`"max_offer_bytes":134217728`, `"max_offer_files":500000`} {
		if !strings.Contains(got, want) {
			t.Errorf("hello JSON %s missing %s", got, want)
		}
	}
}

// A golden body with the agreed wire field names round-trips.
func TestHelloOfferLimitsGoldenRoundTrip(t *testing.T) {
	golden := `{"device_id":"Phone","fingerprint":"abc","name":"Phone","os":"android","version":"1.0","port":1234,"max_offer_bytes":8388608,"max_offer_files":50000}`
	var h Hello
	if err := json.Unmarshal([]byte(golden), &h); err != nil {
		t.Fatal(err)
	}
	if h.MaxOfferBytes != 8<<20 {
		t.Errorf("MaxOfferBytes = %d, want %d", h.MaxOfferBytes, 8<<20)
	}
	if h.MaxOfferFiles != 50000 {
		t.Errorf("MaxOfferFiles = %d, want 50000", h.MaxOfferFiles)
	}
	out, err := json.Marshal(h)
	if err != nil {
		t.Fatal(err)
	}
	var again Hello
	if err := json.Unmarshal(out, &again); err != nil {
		t.Fatal(err)
	}
	if again.MaxOfferBytes != h.MaxOfferBytes || again.MaxOfferFiles != h.MaxOfferFiles {
		t.Errorf("round trip lost limits: %+v", again)
	}
}

// A hello from an older peer that predates the fields decodes to zero, which is
// what makes the sender fall back to the floor limits.
func TestHelloLegacyHasNoOfferLimits(t *testing.T) {
	var h Hello
	if err := json.Unmarshal([]byte(`{"device_id":"Old","fingerprint":"x","name":"Old","os":"android","version":"0.9","port":1}`), &h); err != nil {
		t.Fatal(err)
	}
	if h.MaxOfferBytes != 0 || h.MaxOfferFiles != 0 {
		t.Errorf("legacy hello limits = %d/%d, want 0/0", h.MaxOfferBytes, h.MaxOfferFiles)
	}
}
