//go:build windows

package main

import (
	"bytes"
	_ "embed"
	"os"
	"runtime"
	"unsafe"

	"golang.org/x/sys/windows"
)

// trayIcon is the multi-size .ico, embedded so the tray never depends on the
// resource id that rsrc assigns to the binary icon.
//
//go:embed icon.ico
var trayIcon []byte

const (
	wmApp          = 0x8000 + 1
	wmClose        = 0x0010
	wmDestroy      = 0x0002
	wmLButtonUp    = 0x0202
	wmRButtonUp    = 0x0205
	nimAdd         = 0
	nimDelete      = 2
	nifMessage     = 0x01
	nifIcon        = 0x02
	nifTip         = 0x04
	imageIcon      = 1
	lrLoadFromFile = 0x0010
	mfString       = 0x0000
	tpmRightButton = 0x0002
	tpmReturnCmd   = 0x0100
	idiApplication = 32512
	csHredraw      = 0x0002
	csVredraw      = 0x0001
)

var (
	user32   = windows.NewLazySystemDLL("user32.dll")
	shell32  = windows.NewLazySystemDLL("shell32.dll")
	kernel32 = windows.NewLazySystemDLL("kernel32.dll")

	procRegisterClassExW    = user32.NewProc("RegisterClassExW")
	procCreateWindowExW     = user32.NewProc("CreateWindowExW")
	procDefWindowProcW      = user32.NewProc("DefWindowProcW")
	procGetMessageW         = user32.NewProc("GetMessageW")
	procTranslateMessage    = user32.NewProc("TranslateMessage")
	procDispatchMessageW    = user32.NewProc("DispatchMessageW")
	procPostQuitMessage     = user32.NewProc("PostQuitMessage")
	procPostMessageW        = user32.NewProc("PostMessageW")
	procDestroyWindow       = user32.NewProc("DestroyWindow")
	procLoadImageW          = user32.NewProc("LoadImageW")
	procLoadIconW           = user32.NewProc("LoadIconW")
	procDestroyIcon         = user32.NewProc("DestroyIcon")
	procCreatePopupMenu     = user32.NewProc("CreatePopupMenu")
	procAppendMenuW         = user32.NewProc("AppendMenuW")
	procTrackPopupMenu      = user32.NewProc("TrackPopupMenu")
	procSetForegroundWindow = user32.NewProc("SetForegroundWindow")
	procGetCursorPos        = user32.NewProc("GetCursorPos")
	procDestroyMenu         = user32.NewProc("DestroyMenu")
	procShellNotifyIconW    = shell32.NewProc("Shell_NotifyIconW")
	procGetModuleHandleW    = kernel32.NewProc("GetModuleHandleW")
)

type point struct{ X, Y int32 }

type msg struct {
	HWnd    windows.HWND
	Message uint32
	WParam  uintptr
	LParam  uintptr
	Time    uint32
	Pt      point
	Private uint32
}

type wndClassEx struct {
	CbSize        uint32
	Style         uint32
	WndProc       uintptr
	CbClsExtra    int32
	CbWndExtra    int32
	HInstance     windows.Handle
	HIcon         uintptr
	HCursor       uintptr
	HbrBackground uintptr
	MenuName      *uint16
	ClassName     *uint16
	HIconSm       uintptr
}

type guid struct {
	Data1 uint32
	Data2 uint16
	Data3 uint16
	Data4 [8]byte
}

type notifyIconData struct {
	CbSize           uint32
	HWnd             windows.HWND
	UID              uint32
	UFlags           uint32
	UCallbackMessage uint32
	HIcon            uintptr
	SzTip            [128]uint16
	DwState          uint32
	DwStateMask      uint32
	SzInfo           [256]uint16
	UVersion         uint32
	SzInfoTitle      [64]uint16
	DwInfoFlags      uint32
	GuidItem         guid
	HBalloonIcon     uintptr
}

type tray struct {
	opts  trayOptions
	hwnd  windows.HWND
	hIcon uintptr
	nid   notifyIconData
}

var currentTray *tray

// startTray writes the icon next to the data and starts the message loop. The
// returned function asks the tray to close.
func startTray(opts trayOptions) func() {
	if err := ensureTrayIcon(opts.IconPath); err != nil {
		if opts.Log != nil {
			opts.Log.Warn("tray icon file could not be written", "err", err)
		}
		return func() {}
	}
	t := &tray{opts: opts}
	currentTray = t
	go t.run()
	return t.requestQuit
}

func ensureTrayIcon(path string) error {
	if b, err := os.ReadFile(path); err == nil && bytes.Equal(b, trayIcon) {
		return nil
	}
	return os.WriteFile(path, trayIcon, 0o644)
}

