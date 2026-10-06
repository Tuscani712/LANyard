//go:build !linux && !windows

package notify

// On platforms without a desktop-notification backend, notifications are a
// no-op so callers do not need build-tagged code.
type noopNotifier struct{}

func (noopNotifier) Notify(Notice) error { return nil }

func newNotifier() Notifier { return noopNotifier{} }
