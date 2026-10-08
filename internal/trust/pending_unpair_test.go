package trust

import (
	"testing"
	"time"
)

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

// A successful pairing must delete any pending-unpair revoke for that
// fingerprint: the device was just paired again, so the queued "drop us" from
// the earlier unpair is stale and must never be delivered.
func TestPairingClearsPendingUnpair(t *testing.T) {
	st := newStore(t)
	st.AddPendingUnpair(Entry{Fingerprint: "peer-fp", Name: "Phone", Addrs: []string{"10.0.0.5"}, Port: 47800})
	if !st.HasPendingUnpair("peer-fp") {
		t.Fatal("test setup: the pending record should exist")
	}

	st.Pair(Entry{Fingerprint: "peer-fp", Name: "Phone", Mode: ModePair})

	if st.HasPendingUnpair("peer-fp") {
		t.Fatal("a successful pairing must clear the pending unpair")
	}
	if _, ok := st.PendingUnpair("peer-fp"); ok {
		t.Fatal("PendingUnpair should report nothing after the pairing")
	}
	if _, ok := st.Entry("peer-fp"); !ok {
		t.Fatal("the pairing entry should exist")
	}
}

// Each pending record carries the moment it was queued. A revoke that predates
// the current pairing's pairedAt is stale: delivering it would unpair the
// freshly paired device, so it must be reported as superseded.
func TestPendingUnpairSupersededByNewerPairing(t *testing.T) {
	st := newStore(t)

	// Pair first, then queue a revoke: queuedAt is now, pairedAt is in the past,
	// so the revoke is current and deliverable.
	st.Pair(Entry{Fingerprint: "peer-fp", Name: "Phone", CreatedAt: time.Now().Add(-time.Hour)})
	st.AddPendingUnpair(Entry{Fingerprint: "peer-fp"})
	if st.PendingUnpairSuperseded("peer-fp") {
		t.Fatal("a revoke queued after the pairing is not superseded")
	}

	// Imagine the pairing is newer than the queued revoke (a re-pair that raced
	// with the queued record): it must be refused.
	st2 := newStore(t)
	st2.AddPendingUnpair(Entry{Fingerprint: "peer-fp"})
	// Pair() clears pending, so re-add one to exercise the timestamp guard alone.
	st2.Pair(Entry{Fingerprint: "peer-fp", Name: "Phone", CreatedAt: time.Now().Add(time.Hour)})
	st2.AddPendingUnpair(Entry{Fingerprint: "peer-fp"})
	if !st2.PendingUnpairSuperseded("peer-fp") {
		t.Fatal("a revoke queued before a newer pairing must be superseded")
	}
	if st2.PendingUnpairSuperseded("nobody") {
		t.Fatal("no pending record means nothing is superseded")
	}
}

// A successful delivery bumps the generation. A racing retry that captured the
// generation before its sibling finished must not be able to re-arm the pending
// record: doing so would resurrect a revoke that already achieved the unpair.
func TestUnpairGenerationGuardPreventsRearm(t *testing.T) {
	st := newStore(t)
	st.AddPendingUnpair(Entry{Fingerprint: "peer-fp", Name: "Phone"})

	// Two racing retries both capture the token before either finishes.
	seen := st.UnpairGeneration("peer-fp")
	if seen != 0 {
		t.Fatalf("fresh store generation = %d, want 0", seen)
	}

	// Sibling A succeeds: bump the generation and clear the record.
	st.MarkUnpairDelivered("peer-fp")
	st.ClearPendingUnpair("peer-fp")
	if got := st.UnpairGeneration("peer-fp"); got != 1 {
		t.Fatalf("generation after delivery = %d, want 1", got)
	}

	// Sibling B fails and tries to remember. The stale token must be refused.
	if st.AddPendingUnpairIfGeneration("peer-fp", seen, Entry{Fingerprint: "peer-fp", Name: "Phone"}) {
		t.Fatal("a stale generation must not re-arm the pending record")
	}
	if st.HasPendingUnpair("peer-fp") {
		t.Fatal("the record was resurrected after a sibling already delivered")
	}
}

// A lone failure still records the pending revoke: the guard only refuses a
// re-arm when a sibling has completed a delivery in the meantime.
func TestUnpairGenerationGuardAllowsLoneFailure(t *testing.T) {
	st := newStore(t)
	seen := st.UnpairGeneration("peer-fp")
	if !st.AddPendingUnpairIfGeneration("peer-fp", seen, Entry{Fingerprint: "peer-fp", Name: "Phone"}) {
		t.Fatal("the first failure must arm the pending record")
	}
	if !st.HasPendingUnpair("peer-fp") {
		t.Fatal("the pending record should exist")
	}
	// A later retry of the now-existing record captures the (still current)
	// generation and may refresh it.
	seen2 := st.UnpairGeneration("peer-fp")
	if !st.AddPendingUnpairIfGeneration("peer-fp", seen2, Entry{Fingerprint: "peer-fp", Name: "Phone", Addrs: []string{"10.0.0.9"}}) {
		t.Fatal("an unchanged generation must allow refreshing the record")
	}
	e, _ := st.PendingUnpair("peer-fp")
	if len(e.Addrs) != 1 || e.Addrs[0] != "10.0.0.9" {
		t.Fatalf("the refreshed record = %+v", e)
	}
	// An empty fingerprint is never stored.
	if st.AddPendingUnpairIfGeneration("", 0, Entry{Name: "nobody"}) {
		t.Fatal("an empty fingerprint must be refused")
	}
}
