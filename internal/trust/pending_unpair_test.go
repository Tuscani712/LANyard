package trust

import "testing"

// A device we unpaired locally but could not tell to drop us must be kept so
// the notification can be retried when it is next seen.
func TestPendingUnpairLifecycle(t *testing.T) {
	st := newStore(t)

	if st.HasPendingUnpair("fp") {
		t.Fatal("nothing should be pending yet")
	}
	st.AddPendingUnpair(Entry{Fingerprint: "fp", Name: "Phone", Addrs: []string{"10.0.0.5"}, Port: 47800})
	if !st.HasPendingUnpair("fp") {
		t.Fatal("the pending record was not added")
	}
	e, ok := st.PendingUnpair("fp")
	if !ok || e.Name != "Phone" || e.Port != 47800 || len(e.Addrs) != 1 {
		t.Fatalf("pending entry = %+v, ok=%v", e, ok)
	}
	if got := st.PendingUnpairs(); len(got) != 1 || got[0].Fingerprint != "fp" {
		t.Fatalf("PendingUnpairs = %+v", got)
	}
	if !st.ClearPendingUnpair("fp") {
		t.Fatal("ClearPendingUnpair should report it removed something")
	}
	if st.HasPendingUnpair("fp") || len(st.PendingUnpairs()) != 0 {
		t.Fatal("the pending record should be gone")
	}
	if st.ClearPendingUnpair("fp") {
		t.Fatal("clearing an absent record should report false")
	}
	// An empty fingerprint is not a valid key.
	st.AddPendingUnpair(Entry{Name: "nobody"})
	if len(st.PendingUnpairs()) != 0 {
		t.Fatal("an entry without a fingerprint must be ignored")
	}
}

// Re-pairing after a stale pairing handshake must replace the lingering pending
// request rather than being refused with "already waiting".
func TestRePairSupersedesPendingRequest(t *testing.T) {
	st := newStore(t)
	first, err := st.CreateIncoming(ModePair, "peer-fp", "Bob", "bob-dev", "nonceA", Permissions{})
	if err != nil {
		t.Fatalf("first CreateIncoming: %v", err)
	}
	second, err := st.CreateIncoming(ModePair, "peer-fp", "Bob", "bob-dev", "nonceB", Permissions{})
	if err != nil {
		t.Fatalf("re-pair should supersede the stale request, got: %v", err)
	}
	if second.ID == first.ID {
		t.Fatal("the new request should be a new session")
	}
	// Only the newer request may remain pending for this peer.
	pending := 0
	for _, v := range st.Sessions() {
		if v.PeerFP == "peer-fp" && v.Status == StatusPending {
			pending++
		}
	}
	if pending != 1 {
		t.Fatalf("pending pair sessions = %d, want 1", pending)
	}
}
