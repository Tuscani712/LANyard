package autostart

import (
	"strings"
	"testing"
)

func TestDesktopEntry(t *testing.T) {
	got := DesktopEntry("/opt/LANyard/lanyard", []string{"--no-browser", "--data-dir", "/home/a b/data"})
	for _, want := range []string{
		"[Desktop Entry]", "Type=Application", "Name=LANyard File Transfer",
		`Exec=/opt/LANyard/lanyard --no-browser --data-dir "/home/a b/data"`,
	} {
		if !strings.Contains(got, want) {
			t.Errorf("desktop entry missing %q:\n%s", want, got)
		}
	}
}

func TestQuoteDesktopEscapes(t *testing.T) {
	cases := map[string]string{
		"plain": "plain",
		"a b":   `"a b"`,
		`a"b`:   `"a\"b"`,
		"a$b":   `"a\\$b"`,
		"a`b":   "\"a\\\\`b\"",
		`a\b`:   `"a\\\\b"`,
	}
	for in, want := range cases {
		if got := quoteDesktop(in); got != want {
			t.Errorf("quoteDesktop(%q) = %s, want %s", in, got, want)
		}
	}
}

func TestLaunchAgentPlist(t *testing.T) {
	got := LaunchAgentPlist("com.lanyard.filetransfer", "/Applications/LANyard & Co/lanyard", []string{"--no-browser"})
	for _, want := range []string{
		"<key>Label</key>", "<string>com.lanyard.filetransfer</string>",
		"<string>/Applications/LANyard &amp; Co/lanyard</string>", "<string>--no-browser</string>",
		"<key>RunAtLoad</key>", "<true/>",
	} {
		if !strings.Contains(got, want) {
			t.Errorf("plist missing %q:\n%s", want, got)
		}
	}
}
