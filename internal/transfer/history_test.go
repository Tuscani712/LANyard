package transfer

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/trust"
)

func historyManager(t *testing.T) *Manager {
	t.Helper()
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	return New(cfg, nil, quiet, 3, nil)
}

func finishedCount(m *Manager) int {
	m.mu.Lock()
	defer m.mu.Unlock()
	n := 0
	for _, j := range m.jobs {
		if j.State == StateDone || j.State == StateFailed {
			n++
		}
	}
	return n
}

// The history keeps the newest 500 entries; older ones are dropped.
func TestHistoryCapTrimsOldest(t *testing.T) {
	m := historyManager(t)
	base := time.Now().Add(-time.Hour)
	const total = 502
	for i := 0; i < total; i++ {
		at := base.Add(time.Duration(i) * time.Second)
		j := &Job{ID: fmt.Sprintf("j_%03d", i), Direction: "download", State: StateDone,
			StartedAt: at, UpdatedAt: at, FinishedAt: at}
		m.jobs[j.ID] = j
	}
	m.persist()

	if got := finishedCount(m); got != historyKeep {
		t.Fatalf("history has %d entries, want %d", got, historyKeep)
	}
	m.mu.Lock()
	_, oldest := m.jobs["j_000"]
	_, oldest2 := m.jobs["j_001"]
	_, newest := m.jobs[fmt.Sprintf("j_%03d", total-1)]
	m.mu.Unlock()
	if oldest || oldest2 {
		t.Fatal("the two oldest entries should have been trimmed")
	}
	if !newest {
		t.Fatal("the newest entry must be kept")
	}
}

func TestClearHistory(t *testing.T) {
	m := historyManager(t)
	now := time.Now()
	m.jobs["done"] = &Job{ID: "done", State: StateDone, FinishedAt: now}
	m.jobs["failed"] = &Job{ID: "failed", State: StateFailed, FinishedAt: now}
	m.jobs["running"] = &Job{ID: "running", State: StateTransferring}

	if n := m.ClearHistory(); n != 2 {
		t.Fatalf("ClearHistory cleared %d, want 2", n)
	}
	m.mu.Lock()
	_, done := m.jobs["done"]
	_, failed := m.jobs["failed"]
	_, running := m.jobs["running"]
	m.mu.Unlock()
	if done || failed {
		t.Fatal("finished and failed jobs should be gone")
	}
	if !running {
		t.Fatal("an in-flight job must be kept")
	}
}

// Stopping an in-flight job keeps it as a Cancelled history entry (labelled
// like Done/Failed); stopping a terminal row removes it instead.
func TestCancelKeepsCancelledHistoryEntry(t *testing.T) {
	m := historyManager(t)
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	m.mu.Lock()
	m.jobs["live"] = &Job{ID: "live", Direction: "download", State: StateTransferring,
		cancel: cancel, wake: make(chan struct{}, 1)}
	m.mu.Unlock()

	if err := m.Cancel("live", false); err != nil {
		t.Fatalf("Cancel: %v", err)
	}
	v, ok := m.Get("live")
	if !ok {
		t.Fatal("a cancelled job must stay in the history")
	}
	if v.State != StateCancelled {
		t.Fatalf("state = %q, want %q", v.State, StateCancelled)
	}
	if v.FinishedAt.IsZero() {
		t.Fatal("a cancelled row must carry a finish time")
	}
	if ctx.Err() == nil {
		t.Fatal("Cancel must stop the running transfer")
	}
	if raw := string(m.cfg.Get().Transfers); !strings.Contains(raw, StateCancelled) {
		t.Fatalf("cancelled entry was not persisted: %s", raw)
	}

	if err := m.Cancel("live", false); err != nil {
		t.Fatalf("second Cancel: %v", err)
	}
	if _, ok := m.Get("live"); ok {
		t.Fatal("cancelling a terminal row must remove it")
	}
}

