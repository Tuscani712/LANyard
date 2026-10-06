package peerapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/inbox"
	"lanyard/internal/trust"
)

// fakeAuth lets a test pick the trust decision for a caller.
type fakeAuth struct{ a trust.Access }

func (f fakeAuth) Access(string) trust.Access { return f.a }

func snippetRequest(t *testing.T, srv *Server, body string) *httptest.ResponseRecorder {
	t.Helper()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/snippet", strings.NewReader(body))
	req = req.WithContext(context.WithValue(req.Context(), peerIDKey, "peer-fp"))
	rr := httptest.NewRecorder()
	srv.handleSnippet(rr, req)
	return rr
}

// A snippet uses the same authorization as a push: unpaired or no-push peers
// are refused.
func TestSnippetPermissionDenied(t *testing.T) {
	m := inbox.New(t.TempDir(), nil)
	cases := []struct {
		name string
		a    trust.Access
		want int
	}{
		{"unpaired", trust.Access{}, http.StatusForbidden},
		{"paired without push", trust.Access{Paired: true}, http.StatusForbidden},
		{"paired with push", trust.Access{Paired: true, Push: true}, 0},
		{"live connect session", trust.Access{SessionID: "c_1"}, 0},
	}
	for _, tc := range cases {
		srv := &Server{inbox: m, auth: fakeAuth{tc.a}}
		rr := snippetRequest(t, srv, `{"text":"hello"}`)
		if tc.want == 0 {
			if rr.Code != http.StatusOK {
				t.Errorf("%s: code = %d, want 200", tc.name, rr.Code)
			}
			continue
		}
		if rr.Code != tc.want {
			t.Errorf("%s: code = %d, want %d", tc.name, rr.Code, tc.want)
		}
	}
	if len(m.Snippets()) != 2 {
		t.Fatalf("only the two allowed snippets should be stored, got %d", len(m.Snippets()))
	}
}

func TestSnippetRejectsTooLarge(t *testing.T) {
	m := inbox.New(t.TempDir(), nil)
	srv := &Server{inbox: m, auth: fakeAuth{trust.Access{Paired: true, Push: true}}}
	big := strings.Repeat("x", inbox.MaxSnippetBytes+1)
	rr := snippetRequest(t, srv, `{"text":"`+big+`"}`)
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("code = %d, want 400", rr.Code)
	}
	if len(m.Snippets()) != 0 {
		t.Fatal("an oversized snippet must not be stored")
	}
}

func TestSnippetRejectsControlCharacters(t *testing.T) {
	m := inbox.New(t.TempDir(), nil)
	srv := &Server{inbox: m, auth: fakeAuth{trust.Access{Paired: true, Push: true}}}
	rr := snippetRequest(t, srv, "{\"text\":\"bad\\u0007bell\"}")
	if rr.Code != http.StatusBadRequest {
		t.Fatalf("code = %d, want 400", rr.Code)
	}
}

// Markup is stored verbatim and never interpreted server-side; the UI renders
// it with textContent.
func TestSnippetMarkupStoredAsText(t *testing.T) {
	m := inbox.New(t.TempDir(), nil)
	srv := &Server{inbox: m, auth: fakeAuth{trust.Access{Paired: true, Push: true}}}
	rr := snippetRequest(t, srv, `{"text":"<script>alert(1)</script>"}`)
	if rr.Code != http.StatusOK {
		t.Fatalf("code = %d, want 200", rr.Code)
	}
	list := m.Snippets()
	if len(list) != 1 || list[0].Text != "<script>alert(1)</script>" {
		t.Fatalf("stored %+v, want the raw text", list)
	}
}
