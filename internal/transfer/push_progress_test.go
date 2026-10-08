package transfer

import (
	"bytes"
	"errors"
	"io"
	"testing"
	"time"

	"lanyard/internal/trust"
)

// newPushProgressRig pairs the sender so the push goes through without a
// prompt, and starts from a known empty destination.
func newPushProgressRig(t *testing.T) *pushRig {
	t.Helper()
	r := newPushRig(t)
	r.trR.Pair(trust.Entry{
		DeviceID: "sender-dev", Name: "Sender PC", Fingerprint: r.sendID.DeviceID, Mode: trust.ModePair,
		Permissions: trust.Permissions{Browse: true, Push: true},
	})
	return r
}

// TestPushProgressAdvancesMidFile: the sender's view must show bytes advancing
// while a large file is still uploading, not sit at 0 and jump to total only
// once the file has been verified.
func TestPushProgressAdvancesMidFile(t *testing.T) {
	r := newPushProgressRig(t)
	r.mgr.SetBandwidthLimit(1) // 1 MB/s so the upload is visibly slow
	const size = 3 << 20
	data := randBytes(31, size)
	v := r.push(t, writeTemp(t, "slow.bin", data))

	deadline := time.Now().Add(30 * time.Second)
	seen := map[int64]bool{}
	for {
		got, _ := r.mgr.Get(v.ID)
		if got == nil {
			t.Fatal("job disappeared")
		}
		if got.Done > 0 && got.Done < size {
			seen[got.Done] = true
		}
		if got.State == StateDone {
			break
		}
		if got.State == StateFailed {
			t.Fatalf("push failed: %s", got.Error)
		}
		if time.Now().After(deadline) {
			t.Fatalf("push never finished: state=%q done=%d", got.State, got.Done)
		}
		time.Sleep(15 * time.Millisecond)
	}
	if len(seen) < 2 {
		t.Errorf("sender progress never advanced mid-file: saw %d intermediate values %v", len(seen), seen)
	}
	if got := inboxFiles(t, r.inboxDir); got["slow.bin"] != sum(data) {
		t.Fatal("uploaded file differs from the source")
	}
}

// TestPushCountingReaderResumeDoesNotDoubleCount: after a failed attempt that
// recorded 400 bytes, the receiver re-offers offset 250. The new attempt must
// report 250 + bytes_sent, never 400 + bytes_sent.
func TestPushCountingReaderResumeDoesNotDoubleCount(t *testing.T) {
	m := &Manager{onChange: func() {}}
	const size int64 = 1000
	const offset int64 = 250

	newJob := func() (*Job, *FileJob) {
		f := &FileJob{Rel: "x.bin", Size: size, State: FilePartial, Done: 400}
		return &Job{ID: "j_test", Direction: "push", Files: []*FileJob{f}, Total: size, Done: 400}, f
	}

	// As pushOne does before streaming: publish the receiver's real offset,
	// which rewinds the stale 400 recorded by the failed attempt.
	job, f := newJob()
	job.mu.Lock()
	setFileDone(job, f, offset)
	job.mu.Unlock()
	if f.Done != offset || job.Done != offset {
		t.Fatalf("after prefix publish: file=%d job=%d, want %d", f.Done, job.Done, offset)
	}

	// Half the remainder goes out: 250 + 300, not 400 + 300.
	cr := &pushCountingReader{m: m, job: job, f: f, base: offset,
		r: io.LimitReader(bytes.NewReader(make([]byte, size-offset)), 300)}
	if _, err := io.Copy(io.Discard, cr); err != nil {
		t.Fatal(err)
	}
	if want := offset + 300; f.Done != want || job.Done != want {
		t.Fatalf("after 300 sent: file=%d job=%d, want %d (double counting the prefix?)", f.Done, job.Done, want)
	}

	// A complete attempt over a fresh stale job reaches exactly the file size.
	job, f = newJob()
	job.mu.Lock()
	setFileDone(job, f, offset)
	job.mu.Unlock()
	cr = &pushCountingReader{m: m, job: job, f: f, base: offset, r: bytes.NewReader(make([]byte, size-offset))}
	if _, err := io.Copy(io.Discard, cr); err != nil {
		t.Fatal(err)
	}
	if f.Done != size || job.Done != size {
		t.Fatalf("full attempt: file=%d job=%d, want %d", f.Done, job.Done, size)
	}
}

// failAfterReader yields its data and then fails, standing in for a connection
// dropped mid-upload.
type failAfterReader struct {
	data []byte
	off  int
}

func (f *failAfterReader) Read(p []byte) (int, error) {
	if f.off >= len(f.data) {
		return 0, errors.New("connection dropped")
	}
	n := copy(p, f.data[f.off:])
	f.off += n
	return n, nil
}

// TestPushCountingReaderFailedAttemptNotInflated: a failed upload must leave a
// count that reflects the bytes actually sent, not the whole file and not a
// doubled figure.
func TestPushCountingReaderFailedAttemptNotInflated(t *testing.T) {
	m := &Manager{onChange: func() {}}
	const size = 4096
	f := &FileJob{Rel: "x.bin", Size: size, State: FilePartial}
	job := &Job{ID: "j_test", Direction: "push", Files: []*FileJob{f}, Total: size}
	body := &failAfterReader{data: make([]byte, 200)}
	cr := &pushCountingReader{m: m, job: job, f: f, r: body}
	if _, err := io.Copy(io.Discard, cr); err == nil {
		t.Fatal("expected the upload to fail")
	}
	if f.Done != 200 || job.Done != 200 {
		t.Fatalf("failed attempt left file=%d job=%d, want 200", f.Done, job.Done)
	}
	if job.Done > size {
		t.Fatalf("failed attempt inflated the count to %d (> %d)", job.Done, size)
	}
}
