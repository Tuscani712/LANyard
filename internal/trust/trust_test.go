package trust

import (
	"testing"
	"time"

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
	// Accepting must not pair yet: the entry appears only when the initiator
	// confirms the SAS (ActivateRemote), so a cancelled pairing leaves nothing.
	if _, ok := st.Entry("peer-fp"); ok {
		t.Fatal("accept must not create a paired entry before confirmation")
	}
	if _, err := st.ActivateRemote(in.ID); err != nil {
		t.Fatalf("ActivateRemote: %v", err)
	}
	e, ok := st.Entry("peer-fp")
	if !ok || !e.Permissions.Push {
		t.Fatalf("confirmation should store a paired entry with push, got %+v ok=%v", e, ok)
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

func TestIncomingSessionFiresCallback(t *testing.T) {
	st := newStore(t)
	got := make(chan string, 1)
	st.SetOnIncoming(func(sess *Session) { got <- sess.Mode })
	if _, err := st.CreateIncoming(ModePair, "peer-fp", "Bob", "bob-dev", "nonceA", Permissions{}); err != nil {
		t.Fatalf("CreateIncoming: %v", err)
	}
	select {
	case mode := <-got:
		if mode != ModePair {
			t.Fatalf("callback mode = %q, want %q", mode, ModePair)
		}
	case <-time.After(2 * time.Second):
		t.Fatal("onIncoming was not called for an incoming request")
	}
}

func TestOutgoingSessionDoesNotFireCallback(t *testing.T) {
	st := newStore(t)
	got := make(chan struct{}, 1)
	st.SetOnIncoming(func(*Session) { got <- struct{}{} })
	st.CreateOutgoing(ModeConnect, "peer-fp", "Bob", "bob-dev", Permissions{})
	select {
	case <-got:
		t.Fatal("onIncoming must not fire for an outgoing request")
	case <-time.After(50 * time.Millisecond):
	}
}

// SetPeerAddr remembers the last address of a paired device so discovery can
// probe it directly when mDNS is silent.
func TestSetPeerAddr(t *testing.T) {
	st := newStore(t)
	st.Pair(Entry{DeviceID: "d", Name: "N", Fingerprint: "fp", Mode: ModePair})

	if !st.SetPeerAddr("fp", []string{"10.0.0.5"}, 47800) {
		t.Fatal("first address should be recorded")
	}
	e, ok := st.Entry("fp")
	if !ok || e.Port != 47800 || len(e.Addrs) != 1 || e.Addrs[0] != "10.0.0.5" {
		t.Fatalf("stored address = %+v, ok=%v", e, ok)
	}
	if st.SetPeerAddr("fp", []string{"10.0.0.5"}, 47800) {
		t.Fatal("an unchanged address should not report a change")
	}
	if !st.SetPeerAddr("fp", []string{"10.0.0.6"}, 47800) {
		t.Fatal("a new address should be recorded")
	}
	if st.SetPeerAddr("unknown", []string{"10.0.0.5"}, 47800) {
		t.Fatal("an unknown fingerprint must not record an address")
	}
	if st.SetPeerAddr("fp", nil, 0) {
		t.Fatal("a missing address/port must be ignored")
	}
}

func TestPairInviteMintConsume(t *testing.T) {
	st := newStore(t)
	tok, exp := st.MintPairInvite()
	if len(tok) < 32 {
		t.Fatalf("token too short: %q", tok)
	}
	if !exp.After(time.Now()) {
		t.Fatal("invite should expire in the future")
	}
	if !st.ConsumePairInvite(tok) {
		t.Fatal("the first consume of a fresh invite should succeed")
	}
	if st.ConsumePairInvite(tok) {
		t.Fatal("a reused invite must be rejected")
	}
}

func TestPairInviteExpiry(t *testing.T) {
	st := newStore(t)
	st.SetPairInviteTTL(-time.Second) // already expired
	tok, _ := st.MintPairInvite()
	if st.ConsumePairInvite(tok) {
		t.Fatal("an expired invite must be rejected")
	}
	if st.ConsumePairInvite(tok) {
		t.Fatal("an expired invite must stay consumed")
	}
}

func TestPairInviteUnknownRejected(t *testing.T) {
	st := newStore(t)
	if st.ConsumePairInvite("deadbeefdeadbeefdeadbeefdeadbeef") {
		t.Fatal("an unknown invite must be rejected")
	}
	if st.ConsumePairInvite("") {
		t.Fatal("an empty invite must be rejected")
	}
}

func TestMarkViaQR(t *testing.T) {
	st := newStore(t)
	in, err := st.CreateIncoming(ModePair, "peer-fp", "Bob", "bob-dev", "nonceA", Permissions{})
	if err != nil {
		t.Fatalf("CreateIncoming: %v", err)
	}
	if v, _ := st.View(in.ID); v.ViaQR {
		t.Fatal("a session must not start marked as QR")
	}
	if !st.MarkViaQR(in.ID) {
		t.Fatal("MarkViaQR should find the session")
	}
	if v, _ := st.View(in.ID); !v.ViaQR {
		t.Fatal("ViaQR should be set after MarkViaQR")
	}
}

// PairInviteValid lets the QR panel keep showing the same code until it expires
// or is used, instead of minting a new one on every poll.
func TestPairInviteValidReusesUntilExpiryOrUse(t *testing.T) {
	st := newStore(t)
	tok, exp := st.MintPairInvite()

	got, ok := st.PairInviteValid(tok)
	if !ok {
		t.Fatal("a fresh invite should be valid")
	}
	if !got.Equal(exp) {
		t.Fatalf("expiry = %v, want %v", got, exp)
	}
	if _, ok := st.PairInviteValid("not-a-real-nonce"); ok {
		t.Fatal("an unknown token must be invalid")
	}

	if !st.ConsumePairInvite(tok) {
		t.Fatal("ConsumePairInvite should succeed on a fresh invite")
	}
	if _, ok := st.PairInviteValid(tok); ok {
		t.Fatal("a consumed invite must be invalid")
	}
}
