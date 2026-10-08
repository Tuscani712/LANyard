package peerapi

import (
	"errors"
	"net/http"
	"strings"
)

// NotPairedMessage is what the desktop shows when a peer refuses an action
// with 403 and no more specific reason is available. A 403 is a pairing or
// permission refusal, not an unreachable device, so it must never be presented
// as "could not reach device".
const NotPairedMessage = "Not paired with this device."

// notPairedMsg is the exact peer body that means "I no longer have you in my
// trust store". It is the only message that may trigger automatic local
// unpairing; see IsNotPaired. Permission refusals deliberately use other
// wording (see the 403 messages in session.go, push.go and shares.go) so they
// can never be mistaken for this.
const notPairedMsg = "not paired"

// genericForbidden lists server messages that only mean "you may not do this",
// which the UI replaces with NotPairedMessage. A peer that sends its own
// explanation (for example "The other device declined the transfer.") keeps it.
// This set drives display wording only; it must never drive the destructive
// unpair decision (see IsNotPaired).
var genericForbidden = map[string]bool{
	"not permitted":      true,
	"push not permitted": true,
	"not paired":         true,
	"forbidden":          true,
}

// IsNotPaired reports whether err is a 403 that the peer sent over a
// certificate-pinned connection and whose body is exactly "not paired". Only
// that exact phrase means the peer has dropped us: permission denials
// ("pull not permitted", "push not permitted", "not permitted", "forbidden"),
// an empty body, and any other message must never remove a good local pairing.
// The comparison is case- and surrounding-whitespace-insensitive; a phrase that
// merely contains "paired" (for example "not paired yet" or "unpaired") does
// not match. The StatusError must also carry Pinned (the peer's certificate was
// verified against the expected fingerprint), so a bare TLS answer cannot
// trigger data loss.
func IsNotPaired(err error) bool {
	var se *StatusError
	if !errors.As(err, &se) || se.Code != http.StatusForbidden || !se.Pinned {
		return false
	}
	return strings.EqualFold(strings.TrimSpace(se.Msg), notPairedMsg)
}

// UserMessage maps a peer client error to the line a person should see. It
// distinguishes a 403 refusal (pairing/permission) from a transport failure so
// the two are not conflated. Everything that is not a StatusError is passed
// through unchanged.
func UserMessage(err error) string {
	if err == nil {
		return ""
	}
	var se *StatusError
	if errors.As(err, &se) {
		if se.Code == http.StatusForbidden {
			if se.Msg != "" && !genericForbidden[se.Msg] {
				return se.Msg
			}
			return NotPairedMessage
		}
		if se.Msg != "" {
			return se.Msg
		}
	}
	return err.Error()
}
