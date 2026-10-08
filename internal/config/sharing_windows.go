//go:build windows

package config

import (
	"errors"
	"syscall"
)

// Windows system error codes for a file that is held by another process in a
// way that prevents replacing it right now. MoveFileEx reports a held
// destination as ERROR_ACCESS_DENIED, while delete paths report
// ERROR_SHARING_VIOLATION; both are transient while a handle is open without
// FILE_SHARE_DELETE. Errors are compared by value so this works against an
// *os.LinkError-wrapped syscall.Errno.
const (
	errAccessDenied     = syscall.Errno(5)  // ERROR_ACCESS_DENIED
	errSharingViolation = syscall.Errno(32) // ERROR_SHARING_VIOLATION
	errLockViolation    = syscall.Errno(33) // ERROR_LOCK_VIOLATION
)

// isSharingViolation reports whether err is a transient Windows sharing, lock
// or access-denied error that a short retry might clear.
func isSharingViolation(err error) bool {
	return errors.Is(err, errAccessDenied) ||
		errors.Is(err, errSharingViolation) ||
		errors.Is(err, errLockViolation)
}
