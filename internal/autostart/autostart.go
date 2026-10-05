// Package autostart registers LANyard to start when the user signs in:
// the HKCU Run key on Windows, a LaunchAgent on macOS, an XDG autostart entry
// elsewhere. Nothing needs administrator rights.
package autostart

import (
	"fmt"
	"html"
	"strings"
)

// Name is the identity used for the registry value and file names.
const Name = "LANyard"

// quoteDesktop quotes an argument for an XDG Exec= line.
func quoteDesktop(s string) string {
	if s != "" && !strings.ContainsAny(s, " \t\"'\\$`<>~|&;*?#()") {
		return s
	}
	r := strings.NewReplacer(`\`, `\\\\`, `"`, `\"`, "`", "\\\\`", "$", `\\$`)
	return `"` + r.Replace(s) + `"`
}

// DesktopEntry renders an XDG autostart .desktop file.
func DesktopEntry(exe string, args []string) string {
	parts := []string{quoteDesktop(exe)}
	for _, a := range args {
		parts = append(parts, quoteDesktop(a))
	}
	return "[Desktop Entry]\nType=Application\nName=LANyard File Transfer\n" +
		"Comment=Start LANyard File Transfer when you sign in\n" +
		"Exec=" + strings.Join(parts, " ") + "\nTerminal=false\nX-GNOME-Autostart-enabled=true\n"
}

// LaunchAgentPlist renders a macOS LaunchAgent property list.
func LaunchAgentPlist(label, exe string, args []string) string {
	var b strings.Builder
	b.WriteString("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
	b.WriteString("<!DOCTYPE plist PUBLIC \"-//Apple//DTD PLIST 1.0//EN\" \"http://www.apple.com/DTDs/PropertyList-1.0.dtd\">\n")
	b.WriteString("<plist version=\"1.0\">\n<dict>\n")
	fmt.Fprintf(&b, "\t<key>Label</key>\n\t<string>%s</string>\n", html.EscapeString(label))
	b.WriteString("\t<key>ProgramArguments</key>\n\t<array>\n")
	fmt.Fprintf(&b, "\t\t<string>%s</string>\n", html.EscapeString(exe))
	for _, a := range args {
		fmt.Fprintf(&b, "\t\t<string>%s</string>\n", html.EscapeString(a))
	}
	b.WriteString("\t</array>\n\t<key>RunAtLoad</key>\n\t<true/>\n</dict>\n</plist>\n")
	return b.String()
}
