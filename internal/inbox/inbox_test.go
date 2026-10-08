package inbox

import (
	"bytes"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"sync/atomic"
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
	st, _, err := m.Complete(p.ID, "peer", "sub/a.txt", sha(data))
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

// A repeated complete for a file already verified and placed must be an
// idempotent success: it returns the same final path and never places the file
// a second time (no "a (1).txt").
func TestCompleteIsIdempotent(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("hello push")
	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "a.txt", Size: int64(len(data))}}, 0)
	if err != nil {
		t.Fatalf("Offer: %v", err)
	}
	if _, err := m.WriteChunk(p.ID, "peer", "a.txt", 0, strings.NewReader(string(data))); err != nil {
		t.Fatalf("WriteChunk: %v", err)
	}
	st1, again1, err := m.Complete(p.ID, "peer", "a.txt", sha(data))
	if err != nil || again1 {
		t.Fatalf("first Complete: st=%+v again=%v err=%v", st1, again1, err)
	}
	st2, again2, err := m.Complete(p.ID, "peer", "a.txt", sha(data))
	if err != nil {
		t.Fatalf("repeated Complete must succeed: %v", err)
	}
	if !again2 {
		t.Error("repeated Complete should report the file as already placed")
	}
	if st2.Final != st1.Final {
		t.Errorf("repeated Complete final = %q, want the original %q", st2.Final, st1.Final)
	}
	entries, err := os.ReadDir(m.Dir())
	if err != nil {
		t.Fatal(err)
	}
	if len(entries) != 1 || entries[0].Name() != "a.txt" {
		var names []string
		for _, e := range entries {
			names = append(names, e.Name())
		}
		t.Fatalf("a duplicate complete placed the file twice: %v", names)
	}
}

