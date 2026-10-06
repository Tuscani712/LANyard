package inbox

import (
	"crypto/sha256"
	"encoding/hex"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func sha(data []byte) string { h := sha256.Sum256(data); return hex.EncodeToString(h[:]) }

func TestOfferWriteComplete(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("hello push")
	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "sub/a.txt", Size: int64(len(data)), MTime: time.Now()}}, 1<<20)
	if err != nil {
		t.Fatalf("Offer: %v", err)
	}
	n, err := m.WriteChunk(p.ID, "peer", "sub/a.txt", 0, strings.NewReader(string(data)))
	if err != nil || n != int64(len(data)) {
		t.Fatalf("WriteChunk n=%d err=%v", n, err)
	}
	st, err := m.Complete(p.ID, "peer", "sub/a.txt", sha(data))
	if err != nil {
		t.Fatalf("Complete: %v", err)
	}
	got, err := os.ReadFile(filepath.Join(m.Dir(), "sub", "a.txt"))
	if err != nil || string(got) != string(data) {
		t.Fatalf("final file = %q err=%v", got, err)
	}
	if st.Final == "" {
		t.Error("final path not recorded")
	}
}

func TestNeverOverwrite(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("x")
	first, _ := m.Offer("p", "paired", []FileReq{{RelPath: "a.txt", Size: 1}}, 1<<20)
	m.WriteChunk(first.ID, "p", "a.txt", 0, strings.NewReader("x"))
	st1, err := m.Complete(first.ID, "p", "a.txt", sha(data))
	if err != nil {
		t.Fatal(err)
	}
	second, _ := m.Offer("p", "paired", []FileReq{{RelPath: "a.txt", Size: 1}}, 1<<20)
	m.WriteChunk(second.ID, "p", "a.txt", 0, strings.NewReader("x"))
	st2, err := m.Complete(second.ID, "p", "a.txt", sha(data))
	if err != nil {
		t.Fatal(err)
	}
	if st2.Final == st1.Final {
		t.Fatal("second push overwrote the first")
	}
	if _, err := os.Stat(st1.Final); err != nil {
		t.Error("first file should still exist")
	}
}

func TestChecksumMismatch(t *testing.T) {
	m := New(t.TempDir(), nil)
	p, _ := m.Offer("p", "paired", []FileReq{{RelPath: "a.txt", Size: 3}}, 1<<20)
	m.WriteChunk(p.ID, "p", "a.txt", 0, strings.NewReader("abc"))
	if _, err := m.Complete(p.ID, "p", "a.txt", strings.Repeat("0", 64)); err == nil {
		t.Fatal("bad checksum should be refused")
	}
	if _, err := os.Stat(filepath.Join(m.Dir(), "a.txt")); err == nil {
		t.Fatal("file must not be finalized after a checksum mismatch")
	}
}

func TestRejectsEscapeAndLimit(t *testing.T) {
	m := New(t.TempDir(), nil)
	if _, err := m.Offer("p", "paired", []FileReq{{RelPath: "../evil", Size: 1}}, 1<<20); err == nil {
		t.Error("traversal must be rejected")
	}
	if _, err := m.Offer("p", "paired", []FileReq{{RelPath: "big", Size: 100}}, 10); err == nil {
		t.Error("push over the limit must be rejected")
	}
}

