package config

import "testing"

func TestNotificationsDefaultOn(t *testing.T) {
	var s Settings
	if !s.NotificationsEnabled() {
		t.Fatal("notifications should default to on when unset")
	}
	off := false
	s.Notifications = &off
	if s.NotificationsEnabled() {
		t.Fatal("an explicit off should disable notifications")
	}
	on := true
	s.Notifications = &on
	if !s.NotificationsEnabled() {
		t.Fatal("an explicit on should enable notifications")
	}
}
