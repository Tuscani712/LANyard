//go:build windows

package uiserver

import "os/exec"

// openPath opens path in Explorer. explorer.exe returns a non-zero exit code
// even on success, so the process is started and not waited on.
func openPath(path string) error {
	c := exec.Command("explorer", path)
	if err := c.Start(); err != nil {
		// ShellExecute via rundll32 handles folders and files alike.
		c2 := exec.Command("rundll32", "url.dll,FileProtocolHandler", path)
		if err2 := c2.Start(); err2 != nil {
			return err
		}
		go func() { _ = c2.Wait() }()
		return nil
	}
	go func() { _ = c.Wait() }()
	return nil
}
