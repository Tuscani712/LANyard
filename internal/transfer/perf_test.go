package transfer

import (
	"context"
	"encoding/json"
	"fmt"
	"math/rand"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"sync"
	"testing"
	"time"

	"lanyard/internal/shares"
	"lanyard/internal/trust"
)

// perfTree creates (once, then reuses) a folder of n small files in 200 sub
// folders, so repeated runs do not pay the creation cost again.
func perfTree(t *testing.T, n int) string {
	t.Helper()
	dir := filepath.Join(os.TempDir(), fmt.Sprintf("lanyard-perf-%d", n))
	marker := filepath.Join(dir, ".complete")
	if _, err := os.Stat(marker); err == nil {
		return dir
	}
	start := time.Now()
	_ = os.RemoveAll(dir)
	per := n / 200
	var wg sync.WaitGroup
	sem := make(chan struct{}, 16)
	for d := 0; d < 200; d++ {
		wg.Add(1)
		sem <- struct{}{}
		go func(d int) {
			defer wg.Done()
			defer func() { <-sem }()
			sub := filepath.Join(dir, fmt.Sprintf("d%03d", d))
			if err := os.MkdirAll(sub, 0o755); err != nil {
				t.Error(err)
				return
			}
			r := rand.New(rand.NewSource(int64(d)))
			for i := 0; i < per; i++ {
				b := make([]byte, 100+r.Intn(900))
				r.Read(b)
				if err := os.WriteFile(filepath.Join(sub, fmt.Sprintf("f%05d.dat", i)), b, 0o644); err != nil {
					t.Error(err)
					return
				}
			}
		}(d)
	}
	wg.Wait()
	if err := os.WriteFile(marker, []byte("ok"), 0o644); err != nil {
		t.Fatal(err)
	}
	t.Logf("created %d files in %v", n, time.Since(start).Round(time.Millisecond))
	return dir
}

func heapMB() float64 {
	var m runtime.MemStats
	runtime.ReadMemStats(&m)
	return float64(m.HeapAlloc) / (1 << 20)
}

// TestPerfManyTinyFiles measures a folder download of many small files:
// manifest cost, job creation, the size/cost of what the UI receives, how
// expensive saving the job list is, and the end-to-end rate. It is skipped
// unless LANYARD_PERF=1 (LANYARD_PERF_FILES overrides the default 200000).
func TestPerfManyTinyFiles(t *testing.T) {
	if os.Getenv("LANYARD_PERF") != "1" {
		t.Skip("set LANYARD_PERF=1 to run")
	}
	n := 200000
	if v, err := strconv.Atoi(os.Getenv("LANYARD_PERF_FILES")); err == nil && v >= 200 {
		n = v
	}
	src := perfTree(t, n)
	e := newEnv(t, nil)
	sh, err := e.shMgr.Add(src, shares.AddOptions{LifetimeType: shares.LifetimeUntilStopped, Label: "perf"})
	if err != nil {
		t.Fatal(err)
	}

	t0 := time.Now()
	man, err := e.client.GetManifest(context.Background(), "127.0.0.1", e.proxy.Port(), e.peerFP, sh.ShareID, "")
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("manifest: %d files in %v", len(man.Files), time.Since(t0).Round(time.Millisecond))

	dest := t.TempDir()
	t0 = time.Now()
	v, err := e.createFor(sh, dest)
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("Create (manifest + plan): %v, heap %.0f MB", time.Since(t0).Round(time.Millisecond), heapMB())

	var maxSSE, maxPersist time.Duration
	var maxSSEBytes int
	start := time.Now()
	deadline := time.Now().Add(45 * time.Minute)
	last := time.Now()
	for {
		t1 := time.Now()
		b, _ := json.Marshal(e.mgr.List())
		if d := time.Since(t1); d > maxSSE {
			maxSSE = d
		}
		if len(b) > maxSSEBytes {
			maxSSEBytes = len(b)
		}
		t1 = time.Now()
		e.mgr.persist()
		if d := time.Since(t1); d > maxPersist {
			maxPersist = d
		}
		cur, _ := e.mgr.Get(v.ID)
		if time.Since(last) > 20*time.Second {
			t.Logf("  %s: %d/%d files, heap %.0f MB", time.Since(start).Round(time.Second), cur.FilesDone, cur.FilesTotal, heapMB())
			last = time.Now()
		}
		if cur.State == StateDone || cur.State == StateFailed {
			if cur.State == StateFailed {
				t.Fatalf("job failed: %s", cur.Error)
			}
			break
		}
		if time.Now().After(deadline) {
			t.Fatalf("not finished after %v: %d files done", time.Since(start), cur.FilesDone)
		}
		time.Sleep(500 * time.Millisecond)
	}
	el := time.Since(start)
	t.Logf("RESULT: %d files in %v = %.0f files/s", n, el.Round(time.Second), float64(n)/el.Seconds())
	t.Logf("UI payload (one SSE 'transfers' event): up to %.1f MB, marshal up to %v", float64(maxSSEBytes)/(1<<20), maxSSE.Round(time.Millisecond))
	t.Logf("persist() (save job list): up to %v; heap at end %.0f MB", maxPersist.Round(time.Millisecond), heapMB())
}

