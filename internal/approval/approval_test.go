package approval

import (
	"context"
	"errors"
	"testing"
	"time"
)

func ask(m *Manager, fp, key string) chan result {
	ch := make(chan result, 1)
	go func() {
		ok, err := m.Ask(context.Background(), Request{PeerFP: fp, PeerName: "Bob", Reason: "connect", Count: 1, Total: 10,
			Files: []File{{Path: "a.txt", Size: 10}}}, key)
		ch <- result{ok, err}
	}()
	return ch
}

type result struct {
	ok  bool
	err error
}

func waitPending(t *testing.T, m *Manager, n int) []Request {
	t.Helper()
	deadline := time.Now().Add(2 * time.Second)
	for {
		if p := m.Pending(); len(p) == n {
			return p
		}
		if time.Now().After(deadline) {
			t.Fatalf("expected %d pending request(s), have %d", n, len(m.Pending()))
		}
		time.Sleep(5 * time.Millisecond)
	}
}

func TestAcceptAndReject(t *testing.T) {
	m := New(nil)
	r1 := ask(m, "fp1", "")
	p := waitPending(t, m, 1)
	if p[0].PeerName != "Bob" || p[0].ID == "" || p[0].ExpiresAt.Before(p[0].CreatedAt) {
		t.Fatalf("unexpected request %+v", p[0])
	}
	if !m.Decide(p[0].ID, true) {
		t.Fatal("Decide should find the request")
	}
	if got := <-r1; !got.ok || got.err != nil {
		t.Fatalf("accept: %+v", got)
	}
	waitPending(t, m, 0)

	r2 := ask(m, "fp1", "")
	p = waitPending(t, m, 1)
	m.Decide(p[0].ID, false)
	if got := <-r2; got.ok || got.err != nil {
		t.Fatalf("reject: %+v", got)
	}
	if m.Decide("a_missing", true) {
		t.Error("deciding an unknown request must report false")
	}
}

func TestTimeoutAndCancel(t *testing.T) {
	m := New(nil)
	m.SetTimeout(40 * time.Millisecond)
	if ok, err := m.Ask(context.Background(), Request{PeerFP: "x"}, ""); ok || !errors.Is(err, ErrTimeout) {
		t.Errorf("want timeout, got ok=%v err=%v", ok, err)
	}
	waitPending(t, m, 0)

	m.SetTimeout(time.Minute)
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan error, 1)
	go func() { _, err := m.Ask(ctx, Request{PeerFP: "x"}, ""); done <- err }()
	waitPending(t, m, 1)
	cancel() // the sender hung up
	if err := <-done; !errors.Is(err, context.Canceled) {
		t.Errorf("want context.Canceled, got %v", err)
	}
	waitPending(t, m, 0)
}

// A resumed push of the same files must not prompt a second time.
func TestAcceptedTransferIsRemembered(t *testing.T) {
	m := New(nil)
	r := ask(m, "fp1", "same-files")
	p := waitPending(t, m, 1)
	m.Decide(p[0].ID, true)
	<-r
	again := ask(m, "fp1", "same-files")
	select {
	case got := <-again:
		if !got.ok {
			t.Fatal("remembered acceptance should answer yes")
		}
	case <-time.After(time.Second):
		t.Fatal("a remembered transfer must not wait for a person")
	}
	if len(m.Pending()) != 0 {
		t.Error("nothing should be pending")
	}
	// A rejection is never remembered.
	r2 := ask(m, "fp1", "other-files")
	p = waitPending(t, m, 1)
	m.Decide(p[0].ID, false)
	<-r2
	r3 := ask(m, "fp1", "other-files")
	waitPending(t, m, 1)
	m.Decide(m.Pending()[0].ID, false)
	<-r3
}

func TestPromptFloodingIsCapped(t *testing.T) {
	m := New(nil)
	for i := 0; i < maxPerPeer; i++ {
		ask(m, "spammer", "")
	}
	waitPending(t, m, maxPerPeer)
	if _, err := m.Ask(context.Background(), Request{PeerFP: "spammer"}, ""); !errors.Is(err, ErrTooMany) {
		t.Errorf("a peer must not stack unlimited prompts, got %v", err)
	}
	// Another peer is unaffected.
	other := ask(m, "someone-else", "")
	waitPending(t, m, maxPerPeer+1)
	m.Decide(m.Pending()[maxPerPeer].ID, true)
	<-other
}

func TestFileListIsBounded(t *testing.T) {
	m := New(nil)
	files := make([]File, 500)
	for i := range files {
		files[i] = File{Path: "f", Size: 1}
	}
	go m.Ask(context.Background(), Request{PeerFP: "p", Files: files, Count: 500}, "")
	p := waitPending(t, m, 1)
	if len(p[0].Files) != maxFilesShown || p[0].Count != 500 {
		t.Errorf("shown %d files / count %d, want %d / 500", len(p[0].Files), p[0].Count, maxFilesShown)
	}
	m.Decide(p[0].ID, false)
}