// The whole-file fast path must likewise be replay-safe: a retried request for
// an already-placed file drains its body and returns the same result.
func TestReceiveIsIdempotent(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("small file body")
	p, err := m.Offer("p", "paired", []FileReq{{RelPath: "x.txt", Size: int64(len(data))}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	st1, again1, err := m.Receive(p.ID, "p", "x.txt", sha(data), strings.NewReader(string(data)))
	if err != nil || again1 {
		t.Fatalf("first Receive: st=%+v again=%v err=%v", st1, again1, err)
	}
	st2, again2, err := m.Receive(p.ID, "p", "x.txt", sha(data), strings.NewReader(string(data)))
	if err != nil {
		t.Fatalf("repeated Receive must succeed: %v", err)
	}
	if !again2 {
		t.Error("repeated Receive should report the file as already placed")
	}
	if st2.Final != st1.Final {
		t.Errorf("repeated Receive final = %q, want the original %q", st2.Final, st1.Final)
	}
	entries, _ := os.ReadDir(m.Dir())
	if len(entries) != 1 || entries[0].Name() != "x.txt" {
		t.Fatalf("a duplicate whole-file receive placed the file twice: %v", entries)
	}
}

// Two simultaneous completes for the same file must place it exactly once: the
// per-file lock serializes the finalize and the replay then reports "already".
func TestConcurrentCompletePlacesOnce(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("concurrent body")
	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "a.txt", Size: int64(len(data))}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.WriteChunk(p.ID, "peer", "a.txt", 0, strings.NewReader(string(data))); err != nil {
		t.Fatal(err)
	}
	const n = 8
	var wg sync.WaitGroup
	alreadies := make([]bool, n)
	errs := make([]error, n)
	for i := 0; i < n; i++ {
		i := i
		wg.Add(1)
		go func() {
			defer wg.Done()
			_, alreadies[i], errs[i] = m.Complete(p.ID, "peer", "a.txt", sha(data))
		}()
	}
	wg.Wait()
	firsts := 0
	for i := 0; i < n; i++ {
		if errs[i] != nil {
			t.Fatalf("concurrent Complete #%d: %v", i, errs[i])
		}
		if !alreadies[i] {
			firsts++
		}
	}
	if firsts != 1 {
		t.Errorf("exactly one complete should do the work, got %d", firsts)
	}
	entries, _ := os.ReadDir(m.Dir())
	if len(entries) != 1 {
		t.Fatalf("concurrent completes placed the file %d times", len(entries))
	}
}

func TestNeverOverwrite(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("x")
	first, _ := m.Offer("p", "paired", []FileReq{{RelPath: "a.txt", Size: 1}}, 1<<20)
	m.WriteChunk(first.ID, "p", "a.txt", 0, strings.NewReader("x"))
	st1, _, err := m.Complete(first.ID, "p", "a.txt", sha(data))
	if err != nil {
		t.Fatal(err)
	}
	second, _ := m.Offer("p", "paired", []FileReq{{RelPath: "a.txt", Size: 1}}, 1<<20)
	m.WriteChunk(second.ID, "p", "a.txt", 0, strings.NewReader("x"))
	st2, _, err := m.Complete(second.ID, "p", "a.txt", sha(data))
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
	if _, _, err := m.Complete(p.ID, "p", "a.txt", strings.Repeat("0", 64)); err == nil {
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
	st, _, err := m.Receive(p.ID, "p", "d/x.txt", sha(data), strings.NewReader(string(data)))
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
		if _, _, err := m.Receive(p.ID, "p", "a.bin", c.sha, strings.NewReader(c.body)); err == nil {
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
	if _, _, err := m.Receive(p.ID, "someone-else", "a.bin", sha([]byte("x")), strings.NewReader("x")); err == nil {
		t.Error("another peer must not write into this push")
	}
	if _, _, err := m.Receive(p.ID, "p", "unlisted.bin", sha([]byte("x")), strings.NewReader("x")); err == nil {
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

// Finishing a push reports each file that landed, so the caller can record a
// receive entry in the transfer history.
func TestFinishReportsReceivedFiles(t *testing.T) {
	m := New(t.TempDir(), nil)
	var gotPeer string
	var gotFiles []ReceivedFile
	m.SetOnDone(func(peerFP string, files []ReceivedFile) { gotPeer, gotFiles = peerFP, files })

	data := []byte("hello push")
	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "sub/a.txt", Size: int64(len(data)), MTime: time.Now()}}, 0)
	if err != nil {
		t.Fatalf("Offer: %v", err)
	}
	if _, _, err := m.Receive(p.ID, "peer", "sub/a.txt", sha(data), strings.NewReader(string(data))); err != nil {
		t.Fatalf("Receive: %v", err)
	}
	if !m.Finish(p.ID, "peer") {
		t.Fatal("Finish returned false")
	}
	if gotPeer != "peer" {
		t.Fatalf("peer = %q", gotPeer)
	}
	if len(gotFiles) != 1 {
		t.Fatalf("files = %+v", gotFiles)
	}
	f := gotFiles[0]
	if f.Rel != "sub/a.txt" || f.Name != "a.txt" || f.Size != int64(len(data)) {
		t.Fatalf("file = %+v", f)
	}
}

// A push whose bytes have all arrived but whose files are not finalized yet
// must report Finishing, and keep doing so until Finish removes it. A body
// still arriving must not be marked Finishing.
func TestIncomingMarksFinishingWhenAllBytesArrived(t *testing.T) {
	m := New(t.TempDir(), nil)
	a, b := []byte("aaaa"), []byte("bbb")
	p, err := m.Offer("peer", "paired", []FileReq{
		{RelPath: "a.txt", Size: int64(len(a))},
		{RelPath: "b.txt", Size: int64(len(b))},
	}, 0)
	if err != nil {
		t.Fatalf("Offer: %v", err)
	}
	view := func() IncomingView {
		got := m.Incoming()
		if len(got) != 1 {
			t.Fatalf("Incoming = %+v, want one push", got)
		}
		return got[0]
	}
	if view().Finishing {
		t.Fatal("a push with no bytes yet must not be Finishing")
	}
	if _, err := m.WriteChunk(p.ID, "peer", "a.txt", 0, strings.NewReader(string(a))); err != nil {
		t.Fatalf("WriteChunk a: %v", err)
	}
	// b has not arrived, so the push is not finishing yet even though one file
	// is fully received.
	if view().Finishing {
		t.Fatal("Finishing must wait for every file's bytes")
	}
	if _, err := m.WriteChunk(p.ID, "peer", "b.txt", 0, strings.NewReader(string(b))); err != nil {
		t.Fatalf("WriteChunk b: %v", err)
	}
	if !view().Finishing {
		t.Fatal("all bytes arrived; the push must report Finishing")
	}
	// Finalizing one file leaves the push finishing until Finish.
	if _, _, err := m.Complete(p.ID, "peer", "a.txt", sha(a)); err != nil {
		t.Fatalf("Complete a: %v", err)
	}
	if !view().Finishing {
		t.Fatal("a partially finalized push must stay Finishing")
	}
	if _, _, err := m.Complete(p.ID, "peer", "b.txt", sha(b)); err != nil {
		t.Fatalf("Complete b: %v", err)
	}
	if !view().Finishing {
		t.Fatal("a fully finalized push awaiting Finish must still be Finishing")
	}
	if !m.Finish(p.ID, "peer") {
		t.Fatal("Finish returned false")
	}
	if got := m.Incoming(); len(got) != 0 {
		t.Fatalf("Finishing must clear when the push finishes: %+v", got)
	}
}

// The whole-file fast path finalizes inside Receive; the row must still report
// Finishing until the sender's Finish removes it.
func TestIncomingFinishingAfterWholeFileReceive(t *testing.T) {
	m := New(t.TempDir(), nil)
	data := []byte("one shot")
	p, err := m.Offer("p", "paired", []FileReq{{RelPath: "x.txt", Size: int64(len(data))}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, _, err := m.Receive(p.ID, "p", "x.txt", sha(data), strings.NewReader(string(data))); err != nil {
		t.Fatalf("Receive: %v", err)
	}
	got := m.Incoming()
	if len(got) != 1 || !got[0].Finishing {
		t.Fatalf("after whole-file Receive = %+v, want one Finishing push", got)
	}
	if !m.Finish(p.ID, "p") {
		t.Fatal("Finish returned false")
	}
	if got := m.Incoming(); len(got) != 0 {
		t.Fatalf("Finishing must clear on Finish: %+v", got)
	}
}

// Cancelling a push fires onCancel with the files that already landed, so the
// caller can record a Cancelled history entry.
func TestCancelReportsReceivedFiles(t *testing.T) {
	m := New(t.TempDir(), nil)
	var gotPeer string
	var gotFiles []ReceivedFile
	var gotStarted time.Time
	m.SetOnCancel(func(peerFP string, files []ReceivedFile, started time.Time) {
		gotPeer, gotFiles, gotStarted = peerFP, files, started
	})

	data := []byte("kept bytes")
	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "sub/a.txt", Size: int64(len(data)), MTime: time.Now()}}, 0)
	if err != nil {
		t.Fatalf("Offer: %v", err)
	}
	if _, _, err := m.Receive(p.ID, "peer", "sub/a.txt", sha(data), strings.NewReader(string(data))); err != nil {
		t.Fatalf("Receive: %v", err)
	}
	if !m.Cancel(p.ID) {
		t.Fatal("Cancel returned false")
	}
	if gotPeer != "peer" {
		t.Fatalf("peer = %q", gotPeer)
	}
	if len(gotFiles) != 1 || gotFiles[0].Name != "a.txt" || gotFiles[0].Size != int64(len(data)) {
		t.Fatalf("files = %+v", gotFiles)
	}
	if gotStarted.IsZero() {
		t.Fatal("start time was not reported")
	}
}

// waitFor polls cond until it is true or the deadline passes.
func waitFor(cond func() bool, within time.Duration) bool {
	deadline := time.Now().Add(within)
	for {
		if cond() {
			return true
		}
		if time.Now().After(deadline) {
			return false
		}
		time.Sleep(2 * time.Millisecond)
	}
}

// chunkReader hands out fixed-size chunks slowly, so bytes can still be
// arriving while the change callback is observed.
type chunkReader struct {
	remaining int
	delay     time.Duration
	reads     atomic.Int32
}

func (r *chunkReader) Read(b []byte) (int, error) {
	time.Sleep(r.delay)
	r.reads.Add(1)
	n := len(b)
	if n > r.remaining {
		n = r.remaining
	}
	for i := 0; i < n; i++ {
		b[i] = 'x'
	}
	r.remaining -= n
	return n, nil
}

// errReader always fails, standing in for a connection that dropped.
type errReader struct{ err error }

func (r errReader) Read([]byte) (int, error) { return 0, r.err }

// failAfterReader hands out data first, then fails, standing in for a
// connection that drops part-way through a file.
type failAfterReader struct {
	data []byte
	err  error
}

func (r *failAfterReader) Read(b []byte) (int, error) {
	if len(r.data) == 0 {
		return 0, r.err
	}
	n := copy(b, r.data)
	r.data = r.data[n:]
	return n, nil
}

// stallReader blocks until unblocked, then fails: a body that is in flight but
// making no progress.
type stallReader struct{ unblock <-chan struct{} }

func (r stallReader) Read([]byte) (int, error) {
	<-r.unblock
	return 0, errors.New("unblocked")
}

// Offering a push fires onChange at once, and bytes arriving fire it again
// while the copy is still running, throttled to well under one call per read.
func TestOnChangeFiresDuringCopyThrottled(t *testing.T) {
	var calls atomic.Int32
	m := New(t.TempDir(), func() { calls.Add(1) })
	defer m.Close()
	m.progressEvery = 15 * time.Millisecond

	const size = 4 << 20
	start := time.Now()
	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "big.bin", Size: size}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if got := calls.Load(); got != 1 {
		t.Fatalf("Offer fired onChange %d times, want 1", got)
	}

	r := &chunkReader{remaining: size, delay: 5 * time.Millisecond}
	done := make(chan error, 1)
	go func() {
		_, err := m.WriteChunk(p.ID, "peer", "big.bin", 0, r)
		done <- err
	}()

	if !waitFor(func() bool { return calls.Load() >= 2 }, 2*time.Second) {
		t.Fatal("onChange did not fire while bytes were still arriving")
	}
	select {
	case err := <-done:
		t.Fatalf("copy finished before progress could be observed: %v", err)
	default:
	}
	if err := <-done; err != nil {
		t.Fatalf("WriteChunk: %v", err)
	}
	elapsed := time.Since(start)

	total, reads := calls.Load(), r.reads.Load()
	if total < 2 {
		t.Fatalf("onChange fired %d times, want offer + at least one mid-copy tick", total)
	}
	if int(total) >= int(reads) {
		t.Fatalf("onChange is not throttled: %d calls for %d reads", total, reads)
	}
	// At most one onChange per throttle interval, plus the offer and the final
	// completion notification.
	if max := int(elapsed/m.progressEvery) + 3; int(total) > max {
		t.Fatalf("onChange fired %d times in %v, want at most %d (throttle %v)", total, elapsed, max, m.progressEvery)
	}
}

