package peerapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"strings"
	"testing"

	"lanyard/internal/inbox"
)

func newPushTestServer(t *testing.T) (*Server, *inbox.Manager) {
	t.Helper()
	ib := inbox.New(t.TempDir(), nil)
	t.Cleanup(func() { ib.Close() })
	s := &Server{inbox: ib, auth: AllowAll(), log: slog.New(slog.NewTextHandler(io.Discard, nil))}
	return s, ib
}

func offerFiles(t *testing.T, s *Server, files []map[string]any) *httptest.ResponseRecorder {
	t.Helper()
	b, err := json.Marshal(map[string]any{"files": files})
	if err != nil {
		t.Fatal(err)
	}
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/push/offer", strings.NewReader(string(b)))
	s.handlePushOffer(rr, req)
	return rr
}

// Two overlapping offers from the same peer naming the same file: the second is
// refused with 409 and readable text, and the first still completes intact. This
// is the exact log scenario (two pushes ~10 s apart, one shared .lanpart).
func TestOverlappingPushOffersRefused(t *testing.T) {
	s, ib := newPushTestServer(t)
	body := []byte("hello")

	files := []map[string]any{{"rel_path": "hello.txt", "size": len(body)}}
	rr1 := offerFiles(t, s, files)
	if rr1.Code != http.StatusOK {
		t.Fatalf("first offer status = %d body = %s", rr1.Code, rr1.Body.String())
	}
	var o1 pushOfferResp
	if err := json.Unmarshal(rr1.Body.Bytes(), &o1); err != nil {
		t.Fatalf("first offer decode: %v", err)
	}

	rr2 := offerFiles(t, s, files)
	if rr2.Code != http.StatusConflict {
		t.Fatalf("second overlapping offer status = %d, want 409 (body %q)", rr2.Code, rr2.Body.String())
	}
	if !strings.Contains(rr2.Body.String(), "already being received") {
		t.Errorf("second offer body = %q, want readable conflict text", rr2.Body.String())
	}

	// The first push must still finalize intact despite the refused overlap.
	sum := sha256.Sum256(body)
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/push/"+o1.PushID+"/file?path=hello.txt", strings.NewReader(string(body)))
	req.SetPathValue("id", o1.PushID)
	req.Header.Set("Content-Range", "bytes 0-4/5")
	req.Header.Set("X-Lanyard-SHA256", hex.EncodeToString(sum[:]))
	s.handlePushFile(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("first file status = %d body = %s", rr.Code, rr.Body.String())
	}

	rr = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPost, "/api/v1/push/"+o1.PushID+"/complete", strings.NewReader(`{"all":true}`))
	req.SetPathValue("id", o1.PushID)
	s.handlePushComplete(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("first final complete status = %d body = %s", rr.Code, rr.Body.String())
	}
	got, err := os.ReadFile(filepath.Join(ib.Dir(), "hello.txt"))
	if err != nil || string(got) != string(body) {
		t.Fatalf("first push file = %q err=%v, want it intact", got, err)
	}
}

// A per-file complete whose part was removed must answer 409 with the reason and
// end the push as Failed: it may not leave the row "Receiving" forever.
func TestCompleteMissingPartEndsPushFailed(t *testing.T) {
	s, ib := newPushTestServer(t)
	body := []byte("gone")

	rr := offerFiles(t, s, []map[string]any{{"rel_path": "hello.txt", "size": len(body)}})
	if rr.Code != http.StatusOK {
		t.Fatalf("offer status = %d body = %s", rr.Code, rr.Body.String())
	}
	var o pushOfferResp
	if err := json.Unmarshal(rr.Body.Bytes(), &o); err != nil {
		t.Fatal(err)
	}

	// Install the part with the streaming path, then remove it out from under
	// the push (as the colliding push's finalize did in the log).
	rr = httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPut, "/api/v1/push/"+o.PushID+"/file?path=hello.txt", strings.NewReader(string(body)))
	req.SetPathValue("id", o.PushID)
	req.Header.Set("Content-Range", "bytes 0-3/4")
	s.handlePushFile(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("file status = %d body = %s", rr.Code, rr.Body.String())
	}
	if err := os.Remove(filepath.Join(ib.Dir(), "hello.txt.lanpart")); err != nil {
		t.Fatal(err)
	}

	sum := sha256.Sum256(body)
	completeBody := `{"rel_path":"hello.txt","sha256":"` + hex.EncodeToString(sum[:]) + `"}`
	rr = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPost, "/api/v1/push/"+o.PushID+"/complete", strings.NewReader(completeBody))
	req.SetPathValue("id", o.PushID)
	s.handlePushComplete(rr, req)
	if rr.Code != http.StatusConflict {
		t.Fatalf("complete status = %d, want 409 (body %q)", rr.Code, rr.Body.String())
	}
	if strings.TrimSpace(rr.Body.String()) == "" {
		t.Error("complete failure body is empty; the reason must surface")
	}
	// The push must be gone, not stuck at "Receiving".
	if got := ib.Incoming(); len(got) != 0 {
		t.Fatalf("failed complete left the push Receiving: %+v", got)
	}
}
