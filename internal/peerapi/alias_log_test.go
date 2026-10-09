package peerapi

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/identity"
	"lanyard/internal/inbox"
	"lanyard/internal/trust"
	"lanyard/internal/xferlog"
)

// The transfer log records the local alias as the friendly peer name while the
// fingerprint stays short, so a person sees "which device" and the log stays
// privacy-safe (never the full fingerprint, never the broadcast name when an
// alias is set).
func TestPushLogUsesAliasWithShortFingerprint(t *testing.T) {
	const fp = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"

	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: "peer-dev", Name: "Pixel 8 Pro", Fingerprint: fp})
	tr.SetAlias(fp, "Kitchen Phone")

	ib := inbox.New(t.TempDir(), nil)
	t.Cleanup(func() { ib.Close() })
	rec := xferlog.New(50)
	s := &Server{
		trust: tr,
		inbox: ib,
		auth:  AllowAll(),
		log:   slog.New(slog.NewTextHandler(io.Discard, nil)),
		xlog:  rec,
	}

	offerBody, _ := json.Marshal(map[string]any{
		"files": []map[string]any{{"rel_path": "hello.txt", "size": 5}},
	})
	rr := httptest.NewRecorder()
	req := withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/push/offer", strings.NewReader(string(offerBody))), fp)
	s.handlePushOffer(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("offer status = %d body = %s", rr.Code, rr.Body.String())
	}

	found := false
	for _, e := range rec.Entries() {
		if e.Step != xferlog.StepOffer {
			continue
		}
		found = true
		if e.Peer != "Kitchen Phone" {
			t.Errorf("log peer = %q, want the alias", e.Peer)
		}
		if e.FP != identity.ShortID(fp) || len(e.FP) >= len(fp) {
			t.Errorf("log fp = %q, want the short fingerprint %q", e.FP, identity.ShortID(fp))
		}
	}
	if !found {
		t.Fatal("no offer entry was logged")
	}
}
