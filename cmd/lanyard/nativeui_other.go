//go:build !windows

package main

import "errors"

// runNativeUI is only implemented on Windows for now; elsewhere LANyard uses
// the browser UI (or runs headless).
func runNativeUI(nativeUIOptions) error {
	return errors.New("the native window is not available on this platform yet")
}

// focusNativeWindow is Windows-only; elsewhere there is no native window.
func focusNativeWindow(int) bool { return false }

// showNativeWindow is Windows-only.
func showNativeWindow() bool { return false }

const nativeProfileDir = "browser"

func nativeSupported() bool { return false }
