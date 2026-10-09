package peerapi

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"lanyard/internal/approval"
	"lanyard/internal/config"
	"lanyard/internal/inbox"
	"lanyard/internal/shares"
	"lanyard/internal/trust"
	"lanyard/internal/xferlog"
)

// permTestServer wires a Server whose authorizer reports the given access, with
// a real approval queue and shares manager so the Ask prompts can be exercised.
func permTestServer(t *testing.T, a trust.Access) (*Server, *approval.Manager) {
	t.Helper()
	ib := inbox.New(t.TempDir(), nil)
	t.Cleanup(func() { ib.Close() })
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	sh := shares.New(cfg, "self", nil)
	m := approval.New(nil)
	m.SetTimeout(5 * time.Second)
	s := &Server{inbox: ib, shares: sh, approvals: m, auth: fakeAuth{a}}
	return s, m
}

// prompted runs fn in a goroutine, waits for its approval prompt, answers it
// with accept, and returns the handler's response.
func prompted(t *testing.T, m *approval.Manager, accept bool, fn func(*httptest.ResponseRecorder)) *httptest.ResponseRecorder {
	t.Helper()
	rr := httptest.NewRecorder()
	done := make(chan struct{})
	go func() { fn(rr); close(done) }()
	deadline := time.Now().Add(5 * time.Second)
	for {
		if pend := m.Pending(); len(pend) > 0 {
			m.Decide(pend[0].ID, accept)
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("no approval prompt appeared")
		}
		time.Sleep(5 * time.Millisecond)
	}
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		t.Fatal("handler did not return after the decision")
	}
	return rr
}

func offerReq(fp, rel string) *http.Request {
	body := `{"files":[{"rel_path":"` + rel + `","size":5}]}`
	return withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/push/offer", strings.NewReader(body)), fp)
}

