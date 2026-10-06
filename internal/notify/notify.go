// Package notify raises desktop notifications for events a person should see
// even when the window is not focused: an incoming pairing request and a
// finished or failed transfer. It is a thin, dependency-light layer: the
// platform notifier is selected at build time, and the host asks it not to
// send anything while desktop notifications are turned off.
package notify

import (
	"html"
	"strings"
)

// Notice is one desktop notification, shown as a title and a short body.
type Notice struct {
	Title string
	Body  string
}

// Notifier posts a desktop notification. Implementations must return quickly
// and must not block; a missing notification service is reported as an error
// the caller can ignore.
type Notifier interface {
	Notify(Notice) error
}

// Client applies the user's on/off setting before delegating to the platform
// notifier. enabled is consulted on every call so a settings change takes
// effect without restarting. A nil enabled means "always on".
type Client struct {
	n       Notifier
	enabled func() bool
}

// New returns a Client backed by the platform notifier. enabled reports
// whether desktop notifications are currently on (it may be nil to always
// send).
func New(enabled func() bool) *Client {
	return &Client{n: newNotifier(), enabled: enabled}
}

// Notify posts n unless notifications are turned off. A nil Client is inert.
func (c *Client) Notify(n Notice) error {
	if c == nil || c.n == nil {
		return nil
	}
	if c.enabled != nil && !c.enabled() {
		return nil
	}
	return c.n.Notify(Notice{Title: clean(n.Title), Body: clean(n.Body)})
}

// clean makes text that may contain a remote device's name safe to show:
// notification daemons render a markup subset (links, bold), so it is escaped,
// control characters are dropped, and the length is capped.
func clean(s string) string {
	s = strings.Map(func(r rune) rune {
		if r < 0x20 || r == 0x7f {
			return ' '
		}
		return r
	}, s)
	if r := []rune(s); len(r) > 200 {
		s = string(r[:200]) + "…"
	}
	return html.EscapeString(s)
}
