package autostart

import (
	"os"
	"path/filepath"
)

const agentLabel = "com.lanyard.filetransfer"

func agentPath() (string, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return "", err
	}
	return filepath.Join(home, "Library", "LaunchAgents", agentLabel+".plist"), nil
}

// Enable writes a LaunchAgent that runs at the next sign-in.
func Enable(exe string, args ...string) error {
	p, err := agentPath()
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		return err
	}
	return os.WriteFile(p, []byte(LaunchAgentPlist(agentLabel, exe, args)), 0o644)
}

// Disable removes the LaunchAgent.
func Disable() error {
	p, err := agentPath()
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !os.IsNotExist(err) {
		return err
	}
	return nil
}

// Enabled reports whether the LaunchAgent exists.
func Enabled() bool {
	p, err := agentPath()
	if err != nil {
		return false
	}
	_, err = os.Stat(p)
	return err == nil
}
