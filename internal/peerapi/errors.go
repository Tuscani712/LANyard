package peerapi

import (
	"encoding/json"
	"errors"
	"net/http"
	"strings"
)

// NotPairedMessage is what the desktop shows when a peer has genuinely dropped
// the pairing. Only the exact "not paired" reason maps here: a permission
// refusal must never be presented as an unpairing (see permissionMessages).
const NotPairedMessage = "Not paired with this device."

// Permission-refusal wording. Each exact 403 reason the peer sends gets its own
// human line, so "push not permitted" never masquerades as "Not paired".
const (
	PushDeniedMessage   = "This device did not allow you to send files to it."
	BrowseDeniedMessage = "This device did not allow you to browse its shares."
	TextDeniedMessage   = "This device did not allow you to send text to it."
	NotPermittedMessage = "This device does not allow that action."
	UserDeniedMessage   = "The other device declined the request."
)

// TooLargeMessage is what the desktop shows when a peer answers 413. The peer's
// own body (a raw limit number) is not useful to a person, so the sender maps
// it to actionable wording.
const TooLargeMessage = "The other device can't accept a list this large; send fewer files at a time."

// notPairedMsg is the exact peer body that means "I no longer have you in my
// trust store". It is the only message that may trigger automatic local
// unpairing; see IsNotPaired. Permission refusals deliberately use other
// wording (see permissionMessages) so they can never be mistaken for this.
const notPairedMsg = "not paired"

// permissionMessages maps the exact 403 reasons a peer sends to the line a
// person should see. Every entry is a refusal, never an unpairing; only
// "not paired" (handled separately) may remove a local pairing.
var permissionMessages = map[string]string{
	"push not permitted": PushDeniedMessage,
	"pull not permitted": BrowseDeniedMessage,
	"text not permitted": TextDeniedMessage,
	"not permitted":      NotPermittedMessage,
	"denied by the user": UserDeniedMessage,
	"not paired":         NotPairedMessage,
	"forbidden":          NotPermittedMessage,
}

// reason extracts the human reason from a peer error body. The phone (and any
// other JSON peer) answers with {"error":"..."}; an older or plain-text peer
// sends the bare phrase. A JSON body whose error field is missing or the wrong
// type yields "" and is therefore never mistaken for a reason. Anything that is
// not a JSON object is returned trimmed, unchanged.
func reason(msg string) string {
	trimmed := strings.TrimSpace(msg)
	if strings.HasPrefix(trimmed, "{") {
		var env struct {
			Error string `json:"error"`
		}
		if err := json.Unmarshal([]byte(trimmed), &env); err == nil {
			return strings.TrimSpace(env.Error)
		}
	}
	return trimmed
}

// IsNotPaired reports whether err is a 403 that the peer sent over a
// certificate-pinned connection and whose body means "not paired". Both wire
// forms are accepted: the plain phrase ("not paired") and the JSON error body a
// phone sends ({"error":"not paired"}). Only that exact reason means the peer
// has dropped us: permission denials ("pull not permitted", "push not
// permitted", "not permitted", "forbidden"), an empty body, and any other
// message must never remove a good local pairing. The comparison is case- and
// surrounding-whitespace-insensitive; a phrase that merely contains "paired"
// (for example "not paired yet" or "unpaired") does not match. The StatusError
// must also carry Pinned (the peer's certificate was verified against the
// expected fingerprint), so a bare TLS answer cannot trigger data loss.
func IsNotPaired(err error) bool {
	var se *StatusError
	if !errors.As(err, &se) || se.Code != http.StatusForbidden || !se.Pinned {
		return false
	}
	return strings.EqualFold(reason(se.Msg), notPairedMsg)
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
			msg := reason(se.Msg)
			if m, ok := permissionMessages[strings.ToLower(msg)]; ok {
				return m
			}
			if msg != "" {
				return msg
			}
			// A bare 403 with no reason: still a refusal, never an unpairing.
			return NotPermittedMessage
		}
		if se.Code == http.StatusRequestEntityTooLarge {
			return TooLargeMessage
		}
		if se.Msg != "" {
			return se.Msg
		}
	}
	return err.Error()
}
