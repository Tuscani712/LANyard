package transfer

import "testing"

func TestSanitizeRemote(t *testing.T) {
	good := map[string]string{
		"a.txt":             "a.txt",
		"sub/b.bin":         "sub/b.bin",
		"a/../b":            "", // rejected below
		"CON":               "_CON",
		"nul.txt":           "_nul.txt",
		"trailing. ":        "trailing",
		"weird\x01name.txt": "weirdname.txt",
	}
	for in, want := range good {
		got, err := SanitizeRemote(in)
		if want == "" {
			if err == nil {
				t.Errorf("SanitizeRemote(%q) expected error, got %q", in, got)
			}
			continue
		}
		if err != nil {
			t.Errorf("SanitizeRemote(%q) error: %v", in, err)
			continue
		}
		if got != want {
			t.Errorf("SanitizeRemote(%q) = %q, want %q", in, got, want)
		}
	}
	for _, bad := range []string{"..", "../x", "/abs", `a\b`, ""} {
		if _, err := SanitizeRemote(bad); err == nil {
			t.Errorf("SanitizeRemote(%q) should fail", bad)
		}
	}
}

func TestReservedName(t *testing.T) {
	for _, s := range []string{"CON", "com1", "NUL.txt", "LPT9"} {
		if !isReservedName(s) {
			t.Errorf("%q should be reserved", s)
		}
	}
	for _, s := range []string{"console", "com0", "notes", "com10"} {
		if isReservedName(s) {
			t.Errorf("%q should not be reserved", s)
		}
	}
}
