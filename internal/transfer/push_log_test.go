package transfer

import (
	"context"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
	"lanyard/internal/xferlog"
)

// pushLogHarness wires a Manager to an httptest TLS peer and captures the
// transfer log the manager records.
type pushLogHarness struct {
	m    *Manager
	rec  *xferlog.Recorder
	port int
	src  string
}

func newPushLogHarness(t *testing.T, mux *http.ServeMux) *pushLogHarness {
	t.Helper()
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	srv := httptest.NewTLSServer(mux)
	t.Cleanup(srv.Close)
	m := New(cfg, peerapi.NewClient(id), quiet, 3, nil)
	rec := xferlog.New(50)
	m.SetXferLog(rec)
	src := filepath.Join(t.TempDir(), "small.txt")
	if err := os.WriteFile(src, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	return &pushLogHarness{m: m, rec: rec, port: srv.Listener.Addr().(*net.TCPAddr).Port, src: src}
}

func (h *pushLogHarness) push(t *testing.T) *View {
	t.Helper()
	v, err := h.m.Push(context.Background(), PushParams{
		PeerID: "", PeerName: "peer", Host: "127.0.0.1", Port: h.port, Paths: []string{h.src},
	})
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	return v
}

func findEntry(entries []xferlog.Entry, step string, level xferlog.Level) (xferlog.Entry, bool) {
	for _, e := range entries {
		if e.Step == step && e.Level == level {
			return e, true
		}
	}
	return xferlog.Entry{}, false
}

// A push whose per-file PUT is refused (403) must record the offer and the
// failed file attempt, carrying the target, the error and the bytes sent.
func TestPushLogsFileFailure(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/push/offer", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"push_id": "p_test", "accepted": true, "max_bytes": 1 << 30,
			"files": []map[string]any{{"rel_path": "small.txt", "offset": 0}},
		})
	})
	mux.HandleFunc("PUT /api/v1/push/{id}/file", func(w http.ResponseWriter, r *http.Request) {
		_, _ = io.Copy(io.Discard, r.Body)
		http.Error(w, "not permitted", http.StatusForbidden)
	})
	h := newPushLogHarness(t, mux)
	v := h.push(t)
	waitState(t, h.m, v.ID, StateFailed, 10*time.Second)

	entries := h.rec.Entries()
	offer, ok := findEntry(entries, xferlog.StepOffer, xferlog.LevelInfo)
	if !ok {
		t.Fatalf("offer attempt not logged: %+v", entries)
	}
	if offer.Direction != xferlog.DirectionSend || offer.Target == "" {
		t.Errorf("offer entry missing direction/target: %+v", offer)
	}
	file, ok := findEntry(entries, xferlog.StepFile, xferlog.LevelError)
	if !ok {
		t.Fatalf("failed file attempt not logged: %+v", entries)
	}
	if !strings.Contains(file.Error, "403") && !strings.Contains(file.Error, "not permitted") {
		t.Errorf("file error = %q, want the full peer error", file.Error)
	}
	if file.File != "small.txt" {
		t.Errorf("file label = %q, want base name only", file.File)
	}
	rep := h.rec.Report()
	if !strings.Contains(rep, "send/file") || !strings.Contains(rep, "not permitted") {
		t.Errorf("diagnostics report missing the failed file attempt:\n%s", rep)
	}
}

// A push whose final complete is refused (403) must record the offer, the file
// attempts and the failed complete, each with the error.
func TestPushLogsCompleteFailure(t *testing.T) {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/push/offer", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"push_id": "p_test", "accepted": true, "max_bytes": 1 << 30,
			"files": []map[string]any{{"rel_path": "small.txt", "offset": 0}},
		})
	})
	mux.HandleFunc("PUT /api/v1/push/{id}/file", func(w http.ResponseWriter, r *http.Request) {
		n, _ := io.Copy(io.Discard, r.Body)
		_ = json.NewEncoder(w).Encode(map[string]any{"written": n})
	})
	mux.HandleFunc("POST /api/v1/push/{id}/complete", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "not permitted", http.StatusForbidden)
	})
	h := newPushLogHarness(t, mux)
	v := h.push(t)
	waitState(t, h.m, v.ID, StateFailed, 10*time.Second)

	entries := h.rec.Entries()
	for _, step := range []string{xferlog.StepOffer, xferlog.StepFile} {
		if _, ok := findEntry(entries, step, xferlog.LevelInfo); !ok {
			t.Errorf("%s success not logged: %+v", step, entries)
		}
	}
	complete, ok := findEntry(entries, xferlog.StepComplete, xferlog.LevelError)
	if !ok {
		t.Fatalf("failed complete attempt not logged: %+v", entries)
	}
	if !strings.Contains(complete.Error, "not permitted") {
		t.Errorf("complete error = %q, want the full peer error", complete.Error)
	}

	// The job itself must show the mapped wording, not the raw status line.
	got, _ := h.m.Get(v.ID)
	if got.State != StateFailed || !strings.Contains(got.Error, "Not paired with this device") {
		t.Errorf("job error = %q (state %s), want the mapped 'not paired' wording", got.Error, got.State)
	}
}