// A cancelled history entry survives a restart, unlike the old behaviour that
// dropped the job on cancel.
func TestCancelledHistorySurvivesReload(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	m := New(cfg, nil, quiet, 3, nil)
	now := time.Now()
	m.mu.Lock()
	m.jobs["c"] = &Job{ID: "c", Direction: "download", State: StateCancelled,
		Total: 100, Done: 40, StartedAt: now.Add(-time.Second), UpdatedAt: now, FinishedAt: now}
	m.mu.Unlock()
	m.persist()

	m2 := New(cfg, nil, quiet, 3, nil)
	if err := m2.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	v, ok := m2.Get("c")
	if !ok || v.State != StateCancelled {
		t.Fatalf("cancelled row did not reload: %+v ok=%v", v, ok)
	}
}

// ClearFinished removes every terminal row (Done, Failed, Cancelled) and leaves
// in-flight jobs alone.
func TestClearFinishedKeepsActiveJobs(t *testing.T) {
	m := historyManager(t)
	now := time.Now()
	m.jobs["done"] = &Job{ID: "done", State: StateDone, FinishedAt: now}
	m.jobs["failed"] = &Job{ID: "failed", State: StateFailed, FinishedAt: now}
	m.jobs["cancelled"] = &Job{ID: "cancelled", State: StateCancelled, FinishedAt: now}
	m.jobs["live"] = &Job{ID: "live", State: StateTransferring}

	if n := m.ClearFinished(); n != 3 {
		t.Fatalf("ClearFinished cleared %d, want 3", n)
	}
	for _, id := range []string{"done", "failed", "cancelled"} {
		if _, ok := m.Get(id); ok {
			t.Fatalf("%s should have been cleared", id)
		}
	}
	if _, ok := m.Get("live"); !ok {
		t.Fatal("an in-flight job must be kept")
	}
}

// CancelAll stops every unfinished job but keeps each as a Cancelled entry.
func TestCancelAllMarksCancelledHistory(t *testing.T) {
	m := historyManager(t)
	m.mu.Lock()
	m.jobs["a"] = &Job{ID: "a", State: StateTransferring}
	m.jobs["b"] = &Job{ID: "b", State: StateQueued}
	m.jobs["d"] = &Job{ID: "d", State: StateDone, FinishedAt: time.Now()}
	m.mu.Unlock()

	if n := m.CancelAll(); n != 2 {
		t.Fatalf("CancelAll = %d, want 2", n)
	}
	for _, id := range []string{"a", "b"} {
		v, ok := m.Get(id)
		if !ok || v.State != StateCancelled {
			t.Fatalf("%s not left cancelled: %+v ok=%v", id, v, ok)
		}
	}
	if v, ok := m.Get("d"); !ok || v.State != StateDone {
		t.Fatal("a finished job must be untouched by CancelAll")
	}
}

// A finished row reports the average throughput derived from its persisted
// times and byte count, so the history still shows it after a restart.
func TestHistoryRowReportsAverageSpeed(t *testing.T) {
	m := historyManager(t)
	start := time.Now().Add(-10 * time.Second)
	m.mu.Lock()
	m.jobs["d"] = &Job{ID: "d", State: StateDone,
		StartedAt: start, UpdatedAt: start, FinishedAt: start.Add(10 * time.Second),
		Total: 10_000_000, Done: 10_000_000}
	m.mu.Unlock()

	v, ok := m.Get("d")
	if !ok {
		t.Fatal("job missing")
	}
	if v.AvgSpeedMBps < 0.9 || v.AvgSpeedMBps > 1.1 {
		t.Fatalf("avg speed = %v MB/s, want ~1", v.AvgSpeedMBps)
	}
}

// A cancelled receive (a push this device stops) is recorded as a Cancelled
// history entry, not dropped.
func TestRecordReceiveCancelledAddsCancelledHistory(t *testing.T) {
	m := historyManager(t)
	m.RecordReceiveCancelled("fp-1", "Pixel 8 Pro", []ReceivedFile{{Name: "photo.jpg", Size: 2048}}, time.Time{})

	list := m.List()
	if len(list) != 1 {
		t.Fatalf("List length = %d, want 1", len(list))
	}
	v := list[0]
	if v.Direction != "receive" || v.State != StateCancelled {
		t.Fatalf("direction/state = %q/%q, want receive/%q", v.Direction, v.State, StateCancelled)
	}
	if v.ShareLabel != "photo.jpg" || v.Total != 2048 {
		t.Fatalf("label/total = %q/%d", v.ShareLabel, v.Total)
	}
	if v.FinishedAt.IsZero() {
		t.Error("finished time was not set")
	}
}