// TestPerfBigFile moves one very large file with a pause and resume in the
// middle, so offsets beyond 4 GB, the prefix re-hash and the final SHA-256 over
// the whole file are all exercised. Skipped unless LANYARD_PERF_BIG_GB is set
// (for example 50). The source is mostly zeros with random blocks sprinkled in
// at fixed offsets, which are compared at the end.
func TestPerfBigFile(t *testing.T) {
	gb, _ := strconv.Atoi(os.Getenv("LANYARD_PERF_BIG_GB"))
	if gb <= 0 {
		t.Skip("set LANYARD_PERF_BIG_GB=50 to run")
	}
	size := int64(gb) << 30
	src := filepath.Join(os.TempDir(), fmt.Sprintf("lanyard-perf-big-%d", gb))
	srcFile := filepath.Join(src, "big.bin")
	if fi, err := os.Stat(srcFile); err != nil || fi.Size() != size {
		must := func(err error) {
			if err != nil {
				t.Fatal(err)
			}
		}
		must(os.MkdirAll(src, 0o755))
		f, err := os.Create(srcFile)
		must(err)
		must(f.Truncate(size))
		for i, off := range []int64{0, 5 << 30, size / 2, size - (1 << 20)} {
			b := make([]byte, 1<<20)
			rand.New(rand.NewSource(int64(i) + 99)).Read(b)
			_, err := f.WriteAt(b, off)
			must(err)
		}
		must(f.Close())
		t.Logf("created a %d GB source file", gb)
	}

	e := newEnv(t, nil)
	sh, err := e.shMgr.Add(src, shares.AddOptions{LifetimeType: shares.LifetimeUntilStopped, Label: "big"})
	if err != nil {
		t.Fatal(err)
	}
	dest := t.TempDir()
	start := time.Now()
	v, err := e.createFor(sh, dest)
	if err != nil {
		t.Fatal(err)
	}
	waitDone(t, e.mgr, v.ID, size/6, 30*time.Minute)
	pauseAt := time.Now()
	if err := e.mgr.Pause(v.ID); err != nil {
		t.Fatal(err)
	}
	waitIdle(t, e.mgr, v.ID)
	part, _ := os.Stat(filepath.Join(dest, "big.bin.lanpart"))
	t.Logf("paused after %v with %.1f GB on disk (%.0f MB/s so far), heap %.0f MB",
		time.Since(start).Round(time.Second), float64(part.Size())/(1<<30), float64(part.Size())/(1<<20)/time.Since(start).Seconds(), heapMB())

	resumeAt := time.Now()
	if err := e.mgr.Resume(v.ID); err != nil {
		t.Fatal(err)
	}
	var peak float64
	deadline := time.Now().Add(60 * time.Minute)
	for {
		cur, _ := e.mgr.Get(v.ID)
		if h := heapMB(); h > peak {
			peak = h
		}
		if cur.State == StateDone {
			break
		}
		if cur.State == StateFailed {
			t.Fatalf("failed: %s", cur.Error)
		}
		if time.Now().After(deadline) {
			t.Fatalf("not done: %d/%d bytes", cur.Done, cur.Total)
		}
		time.Sleep(time.Second)
	}
	t.Logf("resumed part took %v, whole job %v, peak heap %.0f MB; paused for %v",
		time.Since(resumeAt).Round(time.Second), time.Since(start).Round(time.Second), peak, resumeAt.Sub(pauseAt).Round(time.Millisecond))

	final := filepath.Join(dest, "big.bin")
	fi, err := os.Stat(final)
	if err != nil || fi.Size() != size {
		t.Fatalf("final file wrong: %v size=%v", err, fi)
	}
	in, _ := os.Open(srcFile)
	out, _ := os.Open(final)
	defer in.Close()
	defer out.Close()
	for _, off := range []int64{0, 5 << 30, size / 2, size - (1 << 20)} {
		a, b := make([]byte, 1<<20), make([]byte, 1<<20)
		in.ReadAt(a, off)
		out.ReadAt(b, off)
		if string(a) != string(b) {
			t.Fatalf("content differs at offset %d", off)
		}
	}
	t.Logf("RESULT: %d GB verified end to end (server digest) and spot-checked", gb)
}

