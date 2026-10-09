package uiserver

import (
	"os"
	"strings"
	"testing"
)

// stripLineComments drops // comments so prose that mentions storage APIs is
// not mistaken for real usage.
func stripLineComments(s string) string {
	lines := strings.Split(s, "\n")
	for n, line := range lines {
		if k := strings.Index(line, "//"); k >= 0 {
			lines[n] = line[:k]
		}
	}
	return strings.Join(lines, "\n")
}

// The firewall banner must be dismissible for this session only. Its dismissal
// state lives in a plain JS variable and the banner block must never touch
// localStorage/sessionStorage, so the warning reappears on the next launch.
// The Copy button must carry the ufw commands.
func TestAppJSFirewallBannerInMemoryDismissAndCopyCommands(t *testing.T) {
	src, err := os.ReadFile("web/app.js")
	if err != nil {
		t.Fatalf("read app.js: %v", err)
	}
	js := string(src)

	const start = "// ---------- firewall banner ----------"
	const end = "// ---------- settings helpers ----------"
	i := strings.Index(js, start)
	if i < 0 {
		t.Fatalf("app.js is missing the firewall banner section marker %q", start)
	}
	j := strings.Index(js[i:], end)
	if j < 0 {
		t.Fatalf("app.js is missing the section end marker %q", end)
	}
	section := stripLineComments(js[i : i+j])

	if strings.Contains(section, "localStorage") || strings.Contains(section, "sessionStorage") {
		t.Error("firewall banner dismissal must be in-memory only, not localStorage/sessionStorage")
	}
	for _, want := range []string{
		"let firewallBannerDismissed = false;",
		"firewallBannerDismissed = true;",
		"sudo ufw allow 47800/tcp",
		"sudo ufw allow 47801/udp",
		"sudo ufw allow 5353/udp",
		"loadFirewallBanner();",
	} {
		if !strings.Contains(js, want) {
			t.Errorf("app.js is missing %q", want)
		}
	}
}
