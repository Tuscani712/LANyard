package peerapi

import (
	"net/http"
	"testing"
)

// pinned builds a 403 the way the client does after verifying the peer's
// certificate against the expected fingerprint.
func pinned(msg string) *StatusError {
	return &StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: msg, Pinned: true}
}

// IsNotPaired is true only for a certificate-pinned 403 whose body is exactly
// "not paired" (case-insensitive). Permission denials, empty or odd bodies and
// anything merely containing "paired" must not match.
func TestIsNotPairedStrict(t *testing.T) {
	cases := []struct {
		name string
		err  error
		want bool
	}{
		{"exact not paired", pinned("not paired"), true},
		{"mixed case", pinned("Not Paired"), true},
		{"upper case", pinned("NOT PAIRED"), true},
		{"surrounding whitespace", pinned("  not paired\n"), true},
		{"pull not permitted", pinned("pull not permitted"), false},
		{"push not permitted", pinned("push not permitted"), false},
		{"not permitted", pinned("not permitted"), false},
		{"forbidden", pinned("forbidden"), false},
		{"empty body", pinned(""), false},
		{"odd body", pinned("<html>go away</html>"), false},
		{"declined the transfer", pinned("The other device declined the transfer."), false},
		{"phrase containing paired", pinned("peer is not paired yet"), false},
		{"unpaired word", pinned("unpaired"), false},
		{"unpinned not paired", &StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: "not paired"}, false},
		{"not a 403", &StatusError{Code: http.StatusNotFound, Status: "404 Not Found", Msg: "not paired", Pinned: true}, false},
		{"not a status error", nil, false},
	}
	for _, c := range cases {
		if got := IsNotPaired(c.err); got != c.want {
			t.Errorf("%s: IsNotPaired = %v, want %v", c.name, got, c.want)
		}
	}
}

// The wider display set still maps generic permission wording to the friendly
// "not paired" line, while a specific explanation is preserved.
func TestUserMessageDisplaySet(t *testing.T) {
	for _, msg := range []string{"not paired", "not permitted", "push not permitted", "forbidden", ""} {
		if got := UserMessage(&StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: msg}); got != NotPairedMessage {
			t.Errorf("UserMessage(%q) = %q, want %q", msg, got, NotPairedMessage)
		}
	}
	specific := "The other device declined the transfer."
	if got := UserMessage(&StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: specific}); got != specific {
		t.Errorf("UserMessage(specific 403) = %q, want %q", got, specific)
	}
}
