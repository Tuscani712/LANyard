package transfer

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
)

// The sender reads the peer's hello limits and splits a selection into that
// many offers, each under its own push_id.
func TestPushSplitsOffersByPeerFileCap(t *testing.T) {
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatal(err)
	}
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}

	var offers atomic.Int32
	var mu sync.Mutex
	pushIDs := map[string]bool{}
	perBatch := []int{}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /api/v1/hello", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(discovery.Hello{
			Name: "phone", MaxOfferBytes: 8 << 20, MaxOfferFiles: 2,
		})
	})
	mux.HandleFunc("POST /api/v1/push/offer", func(w http.ResponseWriter, r *http.Request) {
		var req struct {
			Files []struct {
				RelPath string `json:"rel_path"`
			} `json:"files"`
		}
		_ = json.NewDecoder(r.Body).Decode(&req)
		n := offers.Add(1)
		pid := fmt.Sprintf("p%d", n)
		mu.Lock()
		pushIDs[pid] = true
		perBatch = append(perBatch, len(req.Files))
		mu.Unlock()
		files := make([]map[string]any, 0, len(req.Files))
		for _, f := range req.Files {
			files = append(files, map[string]any{"rel_path": f.RelPath, "offset": 0})
		}
		_ = json.NewEncoder(w).Encode(map[string]any{
			"push_id": pid, "accepted": true, "max_bytes": 1 << 30, "files": files,
		})
	})
	mux.HandleFunc("PUT /api/v1/push/{id}/file", func(w http.ResponseWriter, r *http.Request) {
		n, _ := io.Copy(io.Discard, r.Body)
		_ = json.NewEncoder(w).Encode(map[string]any{"written": n})
	})
	mux.HandleFunc("POST /api/v1/push/{id}/complete", func(w http.ResponseWriter, r *http.Request) {
		_ = json.NewEncoder(w).Encode(map[string]bool{"done": true})
	})
	srv := httptest.NewTLSServer(mux)
	defer srv.Close()
	port := srv.Listener.Addr().(*net.TCPAddr).Port

	dir := t.TempDir()
	var paths []string
	for i := 0; i < 5; i++ {
		p := filepath.Join(dir, fmt.Sprintf("f%d.txt", i))
		if err := os.WriteFile(p, []byte(fmt.Sprintf("hello %d", i)), 0o644); err != nil {
			t.Fatal(err)
		}
		paths = append(paths, p)
	}

	m := New(cfg, peerapi.NewClient(id), quiet, 3, nil)
	v, err := m.Push(context.Background(), PushParams{
		PeerID: "", PeerName: "phone", Host: "127.0.0.1", Port: port, Paths: paths,
	})
	if err != nil {
		t.Fatalf("Push: %v", err)
	}
	waitState(t, m, v.ID, StateDone, 10*time.Second)

	if got := offers.Load(); got != 3 {
		t.Fatalf("offers = %d, want 3 (5 files at cap 2 -> 2+2+1)", got)
	}
	if len(pushIDs) != 3 {
		t.Errorf("offers reused a push_id: %v", pushIDs)
	}
	mu.Lock()
	got := append([]int(nil), perBatch...)
	mu.Unlock()
	for i, n := range got {
		if n > 2 {
			t.Errorf("offer %d carried %d files, over the peer cap of 2", i, n)
		}
	}
}
