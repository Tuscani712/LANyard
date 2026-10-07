//go:build !windows

package uiserver

import (
	"os/exec"
	"runtime"
)

// openPath reveals path in the desktop's file manager. The opener is started
// and not waited on, so a slow or missing file manager cannot block the handler.
func openPath(path string) error {
	cmd := "xdg-open"
	if runtime.GOOS == "darwin" {
		cmd = "open"
	}
	c := exec.Command(cmd, path)
	if err := c.Start(); err != nil {
		return err
	}
	go func() { _ = c.Wait() }()
	return nil
}