// An in-flight body that stops producing bytes is removed by the reaper after
// the stall timeout and reported through onFail; it must not stay listed in
// Incoming(). Its .lanpart is kept so a re-offer can resume.
func TestReaperRemovesStalledPush(t *testing.T) {
	dir := t.TempDir()
	m := New(dir, nil)
	defer m.Close()
	m.SetStallTimeout(40 * time.Millisecond)
	failed := make(chan string, 4)
	m.SetOnFail(func(_, reason string) { failed <- reason })

	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "a.bin", Size: 1 << 20}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	unblock := make(chan struct{})
	var wg sync.WaitGroup
	wg.Add(1)
	go func() {
		defer wg.Done()
		_, _ = m.WriteChunk(p.ID, "peer", "a.bin", 0, stallReader{unblock: unblock})
	}()
	// Wait until the body is actually in flight so the reaper judges it by the
	// short stall timeout, not the long idle one.
	if !waitFor(func() bool { return p.inFlight.Load() }, time.Second) {
		t.Fatal("body never entered flight")
	}
	if !waitFor(func() bool { return m.Count() == 0 }, 2*time.Second) {
		t.Fatalf("stalled push still tracked: %+v", m.Incoming())
	}
	if got := m.Incoming(); len(got) != 0 {
		t.Fatalf("stalled push still listed: %+v", got)
	}
	if _, ok := m.Get(p.ID, "peer"); ok {
		t.Error("stalled push is still retrievable")
	}
	select {
	case reason := <-failed:
		if !strings.Contains(reason, "stall") {
			t.Errorf("onFail reason = %q, want a stall message", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("onFail was not called for the stalled push")
	}
	close(unblock)
	wg.Wait()
	if _, err := os.Stat(filepath.Join(dir, "a.bin.lanpart")); err != nil {
		t.Errorf("a stalled push should keep its .lanpart for resume: %v", err)
	}
}

// A push with no body in flight (hashing before the first byte, or between
// files) must not be reaped by the short stall timeout; it gets the much longer
// idle timeout instead. The timeouts are scaled down for the test.
func TestReaperSparesIdlePushUntilIdleTimeout(t *testing.T) {
	m := New(t.TempDir(), nil)
	defer m.Close()
	m.SetStallTimeout(40 * time.Millisecond)
	m.SetIdleTimeout(120 * time.Millisecond)
	failed := make(chan string, 4)
	m.SetOnFail(func(_, reason string) { failed <- reason })

	if _, err := m.Offer("peer", "paired", []FileReq{{RelPath: "a.txt", Size: 10}}, 0); err != nil {
		t.Fatal(err)
	}
	// No body is in flight, so the 40 ms stall timeout must not apply: past it
	// but before the 120 ms idle timeout the push must still be alive.
	time.Sleep(60 * time.Millisecond)
	if m.Count() != 1 {
		t.Fatal("an idle push was reaped by the stall timeout")
	}
	if !waitFor(func() bool { return m.Count() == 0 }, 2*time.Second) {
		t.Fatalf("idle push was not reaped after its idle timeout: %+v", m.Incoming())
	}
	select {
	case reason := <-failed:
		if !strings.Contains(reason, "stall") {
			t.Errorf("onFail reason = %q, want a stall message", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("onFail was not called for the idle push")
	}
}

// A push dropped mid-file keeps its .lanpart, and a re-offer resumes from the
// partial's size rather than restarting from zero.
func TestDroppedPushKeepsPartialAndResumes(t *testing.T) {
	dir := t.TempDir()
	m := New(dir, nil)
	defer m.Close()

	full := bytes.Repeat([]byte("abcdefgh"), 4096)
	const sent = 12 * 1024
	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "big.bin", Size: int64(len(full))}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.WriteChunk(p.ID, "peer", "big.bin", 0, &failAfterReader{data: full[:sent], err: errors.New("connection reset")}); err == nil {
		t.Fatal("WriteChunk should report the dropped connection")
	}
	if m.Count() != 0 {
		t.Fatal("a dropped push should leave the incoming list")
	}
	part := filepath.Join(dir, "big.bin.lanpart")
	if info, err := os.Stat(part); err != nil || info.Size() != sent {
		t.Fatalf("partial should be kept at %d bytes: info=%v err=%v", sent, info, err)
	}

	p2, err := m.Offer("peer", "paired", []FileReq{{RelPath: "big.bin", Size: int64(len(full))}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	offs := p2.SortedOffsets()
	if len(offs) != 1 || offs[0].Offset != sent {
		t.Fatalf("resume offsets = %+v, want one at %d", offs, sent)
	}
	n, err := m.WriteChunk(p2.ID, "peer", "big.bin", offs[0].Offset, bytes.NewReader(full[sent:]))
	if err != nil {
		t.Fatalf("resume WriteChunk: %v", err)
	}
	if n != int64(len(full)-sent) {
		t.Fatalf("resumed %d bytes, want %d", n, len(full)-sent)
	}
	if _, _, err := m.Complete(p2.ID, "peer", "big.bin", sha(full)); err != nil {
		t.Fatalf("Complete after resume: %v", err)
	}
	got, err := os.ReadFile(filepath.Join(dir, "big.bin"))
	if err != nil || !bytes.Equal(got, full) {
		t.Fatalf("final file wrong: len=%d err=%v", len(got), err)
	}
}

// A body-copy read error (a dropped connection) removes the push immediately
// and surfaces it, without waiting for the stall timeout.
func TestBodyCopyErrorRemovesPush(t *testing.T) {
	dir := t.TempDir()
	m := New(dir, nil)
	defer m.Close()
	failed := make(chan string, 4)
	m.SetOnFail(func(_, reason string) { failed <- reason })

	p, err := m.Offer("peer", "paired", []FileReq{{RelPath: "a.bin", Size: 100}}, 0)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := m.WriteChunk(p.ID, "peer", "a.bin", 0, errReader{errors.New("connection reset")}); err == nil {
		t.Fatal("WriteChunk should report the read error")
	}
	if n := m.Count(); n != 0 {
		t.Fatalf("dead push is still tracked: count=%d", n)
	}
	if got := m.Incoming(); len(got) != 0 {
		t.Fatalf("dead push is still listed: %+v", got)
	}
	// A dropped connection keeps the partial so a re-offer resumes.
	if _, err := os.Stat(filepath.Join(dir, "a.bin.lanpart")); err != nil {
		t.Errorf("a dropped push should keep its .lanpart for resume: %v", err)
	}
	select {
	case reason := <-failed:
		if !strings.Contains(reason, "connection reset") {
			t.Errorf("onFail reason = %q, want the read error", reason)
		}
	case <-time.After(time.Second):
		t.Fatal("onFail was not called for the dead push")
	}
}
