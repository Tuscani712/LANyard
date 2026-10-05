//go:build windows

package main

import (
	"errors"
	"syscall"
	"unsafe"

	webview2 "github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
)

// user32 is declared in tray_windows.go; here we add the few extra calls and
// dwmapi entry points needed to turn the WebView2 host into a frameless shell.
var (
	dwmapi                       = windows.NewLazySystemDLL("dwmapi.dll")
	procGetWindowLongPtrW        = user32.NewProc("GetWindowLongPtrW")
	procSetWindowLongPtrW        = user32.NewProc("SetWindowLongPtrW")
	procSetWindowPos             = user32.NewProc("SetWindowPos")
	procReleaseCapture           = user32.NewProc("ReleaseCapture")
	procSendMessageW             = user32.NewProc("SendMessageW")
	procShowWindow               = user32.NewProc("ShowWindow")
	procIsZoomed                 = user32.NewProc("IsZoomed")
	procEnumWindows              = user32.NewProc("EnumWindows")
	procGetWindowThreadProcessID = user32.NewProc("GetWindowThreadProcessId")
	procIsWindowVisible          = user32.NewProc("IsWindowVisible")
	procGetWindowTextW           = user32.NewProc("GetWindowTextW")
	procDwmSetWindowAttribute    = dwmapi.NewProc("DwmSetWindowAttribute")
)

const (
	wsCaption       = 0x00C00000
	swpNoSize       = 0x0001
	swpNoMove       = 0x0002
	swpNoZOrder     = 0x0004
	swpFrameChanged = 0x0020
	wmNCLButtonDown = 0x00A1
	htCaption       = 2
	swMinimize      = 6
	swHide          = 0
	swShow          = 5
	swMaximize      = 3
	swRestore       = 9
	dwmwaDarkMode   = 20
)

// GWL_STYLE is -16 (as an unsigned index).
const gwlStyle = ^uintptr(15)

// nativeShow brings the window back to the foreground (used if a tray icon is
// present). Kept as a package global so the tray can reference it.
var nativeShow func()

// showNativeWindow restores the window (even if hidden in the tray) and brings
// it to the front. It reports false when there is no native window.
func showNativeWindow() bool {
	f := nativeShow
	if f == nil {
		return false
	}
	f()
	return true
}

// runNativeUI opens the UI in an embedded WebView2 window (Edge, no browser
// chrome) and blocks until the window closes. It returns an error if the
// WebView2 runtime is unavailable so the caller can fall back to a browser.
func runNativeUI(opts nativeUIOptions) error {
	w := webview2.NewWithOptions(webview2.WebViewOptions{
		Debug:     false,
		DataPath:  opts.DataPath,
		AutoFocus: true,
		WindowOptions: webview2.WindowOptions{
			Title:  opts.Title,
			Width:  1280,
			Height: 820,
			IconId: 1,
			Center: true,
		},
	})
	if w == nil {
		return errors.New("WebView2 runtime not available")
	}
	defer w.Destroy()

	hwnd := windows.HWND(w.Window())
	applyShellFrame(hwnd)

	_ = w.Bind("lanWin", func(action string) string {
		switch action {
		case "min":
			if minimizeToTray.Load() && currentTray != nil {
				procShowWindow.Call(uintptr(hwnd), swHide)
			} else {
				procShowWindow.Call(uintptr(hwnd), swMinimize)
			}
		case "max":
			if maximized(hwnd) {
				procShowWindow.Call(uintptr(hwnd), swRestore)
			} else {
				procShowWindow.Call(uintptr(hwnd), swMaximize)
			}
		case "close":
			if minimizeToTray.Load() && currentTray != nil {
				procShowWindow.Call(uintptr(hwnd), swHide)
			} else {
				procPostMessageW.Call(uintptr(hwnd), wmClose, 0, 0)
			}
		}
		if maximized(hwnd) {
			return "max"
		}
		return "restore"
	})
	_ = w.Bind("lanDrag", func() {
		procReleaseCapture.Call()
		procSendMessageW.Call(uintptr(hwnd), wmNCLButtonDown, htCaption, 0)
	})

	nativeShow = func() {
		w.Dispatch(func() {
			procShowWindow.Call(uintptr(hwnd), swShow)
			procShowWindow.Call(uintptr(hwnd), swRestore)
			procSetForegroundWindow.Call(uintptr(hwnd))
		})
	}

	w.Navigate(opts.URL)

	if opts.Done != nil {
		go func() {
			<-opts.Done
			w.Terminate()
		}()
	}
	if opts.Log != nil {
		opts.Log.Info("native window open", "url", opts.URL)
	}

	w.Run() // blocks until the window is closed
	if opts.OnQuit != nil {
		opts.OnQuit()
	}
	return nil
}

func maximized(hwnd windows.HWND) bool {
	r, _, _ := procIsZoomed.Call(uintptr(hwnd))
	return r != 0
}

// focusNativeWindow brings an already-running instance's window to the front.
// It matches by process id (from run.json) so it never picks up an unrelated
// window, and restores the window if it was minimized.
func focusNativeWindow(pid int) bool {
	if pid <= 0 {
		return false
	}
	var found, hidden uintptr
	cb := syscall.NewCallback(func(hwnd uintptr, _ uintptr) uintptr {
		var wp uint32
		procGetWindowThreadProcessID.Call(hwnd, uintptr(unsafe.Pointer(&wp)))
		if int(wp) == pid {
			if vis, _, _ := procIsWindowVisible.Call(hwnd); vis != 0 {
				found = hwnd
				return 0 // stop enumerating
			}
			// A window hidden in the system tray is still "the" window.
			var buf [64]uint16
			n, _, _ := procGetWindowTextW.Call(hwnd, uintptr(unsafe.Pointer(&buf[0])), uintptr(len(buf)))
			if windows.UTF16ToString(buf[:n]) == "LANyard File Transfer" {
				hidden = hwnd
			}
		}
		return 1
	})
	procEnumWindows.Call(cb, 0)
	if found == 0 {
		found = hidden
	}
	if found == 0 {
		return false
	}
	procShowWindow.Call(found, swShow)
	procShowWindow.Call(found, swRestore)
	procSetForegroundWindow.Call(found)
	return true
}

// applyShellFrame removes the system caption so the page can draw its own title
// bar, keeps the resize border, and asks DWM for a dark frame.
func applyShellFrame(hwnd windows.HWND) {
	style, _, _ := procGetWindowLongPtrW.Call(uintptr(hwnd), gwlStyle)
	style &^= wsCaption
	procSetWindowLongPtrW.Call(uintptr(hwnd), gwlStyle, style)
	procSetWindowPos.Call(uintptr(hwnd), 0, 0, 0, 0, 0,
		swpNoSize|swpNoMove|swpNoZOrder|swpFrameChanged)

	dark := int32(1)
	procDwmSetWindowAttribute.Call(uintptr(hwnd), dwmwaDarkMode,
		uintptr(unsafe.Pointer(&dark)), unsafe.Sizeof(dark))
	procDwmSetWindowAttribute.Call(uintptr(hwnd), dwmwaDarkMode-1,
		uintptr(unsafe.Pointer(&dark)), unsafe.Sizeof(dark))
}
