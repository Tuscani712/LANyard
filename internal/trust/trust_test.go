package trust

import (
	"testing"

	"lanyard/internal/config"
)

func newStore(t *testing.T) *Store {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	return New(cfg, "self-fingerprint", nil)
}

func TestPairFlow(t *testing.T) {
	st := newStore(t)

	// Responder side: receive, then accept with the permissions we grant.
	in, err := st.CreateIncoming(ModePair, "peer-fp", "Bob", "bob-dev", "nonceA", Permissions{Browse: true})
	if err != nil {
		t.Fatalf("CreateIncoming: %v", err)
	}
	if v, _ := st.View(in.ID); v.SAS == "" {
		t.Error("incoming session should have a SAS once the peer nonce is known")
	}
	if _, err := st.Accept(in.ID, Permissions{Browse: true, Push: true}); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	e, ok := st.Entry("peer-fp")
	if !ok || !e.Permissions.Push {
		t.Fatalf("accept should store a paired entry with push, got %+v ok=%v", e, ok)
	}

	// Initiator side: create, learn the remote id/nonce, confirm.
	out := st.CreateOutgoing(ModePair, "peer-fp2", "Carol", "carol-dev", Permissions{Browse: true})
	st.SetRemote(out.ID, "remote-1", "nonceB")
	st.SetStatus(out.ID, StatusAccepted, "")
	if _, err := st.Confirm(out.ID); err != nil {
		t.Fatalf("Confirm: %v", err)
	}
	if e, ok := st.Entry("peer-fp2"); !ok || !e.Permissions.Browse {
		t.Fatalf("confirm should store the initiator's entry, got %+v ok=%v", e, ok)
	}

	// Authorization reflects permissions.
	if a := st.Access("peer-fp"); !a.Paired || !a.Browse || !a.Push {
		t.Errorf("access = %+v", a)
	}

	// Unpair revokes.
	if !st.Unpair("peer-fp") {
		t.Error("Unpair returned false")
	}
	if a := st.Access("peer-fp"); a.Paired {
		t.Errorf("access after unpair = %+v", a)
	}
}

func TestConnectSessionOffers(t *testing.T) {
	st := newStore(t)
	in, err := st.CreateIncoming(ModeConnect, "peer-fp", "Dana", "dana-dev", "nonceA", Permissions{})
	if err != nil {
		t.Fatal(err)
	}
	// Pending sessions are not yet usable.
	if a := st.Access("peer-fp"); a.SessionID != "" {
		t.Errorf("pending session should not grant access: %+v", a)
	}
	if _, err := st.Accept(in.ID, Permissions{}); err != nil {
		t.Fatal(err)
	}
	if err := st.Offer(in.ID, []string{"s_1", "s_2"}); err != nil {
		t.Fatal(err)
	}
	a := st.Access("peer-fp")
	if a.SessionID == "" || !a.Offered["s_1"] || !a.Offered["s_2"] || a.Offered["s_3"] {
		t.Errorf("connect access = %+v", a)
	}
	// An unpaired connect session must not authorize a random share.
	if a.Offered["s_9"] {
		t.Error("session should only expose offered shares")
	}
}

func TestPendingCap(t *testing.T) {
	st := newStore(t)
	if _, err := st.CreateIncoming(ModeConnect, "spammer", "S", "s", "n1", Permissions{}); err != nil {
		t.Fatal(err)
	}
	if _, err := st.CreateIncoming(ModeConnect, "spammer", "S", "s", "n2", Permissions{}); err == nil {
		t.Error("a second pending request from the same peer should be refused")
	}
}

func TestUpdatePermissions(t *testing.T) {
	st := newStore(t)
	st.Pair(Entry{DeviceID: "d", Name: "N", Fingerprint: "fp", Mode: ModePair, Permissions: Permissions{Browse: true}})
	if a := st.Access("fp"); !a.Browse || a.Push {
		t.Fatalf("initial access = %+v", a)
	}
	if !st.UpdatePermissions("fp", Permissions{Browse: false, Push: true, AskOver: 42}) {
		t.Fatal("UpdatePermissions returned false for a paired device")
	}
	a := st.Access("fp")
	if a.Browse || !a.Push || a.MaxPushBytes != 0 {
		t.Fatalf("access after update = %+v", a)
	}
	if e, _ := st.Entry("fp"); e.Permissions.AskOver != 42 {
		t.Fatalf("ask_over not stored: %+v", e.Permissions)
	}
	if st.UpdatePermissions("nobody", Permissions{}) {
		t.Error("updating an unknown fingerprint should fail")
	}
}
