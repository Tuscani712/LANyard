//go:build !windows

package uiserver

// freeDriveLetters has no meaning off Windows.
func freeDriveLetters() []string { return nil }
