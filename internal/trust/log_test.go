package trust

import (
	"testing"

	"lanyard/internal/xferlog"
)

// Pairing is one of the four logged areas: request, accept, confirm, refuse,
// close and unpair must all land in the shared log with the pairing tag.
func TestPairingLifecycleLogged(t *testing.T) {
	st := newStore(t)
	rec := xferlog.New(200)
	st.SetXferLog(rec)

	in, err := st.CreateIncoming(ModePair, "peer-fp", "Bob", "bob-dev", "nonceA", Permissions{Browse: Allow})
	if err != nil {
		t.Fatalf("CreateIncoming: %v", err)
	}
	if _, err := st.Accept(in.ID, Permissions{Browse: Allow, Push: Allow}); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if _, err := st.ActivateRemote(in.ID); err != nil {
		t.Fatalf("ActivateRemote: %v", err)
	}

	out := st.CreateOutgoing(ModeConnect, "carol-fp", "Carol", "carol-dev", Permissions{Browse: Allow})
	st.SetStatus(out.ID, StatusRejected, "user declined")
	st.Close(out.ID)

	st.Unpair("peer-fp")

	want := map[string]bool{"request": false, "accept": false, "confirm": false, "refuse": false, "close": false, "unpair": false}
	for _, e := range rec.Entries() {
		if e.Area != xferlog.AreaPairing {
			t.Errorf("entry not tagged pairing: %+v", e)
		}
		if _, ok := want[e.Outcome]; ok {
			want[e.Outcome] = true
		}
	}
	for outcome, ok := range want {
		if !ok {
			t.Errorf("pairing outcome %q not logged: %+v", outcome, rec.Entries())
		}
	}
	// The log never carries the full peer fingerprint, only a short id.
	for _, e := range rec.Entries() {
		if len(e.FP) > 16 {
			t.Errorf("pairing log kept a full fingerprint: %q", e.FP)
		}
	}
}
