package transfer

import "testing"

// ActiveTransfer backs the discovery eviction guard: an in-flight job with a
// peer means that peer must be considered alive. Matching works by full
// fingerprint or by the peer's short id (first 16 hex characters).
func TestActiveTransfer(t *testing.T) {
	m := historyManager(t)
	fp := "abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789"
	short := fp[:16]

	m.mu.Lock()
	m.jobs["j1"] = &Job{ID: "j1", PeerID: fp, State: StateTransferring}
	m.mu.Unlock()

	if !m.ActiveTransfer(fp) {
		t.Fatal("an in-flight job must report an active transfer for its fingerprint")
	}
	if !m.ActiveTransfer(short) {
		t.Fatal("an in-flight job must match by short id too")
	}
	if m.ActiveTransfer("deadbeefdeadbeef") {
		t.Fatal("an unrelated peer must not report an active transfer")
	}

	for _, st := range []string{StateWaiting, StatePaused, StateDone, StateFailed} {
		m.mu.Lock()
		m.jobs["j1"].State = st
		m.mu.Unlock()
		if m.ActiveTransfer(fp) {
			t.Fatalf("state %q must not count as an active transfer", st)
		}
		m.mu.Lock()
		m.jobs["j1"].State = StateTransferring
		m.mu.Unlock()
	}
}
