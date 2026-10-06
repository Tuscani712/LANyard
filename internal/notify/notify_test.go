package notify

import (
	"errors"
	"strings"
	"sync"
	"testing"
)

// fakeNotifier records what it was asked to send.
type fakeNotifier struct {
	mu      sync.Mutex
	notices []Notice
	err     error
}

func (f *fakeNotifier) Notify(n Notice) error {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.notices = append(f.notices, n)
	return f.err
}

func (f *fakeNotifier) sent() []Notice {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]Notice(nil), f.notices...)
}

func TestClientDelegatesWhenEnabled(t *testing.T) {
	f := &fakeNotifier{}
	c := &Client{n: f, enabled: func() bool { return true }}
	if err := c.Notify(Notice{Title: "LANyard", Body: "Sent 2 files to Bob"}); err != nil {
		t.Fatalf("Notify: %v", err)
	}
	got := f.sent()
	if len(got) != 1 || got[0].Title != "LANyard" || got[0].Body != "Sent 2 files to Bob" {
		t.Fatalf("got %+v", got)
	}
}

func TestClientSuppressedWhenDisabled(t *testing.T) {
	f := &fakeNotifier{}
	c := &Client{n: f, enabled: func() bool { return false }}
	if err := c.Notify(Notice{Title: "LANyard", Body: "should not appear"}); err != nil {
		t.Fatalf("Notify: %v", err)
	}
	if got := f.sent(); len(got) != 0 {
		t.Fatalf("disabled client delivered %+v", got)
	}
}

func TestClientWithNilEnabledAlwaysSends(t *testing.T) {
	f := &fakeNotifier{}
	c := &Client{n: f}
	if err := c.Notify(Notice{Title: "LANyard"}); err != nil {
		t.Fatalf("Notify: %v", err)
	}
	if got := f.sent(); len(got) != 1 {
		t.Fatalf("expected one notice, got %+v", got)
	}
}

func TestClientPropagatesPlatformError(t *testing.T) {
	want := errors.New("no notification service")
	f := &fakeNotifier{err: want}
	c := &Client{n: f, enabled: func() bool { return true }}
	if err := c.Notify(Notice{Title: "LANyard"}); !errors.Is(err, want) {
		t.Fatalf("err = %v, want %v", err, want)
	}
}

func TestNilClientIsInert(t *testing.T) {
	var c *Client
	if err := c.Notify(Notice{Title: "LANyard"}); err != nil {
		t.Fatalf("nil client: %v", err)
	}
}

// newNotifier must be usable on every platform; on a headless test host the
// platform call may fail, which the caller is expected to tolerate.
func TestNewWiresPlatformNotifier(t *testing.T) {
	c := New(nil)
	if c == nil || c.n == nil {
		t.Fatal("New returned an unusable client")
	}
	_ = c.Notify(Notice{Title: "LANyard", Body: "test"})
}

func TestClientEscapesMarkup(t *testing.T) {
	f := &fakeNotifier{}
	c := &Client{n: f}
	_ = c.Notify(Notice{Title: "T", Body: "<a href=\"x\">evil</a>\n" + strings.Repeat("x", 300)})
	got := f.sent()[0]
	if strings.Contains(got.Body, "<a") || strings.Contains(got.Body, "\n") || len([]rune(got.Body)) > 260 {
		t.Fatalf("not sanitized: %q", got.Body)
	}
}
