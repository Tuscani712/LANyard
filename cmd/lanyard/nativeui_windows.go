//go:build windows

package main

import (
	"errors"
	"unsafe"

	webview2 "github.com/jchv/go-webview2"
	"golang.org/x/sys/windows"
)

// user32 is declared in tray_windows.go; here we add the few extra calls and
// dwmapi entry points needed to turn the WebView2 host into a frameless shell.
var (
	dwmapi                    = windows.NewLazySystemDLL("dwmapi.dll")
	procGetWindowLongPtrW     = user32.NewProc("GetWindowLongPtrW")
	procSetWindowLongPtrW     = user32.NewProc("SetWindowLongPtrW")
	procSetWindowPos          = user32.NewProc("SetWindowPos")
	procReleaseCapture        = user32.NewProc("ReleaseCapture")
	procSendMessageW          = user32.NewProc("SendMessageW")
	procShowWindow            = user32.NewProc("ShowWindow")
	procIsZoomed              = user32.NewProc("IsZoomed")
	procDwmSetWindowAttribute = dwmapi.NewProc("DwmSetWindowAttribute")
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
	swMaximize      = 3
	swRestore       = 9
	dwmwaDarkMode   = 20
)

// GWL_STYLE is -16 (as an unsigned index).
const gwlStyle = ^uintptr(15)

// nativeShow brings the window back to the foreground (used if a tray icon is
// present). Kept as a package global so the tray can reference it.
var nativeShow func()

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
			procShowWindow.Call(uintptr(hwnd), swMinimize)
		case "max":
			if maximized(hwnd) {
				procShowWindow.Call(uintptr(hwnd), swRestore)
			} else {
				procShowWindow.Call(uintptr(hwnd), swMaximize)
			}
		case "close":
			procPostMessageW.Call(uintptr(hwnd), wmClose, 0, 0)
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
		w.Dispatch(func() { procShowWindow.Call(uintptr(hwnd), swRestore) })
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
