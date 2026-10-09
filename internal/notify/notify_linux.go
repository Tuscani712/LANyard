package notify

import (
	"sync"

	"github.com/godbus/dbus/v5"
)

// This is the pure-Go Linux backend: it talks to the freedesktop notification
// service over the session bus, so it needs no cgo and no extra dependency
// (godbus is already used by the tray).
const (
	notifDest  = "org.freedesktop.Notifications"
	notifIface = "org.freedesktop.Notifications"
	appName    = "LANyard"
)

// linuxNotifier lazily connects to the session bus on first use and reuses the
// connection for later notifications.
type linuxNotifier struct {
	once sync.Once
	obj  dbus.BusObject
	err  error
}

func newNotifier() Notifier { return &linuxNotifier{} }

func (l *linuxNotifier) Notify(n Notice) error {
	l.once.Do(func() {
		conn, err := dbus.SessionBus()
		if err != nil {
			l.err = err
			return
		}
		l.obj = conn.Object(notifDest, dbus.ObjectPath("/"+notifIface))
	})
	if l.err != nil {
		return l.err
	}
	hints := map[string]dbus.Variant{
		"desktop-entry": dbus.MakeVariant("lanyard"),
		// urgency 1 = normal; the daemon matches its own theme.
		"urgency": dbus.MakeVariant(byte(1)),
	}
	call := l.obj.Call(notifIface+".Notify", 0,
		appName, uint32(0), "", n.Title, n.Body,
		[]string{}, hints, int32(-1))
	return call.Err
}
