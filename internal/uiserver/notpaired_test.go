package uiserver

import (
	"errors"
	"net/http"
	"strings"
	"testing"

	"lanyard/internal/peerapi"
)

// A permission 403 from a peer gets its own wording, never the destructive
// "not paired" line (which is reserved for a genuine unpairing) and never
// "could not reach device", which is reserved for transport failures.
func TestPeerErrorMessageNotPaired(t *testing.T) {
	forbidden := &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "not permitted"}

	if got := peerErrorMessage(forbidden); got != peerapi.NotPermittedMessage {
		t.Errorf("peerErrorMessage(403) = %q, want %q", got, peerapi.NotPermittedMessage)
	}
	if got := peerUIMessage(forbidden); got != peerapi.NotPermittedMessage {
		t.Errorf("peerUIMessage(403) = %q, want %q", got, peerapi.NotPermittedMessage)
	}

	// The genuine unpairing reason still reads as "Not paired".
	unpaired := &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "not paired"}
	if got := peerErrorMessage(unpaired); got != peerapi.NotPairedMessage {
		t.Errorf("peerErrorMessage(not paired) = %q, want %q", got, peerapi.NotPairedMessage)
	}

	// A peer that explains itself keeps its own message.
	declined := &peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "The other device declined the transfer."}
	if got := peerErrorMessage(declined); got != declined.Msg {
		t.Errorf("peerErrorMessage(declined) = %q, want the peer's own message %q", got, declined.Msg)
	}

	// A transport failure is still reported as unreachable.
	dial := errors.New("dial tcp 10.0.0.5:47800: connect: connection refused")
	if got := peerErrorMessage(dial); !strings.Contains(got, "could not reach device") {
		t.Errorf("peerErrorMessage(dial) = %q, want the unreachable wording", got)
	}
	if got := peerErrorMessage(dial); strings.Contains(got, "Not paired") {
		t.Errorf("peerErrorMessage(dial) = %q, must not claim not-paired", got)
	}
}

// The pairing flow shows the same distinction: a permission 403 keeps its own
// wording while a dial error is not friendly.
func TestPairingStartMessageForbidden(t *testing.T) {
	msg, friendly := pairingStartMessage(&peerapi.StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "push not permitted"})
	if !friendly {
		t.Errorf("403 should be the friendly case, got friendly=%v", friendly)
	}
	if msg != peerapi.PushDeniedMessage {
		t.Errorf("403 pairing message = %q, want %q", msg, peerapi.PushDeniedMessage)
	}
	if strings.Contains(msg, "Not paired") {
		t.Errorf("a permission refusal must not read as not paired: %q", msg)
	}
	if strings.Contains(msg, "could not reach") {
		t.Errorf("403 must not be reported as unreachable: %q", msg)
	}
}
