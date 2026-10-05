package main

import (
	"os"

	"golang.org/x/sys/windows"
)

// usable reports whether a standard handle points at something (a pipe, file
// or console), i.e. the caller redirected it.
func usable(id uint32) bool {
	h, err := windows.GetStdHandle(id)
	if err != nil || h == 0 || h == windows.InvalidHandle {
		return false
	}
	t, err := windows.GetFileType(h)
	return err == nil && t != windows.FILE_TYPE_UNKNOWN
}

// attachParentConsole lets the windowless (GUI-subsystem) Windows build print
// to the terminal it was started from, so "lanyard peers" and friends work.
// Redirected handles (cmd > file, Start-Process -RedirectStandardOutput) are
// kept as they are. Started by double-click or at sign-in there is no parent
// console and this does nothing.
//
// Limit of the GUI subsystem: cmd.exe does not wait for such a program, so its
// output can appear after the prompt returns. Use the console build
// (lanyard-win-x64-console.exe) when a shell must wait for the command.
func attachParentConsole() {
	k32 := windows.NewLazySystemDLL("kernel32.dll")
	const attachParentProcess = ^uintptr(0) // (DWORD)-1
	if r, _, _ := k32.NewProc("AttachConsole").Call(attachParentProcess); r == 0 {
		return
	}
	if !usable(windows.STD_OUTPUT_HANDLE) {
		if f, err := os.OpenFile("CONOUT$", os.O_WRONLY, 0); err == nil {
			os.Stdout = f
		}
	}
	if !usable(windows.STD_ERROR_HANDLE) {
		if f, err := os.OpenFile("CONOUT$", os.O_WRONLY, 0); err == nil {
			os.Stderr = f
		}
	}
	if !usable(windows.STD_INPUT_HANDLE) {
		if f, err := os.OpenFile("CONIN$", os.O_RDONLY, 0); err == nil {
			os.Stdin = f
		}
	}
}