// A paired peer whose push permission is Ask is prompted at the offer: an
// accept lets the push proceed, a decline is refused with the exact "denied by
// the user" body (never "not paired"). Never refuses outright with no prompt.
func TestPushAskPromptsAtOffer(t *testing.T) {
	s, m := permTestServer(t, trust.Access{Paired: true, Push: trust.Ask})
	rr := prompted(t, m, true, func(w *httptest.ResponseRecorder) {
		s.handlePushOffer(w, offerReq("peer-a", "a.txt"))
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("accepted Ask offer = %d body %q, want 200", rr.Code, rr.Body.String())
	}

	// A different file set is a different transfer, so it asks again.
	rr = prompted(t, m, false, func(w *httptest.ResponseRecorder) {
		s.handlePushOffer(w, offerReq("peer-a", "b.txt"))
	})
	if rr.Code != http.StatusForbidden || strings.TrimSpace(rr.Body.String()) != permissionReasonUserDenied {
		t.Fatalf("declined Ask offer = %d body %q, want 403 %q", rr.Code, rr.Body.String(), permissionReasonUserDenied)
	}

	// Never: a hard refusal, worded as a permission problem.
	s2, _ := permTestServer(t, trust.Access{Paired: true, Push: trust.Never})
	rr = httptest.NewRecorder()
	s2.handlePushOffer(rr, offerReq("peer-a", "a.txt"))
	if rr.Code != http.StatusForbidden || strings.TrimSpace(rr.Body.String()) != "push not permitted" {
		t.Fatalf("Never offer = %d body %q, want 403 push not permitted", rr.Code, rr.Body.String())
	}
}

// A paired peer whose browse permission is Ask is prompted once per browse
// session: the first request blocks on a person, the acceptance is remembered,
// and the next request (same session) returns without a prompt.
func TestBrowseAskPromptsOncePerSession(t *testing.T) {
	s, m := permTestServer(t, trust.Access{Paired: true, Browse: trust.Ask})
	asked := func() bool { return len(m.Pending()) > 0 }

	rr := prompted(t, m, true, func(w *httptest.ResponseRecorder) {
		s.handleShareList(w, withPeerFP(httptest.NewRequest(http.MethodGet, "/api/v1/shares", nil), "peer-a"))
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("accepted browse = %d body %q", rr.Code, rr.Body.String())
	}

	// Second request in the same session: no prompt, immediate 200.
	rr2 := httptest.NewRecorder()
	s.handleShareList(rr2, withPeerFP(httptest.NewRequest(http.MethodGet, "/api/v1/shares", nil), "peer-a"))
	if rr2.Code != http.StatusOK {
		t.Fatalf("second browse = %d body %q", rr2.Code, rr2.Body.String())
	}
	if asked() {
		t.Fatal("a second browse request re-prompted within the session")
	}

	// Declining a fresh peer's prompt refuses the pull with the user-denied body.
	s2, m2 := permTestServer(t, trust.Access{Paired: true, Browse: trust.Ask})
	rr3 := prompted(t, m2, false, func(w *httptest.ResponseRecorder) {
		s2.handleShareList(w, withPeerFP(httptest.NewRequest(http.MethodGet, "/api/v1/shares", nil), "peer-b"))
	})
	if rr3.Code != http.StatusForbidden || strings.TrimSpace(rr3.Body.String()) != permissionReasonUserDenied {
		t.Fatalf("declined browse = %d body %q, want 403 %q", rr3.Code, rr3.Body.String(), permissionReasonUserDenied)
	}
}

// A browse Ask that nobody answers times out as a denial, so a silent house
// never leaks a share listing.
func TestBrowseAskTimeoutDenies(t *testing.T) {
	s, m := permTestServer(t, trust.Access{Paired: true, Browse: trust.Ask})
	m.SetTimeout(50 * time.Millisecond)
	rr := httptest.NewRecorder()
	s.handleShareList(rr, withPeerFP(httptest.NewRequest(http.MethodGet, "/api/v1/shares", nil), "peer-a"))
	if rr.Code != http.StatusForbidden {
		t.Fatalf("timed-out browse = %d body %q, want 403", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), "did not answer") {
		t.Fatalf("timed-out browse body = %q, want the no-answer wording", rr.Body.String())
	}
}

// A text refusal is logged with its permission reason. The diagnostics must
// never record "not paired with this device" for a permission refusal: that
// wording would wrongly suggest the pairing is gone.
func TestSnippetRefusalLogWording(t *testing.T) {
	ib := inbox.New(t.TempDir(), nil)
	t.Cleanup(func() { ib.Close() })
	rec := xferlog.New(50)
	s := &Server{
		inbox: ib,
		auth:  fakeAuth{trust.Access{Paired: true, Text: trust.Never}},
		log:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		xlog:  rec,
	}
	rr := httptest.NewRecorder()
	s.handleSnippet(rr, withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/snippet", strings.NewReader(`{"text":"hi"}`)), "peer-a"))
	if rr.Code != http.StatusForbidden {
		t.Fatalf("refused snippet = %d body %q, want 403", rr.Code, rr.Body.String())
	}
	entries := rec.Entries()
	if len(entries) == 0 {
		t.Fatal("a refused snippet was not logged")
	}
	for _, e := range entries {
		if strings.Contains(e.Error, "not paired with this device") {
			t.Errorf("refusal log error = %q, must not claim not-paired", e.Error)
		}
	}
	found := false
	for _, e := range entries {
		if strings.Contains(e.Error, "not permitted") {
			found = true
		}
	}
	if !found {
		t.Errorf("refusal log entries = %+v, want a 'not permitted' reason", entries)
	}
}

// A paired peer whose text permission is Ask reuses the approval prompt;
// declining answers the exact user-denied body.
func TestTextAskPrompts(t *testing.T) {
	s, m := permTestServer(t, trust.Access{Paired: true, Text: trust.Ask})
	rr := prompted(t, m, true, func(w *httptest.ResponseRecorder) {
		s.handleSnippet(w, withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/snippet", strings.NewReader(`{"text":"hi"}`)), "peer-a"))
	})
	if rr.Code != http.StatusOK {
		t.Fatalf("accepted text = %d body %q", rr.Code, rr.Body.String())
	}

	rr = prompted(t, m, false, func(w *httptest.ResponseRecorder) {
		s.handleSnippet(w, withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/snippet", strings.NewReader(`{"text":"hi"}`)), "peer-a"))
	})
	if rr.Code != http.StatusForbidden || strings.TrimSpace(rr.Body.String()) != permissionReasonUserDenied {
		t.Fatalf("declined text = %d body %q, want 403 %q", rr.Code, rr.Body.String(), permissionReasonUserDenied)
	}

	// Never refuses, worded as a text-specific permission problem.
	s2, _ := permTestServer(t, trust.Access{Paired: true, Text: trust.Never})
	rr = httptest.NewRecorder()
	s2.handleSnippet(rr, withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/snippet", strings.NewReader(`{"text":"hi"}`)), "peer-a"))
	if rr.Code != http.StatusForbidden || strings.TrimSpace(rr.Body.String()) != "text not permitted" {
		t.Fatalf("Never text = %d body %q, want 403 text not permitted", rr.Code, rr.Body.String())
	}
}

// A paired Ask push must fail closed when there is no approval queue to ask a
// person: it is refused with the user-denied reason rather than accepted
// silently. A Connect session and an over-limit push keep their old automatic
// (accept) behaviour when the queue is unavailable.
func TestPushAskWithoutApprovalsFailsClosed(t *testing.T) {
	newSrv := func(a trust.Access) *Server {
		ib := inbox.New(t.TempDir(), nil)
		t.Cleanup(func() { ib.Close() })
		return &Server{inbox: ib, auth: fakeAuth{a}}
	}

	s := newSrv(trust.Access{Paired: true, Push: trust.Ask})
	rr := httptest.NewRecorder()
	s.handlePushOffer(rr, offerReq("peer-a", "a.txt"))
	if rr.Code != http.StatusForbidden || strings.TrimSpace(rr.Body.String()) != permissionReasonUserDenied {
		t.Fatalf("paired Ask without approvals = %d body %q, want 403 %q", rr.Code, rr.Body.String(), permissionReasonUserDenied)
	}

	sc := newSrv(trust.Access{SessionID: "sess-connect"})
	rr = httptest.NewRecorder()
	sc.handlePushOffer(rr, offerReq("stranger", "a.txt"))
	if rr.Code != http.StatusOK {
		t.Fatalf("connect without approvals = %d body %q, want 200 (unchanged)", rr.Code, rr.Body.String())
	}

	sl := newSrv(trust.Access{Paired: true, Push: trust.Allow, AskOver: 1})
	rr = httptest.NewRecorder()
	sl.handlePushOffer(rr, offerReq("peer-b", "a.txt"))
	if rr.Code != http.StatusOK {
		t.Fatalf("large push without approvals = %d body %q, want 200 (unchanged)", rr.Code, rr.Body.String())
	}
}
