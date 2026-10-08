package peerapi

import (
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/inbox"
	"lanyard/internal/xferlog"
)

// A received push must record the offer, the per-file receipt and the complete,
// so the receiving side shows up in the desktop diagnostics report too.
func TestReceivePushLogsStages(t *testing.T) {
	ib := inbox.New(t.TempDir(), nil)
	t.Cleanup(func() { ib.Close() })
	rec := xferlog.New(50)
	s := &Server{
		inbox: ib,
		auth:  AllowAll(),
		log:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		xlog:  rec,
	}

	body := []byte("hello")
	offerBody, _ := json.Marshal(map[string]any{
		"files": []map[string]any{{"rel_path": "hello.txt", "size": len(body)}},
	})
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/push/offer", strings.NewReader(string(offerBody)))
	s.handlePushOffer(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("offer status = %d body = %s", rr.Code, rr.Body.String())
	}
	var offer pushOfferResp
	if err := json.Unmarshal(rr.Body.Bytes(), &offer); err != nil {
		t.Fatalf("offer decode: %v", err)
	}

	sum := sha256.Sum256(body)
	rr = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPut, "/api/v1/push/"+offer.PushID+"/file?path=hello.txt", strings.NewReader(string(body)))
	req.SetPathValue("id", offer.PushID)
	req.Header.Set("Content-Range", "bytes 0-4/5")
	req.Header.Set("X-Lanyard-SHA256", hex.EncodeToString(sum[:]))
	s.handlePushFile(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("file status = %d body = %s", rr.Code, rr.Body.String())
	}

	rr = httptest.NewRecorder()
	req = httptest.NewRequest(http.MethodPost, "/api/v1/push/"+offer.PushID+"/complete", strings.NewReader(`{"all":true}`))
	req.SetPathValue("id", offer.PushID)
	s.handlePushComplete(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("complete status = %d body = %s", rr.Code, rr.Body.String())
	}

	entries := rec.Entries()
	for _, step := range []string{xferlog.StepOffer, xferlog.StepFile, xferlog.StepComplete} {
		found := false
		for _, e := range entries {
			if e.Step == step && e.Direction == xferlog.DirectionReceive && e.Level == xferlog.LevelInfo {
				found = true
			}
		}
		if !found {
			t.Errorf("receive %s success not logged: %+v", step, entries)
		}
	}
	if rep := rec.Report(); !strings.Contains(rep, "receive/offer") || !strings.Contains(rep, "receive/file") {
		t.Errorf("report missing receive stages:\n%s", rep)
	}
}

// A refused incoming offer is logged with the reason, so a 403 shows as a
// warning rather than disappearing.
func TestReceivePushRefusalIsLogged(t *testing.T) {
	rec := xferlog.New(50)
	s := &Server{
		auth: AllowAll(),
		log:  slog.New(slog.NewTextHandler(io.Discard, nil)),
		xlog: rec,
	}
	// No inbox wired means pushAccess refuses with 503 before authorization.
	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/push/offer", strings.NewReader(`{"files":[]}`))
	s.handlePushOffer(rr, req)
	if rr.Code != http.StatusServiceUnavailable {
		t.Fatalf("status = %d, want 503", rr.Code)
	}
	entries := rec.Entries()
	if len(entries) == 0 || entries[0].Level != xferlog.LevelError {
		t.Fatalf("refused offer not logged: %+v", entries)
	}
	if entries[0].Direction != xferlog.DirectionReceive || entries[0].Error == "" {
		t.Errorf("refusal entry missing direction/reason: %+v", entries[0])
	}
}
