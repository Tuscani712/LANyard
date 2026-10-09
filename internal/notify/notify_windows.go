package notify

// windowsNotifier is the staged Windows backend. The notification plumbing
// (a Shell_NotifyIcon balloon or a WinRT toast) is not wired yet; this stub
// keeps the interface satisfied and Windows builds green until then.
type windowsNotifier struct{}

func newNotifier() Notifier { return windowsNotifier{} }

func (windowsNotifier) Notify(Notice) error { return nil }