// Receive is the one-request path for small files: bytes and digest arrive
// together and the file is finalized without a second read from disk.
func TestReceiveWholeFile(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("small file body")
	mt := time.Date(2025, 1, 2, 3, 4, 5, 0, time.UTC)
	p, err := m.Offer("p", "paired", []FileReq{{RelPath: "d/x.txt", Size: int64(len(data)), MTime: mt}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	st, err := m.Receive(p.ID, "p", "d/x.txt", sha(data), strings.NewReader(string(data)))
	if err != nil {
		t.Fatalf("Receive: %v", err)
	}
	got, err := os.ReadFile(filepath.Join(m.Dir(), "d", "x.txt"))
	if err != nil || string(got) != string(data) {
		t.Fatalf("final file = %q err=%v", got, err)
	}
	if fi, _ := os.Stat(st.Final); !fi.ModTime().Equal(mt) {
		t.Errorf("modification time %v not preserved (want %v)", fi.ModTime(), mt)
	}
	if _, err := os.Stat(st.Part); err == nil {
		t.Error("the .lanpart file should be gone")
	}
}

func TestReceiveRefusesBadInput(t *testing.T) {
	data := []byte("abcdef")
	cases := map[string]struct {
		body string
		sha  string
	}{
		"wrong digest": {string(data), strings.Repeat("0", 64)},
		"too short":    {"abc", sha([]byte("abc"))},
		"too long":     {string(data) + "extra", sha(append(append([]byte{}, data...), "extra"...))},
	}
	for name, c := range cases {
		m := New(t.TempDir(), nil)
		p, _ := m.Offer("p", "paired", []FileReq{{RelPath: "a.bin", Size: int64(len(data))}}, 0)
		if _, err := m.Receive(p.ID, "p", "a.bin", c.sha, strings.NewReader(c.body)); err == nil {
			t.Errorf("%s: should be refused", name)
		}
		if _, err := os.Stat(filepath.Join(m.Dir(), "a.bin")); err == nil {
			t.Errorf("%s: a refused file must not be finalized", name)
		}
		if _, err := os.Stat(filepath.Join(m.Dir(), "a.bin.lanpart")); err == nil {
			t.Errorf("%s: a refused file must not leave a partial behind", name)
		}
	}
	m := New(t.TempDir(), nil)
	p, _ := m.Offer("p", "paired", []FileReq{{RelPath: "a.bin", Size: 1}}, 0)
	if _, err := m.Receive(p.ID, "someone-else", "a.bin", sha([]byte("x")), strings.NewReader("x")); err == nil {
		t.Error("another peer must not write into this push")
	}
	if _, err := m.Receive(p.ID, "p", "unlisted.bin", sha([]byte("x")), strings.NewReader("x")); err == nil {
		t.Error("a file that was not offered must be refused")
	}
}

// With no limit configured there is no hidden cap: a paired device that was
// allowed to push can send more than 2 GiB (free space is still checked).
func TestNoHiddenSizeCap(t *testing.T) {
	m := New(t.TempDir(), nil)
	free := FreeSpace(m.Dir())
	size := int64(3) << 30
	if free > 0 && free < size+(1<<30) {
		t.Skip("not enough free disk space for this check")
	}
	if _, err := m.Offer("p", "paired", []FileReq{{RelPath: "big.iso", Size: size}}, 0); err != nil {
		t.Fatalf("a %d GiB push with no configured limit should be accepted: %v", size>>30, err)
	}
	if _, err := m.Offer("p", "paired", []FileReq{{RelPath: "big2.iso", Size: size}}, 1<<30); err == nil {
		t.Error("an explicit 1 GiB limit must still be enforced")
	}
}

func TestValidateSnippet(t *testing.T) {
	ok := []string{"hello", "line one\nline two", "tab\there", "a link https://example.com/x", strings.Repeat("x", MaxSnippetBytes)}
	for _, s := range ok {
		if err := ValidateSnippet(s); err != nil {
			t.Errorf("ValidateSnippet(%q) = %v, want nil", s[:min(len(s), 20)], err)
		}
	}
	bad := []string{
		"",
		string(make([]byte, 0)),
		"\x00binary",
		"bell\x07",
		"del\x7f",
		"c1\x9b",
		string([]byte{0xff, 0xfe}),
		strings.Repeat("x", MaxSnippetBytes+1),
	}
	for _, s := range bad {
		if err := ValidateSnippet(s); err == nil {
			t.Errorf("ValidateSnippet(%q) = nil, want an error", s)
		}
	}
}

func TestAddDismissSnippet(t *testing.T) {
	m := New(t.TempDir(), nil)
	var gotPeer, gotText string
	m.SetOnSnippet(func(peer, text string) { gotPeer, gotText = peer, text })

	sp, err := m.AddSnippet("peer-fp", "<b>hi</b>\nhttps://example.com")
	if err != nil {
		t.Fatalf("AddSnippet: %v", err)
	}
	if gotPeer != "peer-fp" || gotText != sp.Text {
		t.Fatalf("onSnippet got (%q,%q), want (peer-fp,%q)", gotPeer, gotText, sp.Text)
	}
	list := m.Snippets()
	if len(list) != 1 || list[0].ID != sp.ID {
		t.Fatalf("Snippets = %+v", list)
	}
	if list[0].Text != "<b>hi</b>\nhttps://example.com" {
		t.Fatalf("markup must be stored as plain text, got %q", list[0].Text)
	}
	if !m.DismissSnippet(sp.ID) {
		t.Fatal("DismissSnippet should find the snippet")
	}
	if len(m.Snippets()) != 0 {
		t.Fatal("snippet should be gone after dismiss")
	}
	if m.DismissSnippet("nope") {
		t.Fatal("dismissing an unknown snippet must fail")
	}
}

func TestFreeSpaceUsesNearestExistingParent(t *testing.T) {
	base := t.TempDir()
	missing := filepath.Join(base, "Inbox", "nested")
	if got := FreeSpace(missing); got <= 0 {
		t.Fatalf("FreeSpace(%q) = %d, want > 0 (measure the existing parent)", missing, got)
	}
}

func TestNearestExistingDir(t *testing.T) {
	base := t.TempDir()
	if got := nearestExistingDir(filepath.Join(base, "a", "b")); got != base {
		t.Fatalf("nearestExistingDir(nonexistent) = %q, want %q", got, base)
	}
	if got := nearestExistingDir(base); got != base {
		t.Fatalf("nearestExistingDir(existing) = %q, want %q", got, base)
	}
	if got := nearestExistingDir(""); got != "" {
		t.Fatalf("nearestExistingDir(\"\") = %q, want \"\"", got)
	}
}
