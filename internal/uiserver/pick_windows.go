//go:build windows

package uiserver

import (
	"errors"
	"os"
	"runtime"
	"sync"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// The Windows common item dialog (IFileOpenDialog): the same Explorer-style
// window every Windows app uses, with Quick Access, This PC, OneDrive and a
// search box. Only one is shown at a time.
var (
	pickMu            sync.Mutex
	shell32           = windows.NewLazySystemDLL("shell32.dll")
	user32Pick        = windows.NewLazySystemDLL("user32.dll")
	procCoCreate      = windows.NewLazySystemDLL("ole32.dll").NewProc("CoCreateInstance")
	procSHCreateItem  = shell32.NewProc("SHCreateItemFromParsingName")
	procGetForeground = user32Pick.NewProc("GetForegroundWindow")

	clsidFileOpenDialog = windows.GUID{Data1: 0xDC1C5A9C, Data2: 0xE88A, Data3: 0x4DDE, Data4: [8]byte{0xA5, 0xA1, 0x60, 0xF8, 0x2A, 0x20, 0xAE, 0xF7}}
	iidFileOpenDialog   = windows.GUID{Data1: 0xD57C7288, Data2: 0xD4AD, Data3: 0x4768, Data4: [8]byte{0xBE, 0x02, 0x9D, 0x96, 0x95, 0x32, 0xD9, 0x60}}
	iidShellItem        = windows.GUID{Data1: 0x43826D1E, Data2: 0xE718, Data3: 0x42EE, Data4: [8]byte{0xBC, 0x55, 0xA1, 0xE2, 0x61, 0xC3, 0x7B, 0xFE}}
)

const (
	fosPickFolders      = 0x20
	fosForceFileSystem  = 0x40
	fosAllowMultiSelect = 0x200
	fosPathMustExist    = 0x800
	fosFileMustExist    = 0x1000
	sigdnFileSysPath    = 0x80058000
	hrCancelled         = 0x800704C7
)

type comObj struct{ p unsafe.Pointer }

// call invokes vtable method idx on the object.
func (o comObj) call(idx uintptr, args ...uintptr) uintptr {
	vt := *(*unsafe.Pointer)(o.p)
	fn := *(*uintptr)(unsafe.Add(vt, idx*unsafe.Sizeof(uintptr(0))))
	r, _, _ := syscall.SyscallN(fn, append([]uintptr{uintptr(o.p)}, args...)...)
	return r
}
func (o comObj) release() {
	if o.p != nil {
		o.call(2)
	}
}

func shellItemPath(item comObj) (string, error) {
	var ws *uint16
	if hr := item.call(5, sigdnFileSysPath, uintptr(unsafe.Pointer(&ws))); hr != 0 {
		return "", errors.New("could not read the chosen item")
	}
	defer windows.CoTaskMemFree(unsafe.Pointer(ws))
	return windows.UTF16PtrToString(ws), nil
}

// pickPaths shows the dialog. kind is "folder" or "files". It returns no paths
// and no error when the person cancels.
func pickPaths(kind, title, start string) ([]string, error) {
	pickMu.Lock()
	defer pickMu.Unlock()
	runtime.LockOSThread()
	defer runtime.UnlockOSThread()

	if err := windows.CoInitializeEx(0, windows.COINIT_APARTMENTTHREADED); err == nil {
		defer windows.CoUninitialize()
	}
	var dlgPtr unsafe.Pointer
	const clsctxInprocServer = 1
	if hr, _, _ := procCoCreate.Call(uintptr(unsafe.Pointer(&clsidFileOpenDialog)), 0, clsctxInprocServer,
		uintptr(unsafe.Pointer(&iidFileOpenDialog)), uintptr(unsafe.Pointer(&dlgPtr))); hr != 0 || dlgPtr == nil {
		return nil, errors.New("the Windows file dialog is not available")
	}
	dlg := comObj{dlgPtr}
	defer dlg.release()

	opts := uintptr(fosForceFileSystem | fosPathMustExist)
	if kind == "folder" {
		opts |= fosPickFolders
	} else {
		opts |= fosAllowMultiSelect | fosFileMustExist
	}
	dlg.call(9, opts) // SetOptions
	if title != "" {
		if t, err := windows.UTF16PtrFromString(title); err == nil {
			dlg.call(17, uintptr(unsafe.Pointer(t))) // SetTitle
		}
	}
	if start != "" {
		if st, err := os.Stat(start); err == nil && st.IsDir() {
			if sp, err := windows.UTF16PtrFromString(start); err == nil {
				var item unsafe.Pointer
				if r, _, _ := procSHCreateItem.Call(uintptr(unsafe.Pointer(sp)), 0, uintptr(unsafe.Pointer(&iidShellItem)), uintptr(unsafe.Pointer(&item))); r == 0 && item != nil {
					dlg.call(12, uintptr(item)) // SetFolder
					comObj{item}.release()
				}
			}
		}
	}
	owner, _, _ := procGetForeground.Call()
	if hr := dlg.call(3, owner); hr != 0 { // Show
		if uint32(hr) == hrCancelled {
			return nil, nil
		}
		return nil, errors.New("the file dialog failed")
	}
	if kind == "folder" {
		var item unsafe.Pointer
		if hr := dlg.call(20, uintptr(unsafe.Pointer(&item))); hr != 0 || item == nil { // GetResult
			return nil, errors.New("nothing was chosen")
		}
		it := comObj{item}
		defer it.release()
		p, err := shellItemPath(it)
		if err != nil {
			return nil, err
		}
		return []string{p}, nil
	}
	var arr unsafe.Pointer
	if hr := dlg.call(27, uintptr(unsafe.Pointer(&arr))); hr != 0 || arr == nil { // GetResults
		return nil, errors.New("nothing was chosen")
	}
	a := comObj{arr}
	defer a.release()
	var n uint32
	a.call(7, uintptr(unsafe.Pointer(&n))) // GetCount
	var out []string
	for i := uint32(0); i < n; i++ {
		var item unsafe.Pointer
		if a.call(8, uintptr(i), uintptr(unsafe.Pointer(&item))) != 0 || item == nil { // GetItemAt
			continue
		}
		it := comObj{item}
		if p, err := shellItemPath(it); err == nil {
			out = append(out, p)
		}
		it.release()
	}
	return out, nil
}
