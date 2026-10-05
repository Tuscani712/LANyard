//go:build !windows

package main

// startTray has no implementation outside Windows; the app runs headless.
func startTray(trayOptions) func() { return func() {} }
