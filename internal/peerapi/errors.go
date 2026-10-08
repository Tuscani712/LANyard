package peerapi

import (
	"errors"
	"net/http"
)

// NotPairedMessage is what the desktop shows when a peer refuses an action
// with 403 and no more specific reason is available. A 403 is a pairing or
// permission refusal, not an unreachable device, so it must never be presented
// as "could not reach device".
const NotPairedMessage = "Not paired with this device."

// genericForbidden lists server messages that only mean "you may not do this",
// which the UI replaces with NotPairedMessage. A peer that sends its own
// explanation (for example "The other device declined the transfer.") keeps it.
var genericForbidden = map[string]bool{
	"not permitted":      true,
	"push not permitted": true,
	"not paired":         true,
	"forbidden":          true,
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
