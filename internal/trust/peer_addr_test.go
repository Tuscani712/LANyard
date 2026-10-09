package trust

import "testing"

// H5: a paired peer that re-announces at the same address but a new peer port
// must have its stored address refreshed (a port change alone is a change).
func TestSetPeerAddrUpdatesOnPortChange(t *testing.T) {
	st := newStore(t)
	st.Pair(Entry{DeviceID: "d", Name: "N", Fingerprint: "fp", Mode: ModePair})

	if !st.SetPeerAddr("fp", []string{"10.0.0.5"}, 47800) {
		t.Fatal("first address should be recorded")
	}
	if !st.SetPeerAddr("fp", []string{"10.0.0.5"}, 51234) {
		t.Fatal("a new port for the same address must be recorded")
	}
	e, ok := st.Entry("fp")
	if !ok || e.Port != 51234 || len(e.Addrs) != 1 || e.Addrs[0] != "10.0.0.5" {
		t.Fatalf("stored address = %+v, ok=%v; want port 51234", e, ok)
	}
	if st.SetPeerAddr("fp", []string{"10.0.0.5"}, 51234) {
		t.Fatal("an unchanged address and port should not report a change")
	}
}