func (t *tray) run() {
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	hInst, _, _ := procGetModuleHandleW.Call(0)
	className, _ := windows.UTF16PtrFromString("LanyardTrayWnd")
	wc := wndClassEx{
		Style:     csHredraw | csVredraw,
		WndProc:   windows.NewCallback(trayWndProc),
		HInstance: windows.Handle(hInst),
		ClassName: className,
	}
	wc.CbSize = uint32(unsafe.Sizeof(wc))
	procRegisterClassExW.Call(uintptr(unsafe.Pointer(&wc))) // 0 if already registered

	title, _ := windows.UTF16PtrFromString("LANyard File Transfer")
	hwnd, _, _ := procCreateWindowExW.Call(0,
		uintptr(unsafe.Pointer(className)), uintptr(unsafe.Pointer(title)),
		0, 0, 0, 0, 0, 0, 0, hInst, 0)
	if hwnd == 0 {
		if t.opts.Log != nil {
			t.opts.Log.Warn("tray window could not be created; no tray icon")
		}
		return
	}
	t.hwnd = windows.HWND(hwnd)

	iconPath, _ := windows.UTF16PtrFromString(t.opts.IconPath)
	h, _, _ := procLoadImageW.Call(0, uintptr(unsafe.Pointer(iconPath)), imageIcon, 0, 0, lrLoadFromFile)
	if h == 0 {
		h, _, _ = procLoadIconW.Call(0, idiApplication)
	}
	t.hIcon = h

	t.nid = notifyIconData{
		HWnd:             t.hwnd,
		UID:              1,
		UFlags:           nifMessage | nifIcon | nifTip,
		UCallbackMessage: wmApp,
		HIcon:            h,
	}
	t.nid.CbSize = uint32(unsafe.Sizeof(t.nid))
	tip := windows.StringToUTF16("LANyard File Transfer")
	copy(t.nid.SzTip[:], tip)

	if ok, _, err := procShellNotifyIconW.Call(nimAdd, uintptr(unsafe.Pointer(&t.nid))); ok == 0 {
		if t.opts.Log != nil {
			t.opts.Log.Warn("could not add the tray icon", "err", err)
		}
		return
	}
	if t.opts.Log != nil {
		t.opts.Log.Info("tray icon added")
	}

	var m msg
	for {
		r, _, _ := procGetMessageW.Call(uintptr(unsafe.Pointer(&m)), 0, 0, 0)
		if int32(r) <= 0 { // WM_QUIT or error
			break
		}
		procTranslateMessage.Call(uintptr(unsafe.Pointer(&m)))
		procDispatchMessageW.Call(uintptr(unsafe.Pointer(&m)))
	}
}

// requestQuit closes the tray window from another goroutine (e.g. on shutdown).
func (t *tray) requestQuit() {
	if t.hwnd != 0 {
		procPostMessageW.Call(uintptr(t.hwnd), wmClose, 0, 0)
	}
}

func (t *tray) remove() {
	procShellNotifyIconW.Call(nimDelete, uintptr(unsafe.Pointer(&t.nid)))
	if t.hIcon != 0 {
		procDestroyIcon.Call(t.hIcon)
		t.hIcon = 0
	}
}

func (t *tray) showMenu() {
	menu, _, _ := procCreatePopupMenu.Call()
	if menu == 0 {
		return
	}
	openTxt, _ := windows.UTF16PtrFromString("Open LANyard")
	quitTxt, _ := windows.UTF16PtrFromString("Quit")
	procAppendMenuW.Call(menu, mfString, 1, uintptr(unsafe.Pointer(openTxt)))
	procAppendMenuW.Call(menu, mfString, 2, uintptr(unsafe.Pointer(quitTxt)))
	var pt point
	procGetCursorPos.Call(uintptr(unsafe.Pointer(&pt)))
	procSetForegroundWindow.Call(uintptr(t.hwnd))
	cmd, _, _ := procTrackPopupMenu.Call(menu, tpmRightButton|tpmReturnCmd,
		uintptr(int(pt.X)), uintptr(int(pt.Y)), 0, uintptr(t.hwnd), 0)
	procDestroyMenu.Call(menu)
	switch cmd {
	case 1:
		if t.opts.OnOpen != nil {
			t.opts.OnOpen()
		}
	case 2:
		if t.opts.OnQuit != nil {
			t.opts.OnQuit()
		}
	}
}

func trayWndProc(hwnd uintptr, message uint32, wparam, lparam uintptr) uintptr {
	switch message {
	case wmApp:
		switch uint32(lparam) & 0xffff {
		case wmLButtonUp:
			if currentTray != nil && currentTray.opts.OnOpen != nil {
				currentTray.opts.OnOpen()
			}
		case wmRButtonUp:
			if currentTray != nil {
				currentTray.showMenu()
			}
		}
		return 0
	case wmClose:
		procDestroyWindow.Call(hwnd)
		return 0
	case wmDestroy:
		if currentTray != nil {
			currentTray.remove()
		}
		procPostQuitMessage.Call(0)
		return 0
	}
	r, _, _ := procDefWindowProcW.Call(hwnd, uintptr(message), wparam, lparam)
	return r
}

// trayProbe reports whether the tray icon was created, for the Settings toggle.
func trayProbe() (bool, string) {
	if currentTray != nil {
		return true, ""
	}
	return false, "The system tray icon could not be created."
}
