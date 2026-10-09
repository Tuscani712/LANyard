//go:build !windows

package config

// isSharingViolation is always false off Windows, where replacing an open file
// is allowed; there is no transient sharing violation to retry.
func isSharingViolation(error) bool { return false }
