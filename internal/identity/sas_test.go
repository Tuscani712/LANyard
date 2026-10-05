package identity

import "testing"

func TestSASDeterministicAndSymmetric(t *testing.T) {
	a := SAS("aaaa", "bbbb", "nonce-one", "nonce-two")
	if len(a) != 6 {
		t.Fatalf("SAS length = %d, want 6", len(a))
	}
	for _, r := range a {
		if r < '0' || r > '9' {
			t.Fatalf("SAS %q is not 6 digits", a)
		}
	}
	// Swapping both fingerprints and their nonces must not change the code.
	if b := SAS("bbbb", "aaaa", "nonce-two", "nonce-one"); b != a {
		t.Errorf("SAS is not order independent: %s vs %s", a, b)
	}
	// A different certificate (man-in-the-middle) changes the code.
	if c := SAS("aaaa", "cccc", "nonce-one", "nonce-two"); c == a {
		t.Errorf("different certificates produced the same SAS %s", a)
	}
	// A different nonce changes the code.
	if d := SAS("aaaa", "bbbb", "nonce-one", "nonce-three"); d == a {
		t.Errorf("different nonce produced the same SAS %s", a)
	}
}

func TestGroupSAS(t *testing.T) {
	if got := GroupSAS("482913"); got != "482 913" {
		t.Errorf("GroupSAS = %q", got)
	}
}
