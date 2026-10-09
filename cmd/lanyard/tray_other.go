//go:build !windows && !linux

package main

// startTray has no implementation outside Windows; the app runs headless.
func startTray(trayOptions) func() { return func() {} }

// trayProbe reports that there is no tray on this platform.
func trayProbe() (bool, string) { return false, "The system tray is not available on this platform." }
