package uiserver

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"

	"lanyard/internal/config"
)

// The firewall commands must be generated from the configured peer and beacon
// ports (plus the fixed mDNS port), not a hard-coded default.
func TestFirewallCommandsUsesConfiguredPorts(t *testing.T) {
	cases := []struct {
		peer, beacon int
		want         string
	}{
		{47800, 47801, "sudo ufw allow 47800/tcp && sudo ufw allow 47801/udp && sudo ufw allow 5353/udp"},
		{5000, 6000, "sudo ufw allow 5000/tcp && sudo ufw allow 6000/udp && sudo ufw allow 5353/udp"},
		{0, 0, "sudo ufw allow 47800/tcp && sudo ufw allow 47801/udp && sudo ufw allow 5353/udp"},
	}
	for _, c := range cases {
		if got := FirewallCommands(c.peer, c.beacon); got != c.want {
			t.Errorf("FirewallCommands(%d, %d) = %q, want %q", c.peer, c.beacon, got, c.want)
		}
	}
}

// The README's copy-paste firewall line must be the same command the app
// generates for the default ports, so the docs cannot drift from the banner.
func TestREADMEFirewallCommandsMatchDefaults(t *testing.T) {
	readme, err := os.ReadFile("../../README.md")
	if err != nil {
		t.Fatalf("read README: %v", err)
	}
	want := FirewallCommands(config.DefaultPeerPort, config.DefaultBeaconPort)
	if !strings.Contains(string(readme), want) {
		t.Errorf("README does not contain the generated default firewall command %q", want)
	}
}

// The banner's JS command builder must use the ports it is given (verified by
// running the real extracted function under node). Skipped when node is absent.
func TestAppJSFirewallCommandsUsePorts(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed; skipping JS-level firewall command check")
	}
	js := readAppJS(t)
	block := extractBlock(t, js,
		"// ---- firewall commands (extracted verbatim by firewall_banner_test.go) ----",
		"// ---- end firewall commands ----")

	script := block + `
const out = [
  firewallCommands(5000, 6000),
  firewallCommands(0, 0),
];
console.log(JSON.stringify(out));
`
	dir := t.TempDir()
	file := filepath.Join(dir, "firewall_check.mjs")
	if err := os.WriteFile(file, []byte(script), 0o600); err != nil {
		t.Fatalf("write node script: %v", err)
	}
	raw := runNode(t, node, file)
	var got []string
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("parse node output %q: %v", raw, err)
	}
	want := []string{
		"sudo ufw allow 5000/tcp && sudo ufw allow 6000/udp && sudo ufw allow 5353/udp",
		"sudo ufw allow 47800/tcp && sudo ufw allow 47801/udp && sudo ufw allow 5353/udp",
	}
	for i := range want {
		if got[i] != want[i] {
			t.Errorf("firewallCommands case %d = %q, want %q", i, got[i], want[i])
		}
	}
}
