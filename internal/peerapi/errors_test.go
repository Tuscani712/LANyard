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
		{"json error form", pinned(`{"error":"not paired"}`), true},
		{"json error form mixed case", pinned(`{"error":"Not Paired"}`), true},
		{"json error form whitespace", pinned("  {\"error\": \"not paired\"}  "), true},
		{"json pull not permitted", pinned(`{"error":"pull not permitted"}`), false},
		{"json push not permitted", pinned(`{"error":"push not permitted"}`), false},
		{"json not permitted", pinned(`{"error":"not permitted"}`), false},
		{"json text not permitted", pinned(`{"error":"text not permitted"}`), false},
		{"json denied by the user", pinned(`{"error":"denied by the user"}`), false},
		{"json forbidden", pinned(`{"error":"forbidden"}`), false},
		{"json empty error", pinned(`{"error":""}`), false},
		{"json no error field", pinned(`{"ok":true}`), false},
		{"json other field only", pinned(`{"reason":"not paired"}`), false},
		{"json null error", pinned(`{"error":null}`), false},
		{"json not paired yet", pinned(`{"error":"not paired yet"}`), false},
		{"malformed json", pinned(`{"error":"not paired"`), false},
		{"pull not permitted", pinned("pull not permitted"), false},
		{"push not permitted", pinned("push not permitted"), false},
		{"not permitted", pinned("not permitted"), false},
		{"text not permitted", pinned("text not permitted"), false},
		{"denied by the user", pinned("denied by the user"), false},
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

// A permission refusal must get its own wording, never the destructive
// "Not paired" line; only a genuinely missing pairing reads as NotPaired.
func TestUserMessageDisplaySet(t *testing.T) {
	cases := map[string]string{
		"not paired":         NotPairedMessage,
		"push not permitted": PushDeniedMessage,
		"pull not permitted": BrowseDeniedMessage,
		"text not permitted": TextDeniedMessage,
		"not permitted":      NotPermittedMessage,
		"denied by the user": UserDeniedMessage,
		"forbidden":          NotPermittedMessage,
		"":                   NotPermittedMessage,
	}
	for msg, want := range cases {
		if got := UserMessage(&StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: msg}); got != want {
			t.Errorf("UserMessage(%q) = %q, want %q", msg, got, want)
		}
	}
	specific := "The other device declined the transfer."
	if got := UserMessage(&StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: specific}); got != specific {
		t.Errorf("UserMessage(specific 403) = %q, want %q", got, specific)
	}
}

// A JSON error body is unwrapped before the mapping is consulted, so a generic
// {"error":"not permitted"} still reads as a permission refusal, never "Not
// paired", while a specific JSON reason is preserved (never shown as raw JSON).
func TestUserMessageJSONErrorBody(t *testing.T) {
	cases := map[string]string{
		`{"error":"not paired"}`:         NotPairedMessage,
		`{"error":"not permitted"}`:      NotPermittedMessage,
		`{"error":"push not permitted"}`: PushDeniedMessage,
		`{"error":"denied by the user"}`: UserDeniedMessage,
		`{"error":"forbidden"}`:          NotPermittedMessage,
		`{"error":""}`:                   NotPermittedMessage,
	}
	for msg, want := range cases {
		if got := UserMessage(&StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: msg}); got != want {
			t.Errorf("UserMessage(%s) = %q, want %q", msg, got, want)
		}
	}
	specific := `{"error":"The other device declined the transfer."}`
	if got := UserMessage(&StatusError{Code: http.StatusForbidden, Status: "403 Forbidden", Msg: specific}); got != "The other device declined the transfer." {
		t.Errorf("UserMessage(specific JSON 403) = %q, want the unwrapped reason", got)
	}
}

// A 413 from a peer must read as actionable wording, never the raw HTTP status
// or the peer's internal limit number.
func TestUserMessageTooLarge(t *testing.T) {
	for _, msg := range []string{
		"",
		"too many files in one push (limit 500000)",
		`{"error":"the list of files is too large for this device"}`,
	} {
		if got := UserMessage(&StatusError{Code: http.StatusRequestEntityTooLarge, Status: "413 Request Entity Too Large", Msg: msg}); got != TooLargeMessage {
			t.Errorf("UserMessage(413 %q) = %q, want %q", msg, got, TooLargeMessage)
		}
	}
}