// pairSenderForPush lets the rig's sender push without an approval prompt.
func pairSenderForPush(r *pushRig) {
	r.trR.Pair(trust.Entry{
		DeviceID: r.sendID.DeviceID, Name: "Sender PC", Fingerprint: r.sendID.DeviceID,
		Mode: trust.ModePair, Permissions: trust.Permissions{Push: trust.Allow},
	})
}

func TestRetryCreatesNewJobAndKeepsTheOld(t *testing.T) {
	r := newPushRig(t)
	pairSenderForPush(r)
	src := writeTemp(t, "note.txt", []byte("hello"))

	v := r.push(t, src)
	waitState(t, r.mgr, v.ID, StateDone, 20*time.Second)

	v2, err := r.mgr.Retry(context.Background(), v.ID)
	if err != nil {
		t.Fatalf("Retry: %v", err)
	}
	if v2.ID == v.ID {
		t.Fatal("retry must create a new job")
	}
	if old, ok := r.mgr.Get(v.ID); !ok || old.State != StateDone {
		t.Fatalf("the original job must be left intact, got %+v ok=%v", old, ok)
	}
	waitState(t, r.mgr, v2.ID, StateDone, 20*time.Second)
}

func TestRetryFailsWhenTheSourceIsGone(t *testing.T) {
	r := newPushRig(t)
	pairSenderForPush(r)
	src := writeTemp(t, "gone.txt", []byte("bye"))

	v := r.push(t, src)
	waitState(t, r.mgr, v.ID, StateDone, 20*time.Second)
	if err := os.Remove(src); err != nil {
		t.Fatal(err)
	}
	if _, err := r.mgr.Retry(context.Background(), v.ID); err == nil {
		t.Fatal("retry must fail clearly when the source path no longer exists")
	}
}

func TestRetryRejectsAnUnfinishedJob(t *testing.T) {
	m := historyManager(t)
	m.jobs["live"] = &Job{ID: "live", State: StateTransferring}
	if _, err := m.Retry(context.Background(), "live"); err == nil {
		t.Fatal("a running job must not be retried")
	}
	if _, err := m.Retry(context.Background(), "nope"); err == nil {
		t.Fatal("an unknown job must not be retried")
	}
}

// Terminal history is saved without the per-file list, but keeps the counts the
// history UI shows, and reloads with those counts intact.
func TestPersistedHistoryIsCompactAndReloadsCounts(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	m := New(cfg, nil, quiet, 3, nil)
	at := time.Now()
	job := &Job{
		ID: "j_done", Direction: "download", PeerID: "peer", PeerName: "Bob",
		State: StateDone, Total: 300, Done: 300,
		StartedAt: at, UpdatedAt: at, FinishedAt: at,
		RemotePaths: []string{"dir,with,commas"},
		Files: []*FileJob{
			{Rel: "a", Local: "a", Size: 100, State: FileDone},
			{Rel: "b", Local: "b", Size: 100, State: FileDone},
			{Rel: "c", Local: "c", Size: 100, State: FilePending},
		},
	}
	m.mu.Lock()
	m.jobs[job.ID] = job
	m.mu.Unlock()
	m.persist()

	var entries []map[string]any
	if err := json.Unmarshal(cfg.Get().Transfers, &entries); err != nil {
		t.Fatalf("unmarshal saved transfers: %v", err)
	}
	if len(entries) != 1 {
		t.Fatalf("saved %d entries, want 1", len(entries))
	}
	e := entries[0]
	if _, has := e["files"]; has {
		t.Fatal("a terminal history entry must not persist the per-file list")
	}
	if got := int(e["files_total"].(float64)); got != 3 {
		t.Fatalf("files_total = %d, want 3", got)
	}
	if got := int(e["files_done"].(float64)); got != 2 {
		t.Fatalf("files_done = %d, want 2", got)
	}

	m2 := New(cfg, nil, quiet, 3, nil)
	if err := m2.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	v, ok := m2.Get("j_done")
	if !ok {
		t.Fatal("the job did not reload")
	}
	if v.FilesTotal != 3 || v.FilesDone != 2 {
		t.Fatalf("counts after reload: total=%d done=%d, want 3/2", v.FilesTotal, v.FilesDone)
	}
	if v.Total != 300 || v.Done != 300 {
		t.Fatalf("bytes after reload: total=%d done=%d, want 300/300", v.Total, v.Done)
	}
}

