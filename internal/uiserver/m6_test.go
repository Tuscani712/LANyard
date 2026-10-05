package uiserver

import "testing"

func TestValidDeviceLabel(t *testing.T) {
	good := []string{"alice", "Alice-PC", "dev_01", "a.b.c", "ABC123"}
	for _, s := range good {
		if !validDeviceLabel(s) {
			t.Errorf("%q should be valid", s)
		}
	}
	bad := []string{"", "ab", "has space", "bad/slash", "emoji😀", "this-label-is-way-too-long-to-be-accepted"}
	for _, s := range bad {
		if validDeviceLabel(s) {
			t.Errorf("%q should be invalid", s)
		}
	}
}

func TestOneOf(t *testing.T) {
	if !oneOf("dark", "light", "dark", "system") {
		t.Error("dark should match")
	}
	if oneOf("sepia", "light", "dark", "system") {
		t.Error("sepia should not match")
	}
}

func TestCleanText(t *testing.T) {
	if got := cleanText("  hi\x00 there  ", 64); got != "hi there" {
		t.Errorf("cleanText = %q", got)
	}
	if got := cleanText("abcdef", 3); got != "abc" {
		t.Errorf("cleanText truncate = %q", got)
	}
}
