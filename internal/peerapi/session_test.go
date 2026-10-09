package peerapi

import (
	"net/http"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/trust"
)

func newInviteServer(t *testing.T) (*Server, *trust.Store) {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	return &Server{trust: tr}, tr
}

func TestAcceptPairInviteValid(t *testing.T) {
	s, tr := newInviteServer(t)
	tok, _ := tr.MintPairInvite()
	viaQR, code := s.acceptPairInvite(trust.ModePair, "peer", tok)
	if code != 0 || !viaQR {
		t.Fatalf("viaQR=%v code=%d, want viaQR=true code=0", viaQR, code)
	}
}

// A bad invite must be a hard reject; it must never silently fall back to the
// SAS path (that would let an attacker downgrade by sending a bogus invite).
func TestAcceptPairInviteBadDoesNotFallBackToSAS(t *testing.T) {
	s, _ := newInviteServer(t)
	viaQR, code := s.acceptPairInvite(trust.ModePair, "peer", "not-a-real-invite")
	if viaQR {
		t.Fatal("a bad invite must not be accepted")
	}
	if code != http.StatusForbidden {
		t.Fatalf("code = %d, want %d", code, http.StatusForbidden)
	}
}

// An empty invite means the caller is on the normal SAS path.
func TestAcceptPairInviteEmptyUsesSAS(t *testing.T) {
	s, _ := newInviteServer(t)
	viaQR, code := s.acceptPairInvite(trust.ModePair, "peer", "")
	if viaQR || code != 0 {
		t.Fatalf("empty invite should mean the SAS path, got viaQR=%v code=%d", viaQR, code)
	}
}

func TestAcceptPairInviteWrongMode(t *testing.T) {
	s, tr := newInviteServer(t)
	tok, _ := tr.MintPairInvite()
	if _, code := s.acceptPairInvite(trust.ModeConnect, "peer", tok); code != http.StatusBadRequest {
		t.Fatalf("code = %d, want %d", code, http.StatusBadRequest)
	}
}

func TestAcceptPairInviteRateLimited(t *testing.T) {
	s, _ := newInviteServer(t)
	for i := 0; i < 10; i++ {
		s.acceptPairInvite(trust.ModePair, "peer", "bad")
	}
	if _, code := s.acceptPairInvite(trust.ModePair, "peer", "bad"); code != http.StatusTooManyRequests {
		t.Fatalf("code = %d, want %d", code, http.StatusTooManyRequests)
	}
}