func jobRemotePaths(m *Manager, id string) []string {
	m.mu.Lock()
	defer m.mu.Unlock()
	if j := m.jobs[id]; j != nil {
		return append([]string(nil), j.RemotePaths...)
	}
	return nil
}

// A remote path containing a comma must survive a retry: it is stored as a
// slice, not re-derived by splitting a joined string. With the old Root-split
// this retry requested the two paths "my" and "dir" and failed; with the fix it
// requests the single path "my,dir" again.
func TestRetryKeepsCommaInRemotePath(t *testing.T) {
	e := newEnv(t, func(int) connPlan { return connPlan{} })
	writeSrc(t, filepath.Join(e.srcDir, "my,dir", "x.txt"), []byte("comma path payload"))

	old := &Job{
		ID: "j_old", Direction: "download", PeerID: e.peerFP, PeerName: "peer",
		Host: "127.0.0.1", Port: e.proxy.Port(), ShareID: e.share.ShareID, ShareLabel: e.share.Label,
		Root: "my,dir", RemotePaths: []string{"my,dir"}, Dest: t.TempDir(),
		State: StateDone, StartedAt: time.Now(), FinishedAt: time.Now(),
	}
	e.mgr.mu.Lock()
	e.mgr.jobs[old.ID] = old
	e.mgr.mu.Unlock()

	v2, err := e.mgr.Retry(context.Background(), old.ID)
	if err != nil {
		t.Fatalf("Retry with a comma in the path: %v", err)
	}
	if got := jobRemotePaths(e.mgr, v2.ID); len(got) != 1 || got[0] != "my,dir" {
		t.Fatalf("retried remote paths = %v, want [my,dir]", got)
	}
	if v, ok := e.mgr.Get(old.ID); !ok || v.State != StateDone {
		t.Fatal("the original job must be left intact")
	}
	// The re-created job starts a real download; stop it, we only check the plan.
	_ = e.mgr.Cancel(v2.ID, false)
}

// A received push is recorded as a terminal "receive" entry so it shows in the
// Transfers history with peer, file name, size and time.
func TestRecordReceivedAddsHistoryEntry(t *testing.T) {
	m := historyManager(t)
	m.RecordReceived("fp-1", "Pixel 8 Pro", []ReceivedFile{{Name: "photo.jpg", Size: 2048}}, time.Time{})

	list := m.List()
	if len(list) != 1 {
		t.Fatalf("List length = %d, want 1", len(list))
	}
	v := list[0]
	if v.Direction != "receive" {
		t.Errorf("direction = %q, want receive", v.Direction)
	}
	if v.State != StateDone {
		t.Errorf("state = %q, want Done", v.State)
	}
	if v.PeerID != "fp-1" || v.PeerName != "Pixel 8 Pro" {
		t.Errorf("peer = %q / %q", v.PeerID, v.PeerName)
	}
	if v.ShareLabel != "photo.jpg" {
		t.Errorf("share_label = %q, want the file name", v.ShareLabel)
	}
	if v.Total != 2048 || v.FilesDone != 1 || len(v.Files) != 1 || v.Files[0].Local != "photo.jpg" {
		t.Errorf("files = %+v total=%d done=%d", v.Files, v.Total, v.FilesDone)
	}
	if v.FinishedAt.IsZero() {
		t.Error("finished time was not set")
	}
	if raw := m.cfg.Get().Transfers; len(raw) == 0 {
		t.Error("receive entry was not persisted")
	}
}

func TestRecordReceivedMultipleNamesLabel(t *testing.T) {
	m := historyManager(t)
	m.RecordReceived("fp", "Peer", []ReceivedFile{{Name: "a.txt", Size: 1}, {Name: "b.txt", Size: 2}}, time.Time{})
	v := m.List()[0]
	if v.ShareLabel != "2 files" {
		t.Errorf("share_label = %q, want \"2 files\"", v.ShareLabel)
	}
	if v.Total != 3 {
		t.Errorf("total = %d, want 3", v.Total)
	}
}
