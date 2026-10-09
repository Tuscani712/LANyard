package transfer

import (
	"errors"
	"net"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"

	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/peerapi"
)

// When the sender cancels a push, its best-effort call to the receiver's cancel
// route must end the receiver's row (Cancelled, "Cancelled by the sender")
// within that one request, not on a later stall timeout.
func TestSenderCancelPropagatesToReceiver(t *testing.T) {
	r := newPushProgressRig(t)

	var mu sync.Mutex
	var gotReason string
	var gotCalled bool
	r.inbox.SetOnCancel(func(peerFP string, files []inbox.ReceivedFile, started time.Time, reason string) {
		mu.Lock()
		gotReason, gotCalled = reason, true
		mu.Unlock()
	})

	r.mgr.SetBandwidthLimit(1) // slow enough that the push is still receiving
	data := randBytes(41, 3<<20)
	v := r.push(t, writeTemp(t, "slow.bin", data))

	waitFor(t, "the receiver to list the push", 10*time.Second, func() bool {
		return len(r.inbox.Incoming()) == 1
	})

	if err := r.mgr.Cancel(v.ID, false); err != nil {
		t.Fatalf("Cancel: %v", err)
	}

	// The receiver's row must be gone by the time Cancel returns: the cancel
	// request completed, so it did not wait for a stall timeout.
	if got := r.inbox.Incoming(); len(got) != 0 {
		t.Fatalf("receiver row survived the sender's cancel: %+v", got)
	}
	mu.Lock()
	called, reason := gotCalled, gotReason
	mu.Unlock()
	if !called {
		t.Fatal("receiver onCancel never fired")
	}
	if reason != "Cancelled by the sender" {
		t.Fatalf("receiver cancel reason = %q, want %q", reason, "Cancelled by the sender")
	}

	// The local job is Cancelled too and stops promptly.
	waitState(t, r.mgr, v.ID, StateCancelled, 5*time.Second)
}

// A peer that does not implement the cancel route answers 404. CancelPush must
// surface that as a StatusError so the sender's best-effort path can ignore it
// (and never fail the local cancel).
func TestCancelPushMissingRouteIsNotFound(t *testing.T) {
	srv := httptest.NewTLSServer(http.NewServeMux()) // no /cancel route -> 404
	defer srv.Close()
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	c := peerapi.NewClient(id)
	port := srv.Listener.Addr().(*net.TCPAddr).Port
	err = c.CancelPush(t.Context(), "127.0.0.1", port, "", "p_old")
	var se *peerapi.StatusError
	if !errors.As(err, &se) || se.Code != http.StatusNotFound {
		t.Fatalf("CancelPush on a route-less peer = %v, want a 404 StatusError", err)
	}
}
