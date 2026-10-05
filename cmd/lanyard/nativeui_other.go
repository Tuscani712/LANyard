//go:build !windows

package main

import "errors"

// runNativeUI is only implemented on Windows for now; elsewhere LANyard uses
// the browser UI (or runs headless).
func runNativeUI(nativeUIOptions) error {
	return errors.New("the native window is not available on this platform yet")
}
