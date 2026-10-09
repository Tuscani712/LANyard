package trust

import (
	"testing"

	"lanyard/internal/config"
)

func newGrantStore(t *testing.T) *Store {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	return New(cfg, "self-fp", nil)
}

// The initiator must record the responder's actual (possibly narrowed) grant,
// not the set it asked for. Here the phone denies push: the local grant to the
// phone is unchanged, but "what we may do on the phone" must not show push.
func TestConfirmRecordsPeerNarrowedGrant(t *testing.T) {
	st := newGrantStore(t)
	out := st.CreateOutgoing(ModePair, "phone-fp", "Phone", "phone-dev", Permissions{Browse: Allow, Push: Allow})
	// The phone accepts and returns browse only.
	st.SetPeerGranted(out.ID, Permissions{Browse: Allow, Push: Never})
	st.SetStatus(out.ID, StatusAccepted, "")

	if _, err := st.Confirm(out.ID); err != nil {
		t.Fatalf("Confirm: %v", err)
	}
	e, ok := st.Entry("phone-fp")
	if !ok {
		t.Fatal("no trust entry after Confirm")
	}
	if !e.Permissions.Push.Allows() {
		t.Errorf("local grant lost push: %+v", e.Permissions)
	}
	if !e.PeerPermissions.Push.Denies() {
		t.Errorf("narrowed phone grant still shows push as allowed: %+v", e.PeerPermissions)
	}
	if !e.PeerPermissions.Browse.Allows() {
		t.Errorf("peer grant lost browse: %+v", e.PeerPermissions)
	}
}

// The responder records what the initiator granted it (the other direction).
func TestActivateRemoteRecordsPeerGrant(t *testing.T) {
	st := newGrantStore(t)
	in, err := st.CreateIncoming(ModePair, "desk-fp", "Desk", "desk-dev", "nonceA", Permissions{Browse: Allow, Push: Never})
	if err != nil {
		t.Fatalf("CreateIncoming: %v", err)
	}
	if _, err := st.Accept(in.ID, Permissions{Browse: Allow, Push: Allow}); err != nil {
		t.Fatalf("Accept: %v", err)
	}
	if _, err := st.ActivateRemote(in.ID); err != nil {
		t.Fatalf("ActivateRemote: %v", err)
	}
	e, ok := st.Entry("desk-fp")
	if !ok {
		t.Fatal("no trust entry after ActivateRemote")
	}
	if !e.PeerPermissions.Push.Denies() {
		t.Errorf("responder peer grant should reflect the initiator denying push: %+v", e.PeerPermissions)
	}
	if !e.PeerPermissions.Browse.Allows() {
		t.Errorf("responder peer grant lost browse: %+v", e.PeerPermissions)
	}
}

// The UI view carries the peer's granted set while a session is live.
func TestViewExposesPeerGrant(t *testing.T) {
	st := newGrantStore(t)
	out := st.CreateOutgoing(ModeConnect, "phone-fp", "Phone", "phone-dev", Permissions{Browse: Allow})
	st.SetPeerGranted(out.ID, Permissions{Browse: Allow})
	v, ok := st.View(out.ID)
	if !ok {
		t.Fatal("no view")
	}
	if !v.PeerGranted.Browse.Allows() {
		t.Errorf("view missing peer grant: %+v", v.PeerGranted)
	}
}
