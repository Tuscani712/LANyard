package inbox

import "testing"

// On Windows, DOS device names are remapped with a leading underscore; the rule
// is exercised here on any host by toggling the gate.
func TestSanitizeWindowsReservedNames(t *testing.T) {
	old := windowsNames
	windowsNames = true
	defer func() { windowsNames = old }()

	cases := []struct{ in, want string }{
		{"CON", "_CON"},
		{"con.txt", "_con.txt"},
		{"PRN", "_PRN"},
		{"AUX.log", "_AUX.log"},
		{"NUL", "_NUL"},
		{"COM1", "_COM1"},
		{"com9.tar.gz", "_com9.tar.gz"},
		{"LPT1", "_LPT1"},
		{"lpt9.txt", "_lpt9.txt"},
		{"sub/CON.txt", "sub/_CON.txt"},
		// Stripped trailing dots/spaces happen before the reserved check.
		{"CON. ", "_CON"},
		{"report. ", "report"},
		{"notes.txt...", "notes.txt"},
		// Not reserved.
		{"console.txt", "console.txt"},
		{"COM0", "COM0"},
		{"COM10", "COM10"},
		{"LPT", "LPT"},
		{"normal file.txt", "normal file.txt"},
	}
	for _, tc := range cases {
		got, err := sanitize(tc.in)
		if err != nil {
			t.Fatalf("sanitize(%q): %v", tc.in, err)
		}
		if got != tc.want {
			t.Errorf("sanitize(%q) = %q, want %q", tc.in, got, tc.want)
		}
	}
}

// Off Windows the reserved-name remap is disabled.
func TestSanitizeLeavesReservedNamesOnNonWindows(t *testing.T) {
	old := windowsNames
	windowsNames = false
	defer func() { windowsNames = old }()

	got, err := sanitize("CON.txt")
	if err != nil {
		t.Fatal(err)
	}
	if got != "CON.txt" {
		t.Fatalf("sanitize(CON.txt) = %q, want CON.txt off Windows", got)
	}
}
