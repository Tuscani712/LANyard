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
)

// A failure from the final PushComplete(all=true) means the receiver never
// finalized the push: the job must be marked failed, not Done, and carry the
// error.
func TestFinalPushCompleteErrorFailsJob(t *testing.T) {
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("POST /api/v1/push/offer", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]any{
			"push_id": "p_test", "accepted": true, "max_bytes": 1 << 30, "files": []any{},
		})
	})
	mux.HandleFunc("PUT /api/v1/push/{id}/file", func(w http.ResponseWriter, r *http.Request) {
		n, _ := io.Copy(io.Discard, r.Body)
		_ = json.NewEncoder(w).Encode(map[string]any{"written": n})
	})
	mux.HandleFunc("POST /api/v1/push/{id}/complete", func(w http.ResponseWriter, r *http.Request) {
		http.Error(w, "receiver refused to finalize", http.StatusConflict)
	})
	srv := httptest.NewTLSServer(mux)
	defer srv.Close()
	port := srv.Listener.Addr().(*net.TCPAddr).Port

	m := New(cfg, peerapi.NewClient(id), quiet, 3, nil)
	src := filepath.Join(t.TempDir(), "small.txt")
	if err := os.WriteFile(src, []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	v, err := m.Push(context.Background(), PushParams{
		PeerID: "", PeerName: "peer", Host: "127.0.0.1", Port: port, Paths: []string{src},
	})
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	got := waitState(t, m, v.ID, StateFailed, 10*time.Second)
	if got.State == StateDone {
		t.Fatal("job must not be Done when the final PushComplete fails")
	}
	if !strings.Contains(got.Error, "finalize") || !strings.Contains(got.Error, "refused") {
		t.Errorf("error = %q, want it to carry the final PushComplete failure", got.Error)
	}
}