// TestPerfPushManyTinyFiles measures pushing a folder of many small files into
// a peer's Inbox (skipped unless LANYARD_PERF=1; LANYARD_PERF_FILES sets the count).
func TestPerfPushManyTinyFiles(t *testing.T) {
	if os.Getenv("LANYARD_PERF") != "1" {
		t.Skip("set LANYARD_PERF=1 to run")
	}
	n := 20000
	if v, err := strconv.Atoi(os.Getenv("LANYARD_PERF_FILES")); err == nil && v >= 200 {
		n = v
	}
	src := perfTree(t, n)
	r := newPushRig(t)
	r.trR.Pair(trust.Entry{
		DeviceID: "sender-dev", Name: "Sender PC", Fingerprint: r.sendID.DeviceID, Mode: trust.ModePair,
		Permissions: trust.Permissions{Browse: true, Push: true},
	})

	start := time.Now()
	v, err := r.mgr.Push(context.Background(), PushParams{
		PeerID: r.recvID.DeviceID, PeerName: "Receiver", Host: r.host, Port: r.port, Paths: []string{src},
	})
	if err != nil {
		t.Fatal(err)
	}
	t.Logf("plan (walk %d files): %v", n, time.Since(start).Round(time.Millisecond))
	last := time.Now()
	deadline := time.Now().Add(40 * time.Minute)
	var maxList time.Duration
	var maxBytes int
	for {
		t1 := time.Now()
		b, _ := json.Marshal(r.mgr.List())
		if d := time.Since(t1); d > maxList {
			maxList = d
		}
		if len(b) > maxBytes {
			maxBytes = len(b)
		}
		cur, _ := r.mgr.Get(v.ID)
		if time.Since(last) > 20*time.Second {
			t.Logf("  %s: %d/%d files, heap %.0f MB", time.Since(start).Round(time.Second), cur.FilesDone, cur.FilesTotal, heapMB())
			last = time.Now()
		}
		if cur.State == StateDone {
			break
		}
		if cur.State == StateFailed {
			t.Fatalf("push failed: %s", cur.Error)
		}
		if time.Now().After(deadline) {
			t.Fatalf("not finished: %d files", cur.FilesDone)
		}
		time.Sleep(500 * time.Millisecond)
	}
	el := time.Since(start)
	t.Logf("RESULT: pushed %d files in %v = %.0f files/s; UI payload up to %.2f MB, heap %.0f MB",
		n, el.Round(time.Second), float64(n)/el.Seconds(), float64(maxBytes)/(1<<20), heapMB())
	got := 0
	_ = filepath.WalkDir(r.inboxDir, func(p string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() && d.Name() != ".complete" && !strings.HasSuffix(p, ".lanpart") && !strings.HasSuffix(p, ".lanstate") {
			got++
		}
		return nil
	})
	if got != n {
		t.Fatalf("inbox holds %d files, want %d", got, n)
	}
}
