package peerapi

import (
	"context"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/shares"
	"lanyard/internal/trust"
	"lanyard/internal/xferlog"
)

// pairedBrowseAuth authorizes any peer as a paired peer with pull permission,
// so the serving-side pull tests can reach the share endpoints.
type pairedBrowseAuth struct{}

func (pairedBrowseAuth) Access(string) trust.Access {
	return trust.Access{Paired: true, Browse: trust.Allow, DeviceID: "self"}
}

// Pulling is one of the four logged areas: a share-list request and a download
// served from a share must both be recorded, with the path reduced to a class.
func TestPullServingLogged(t *testing.T) {
	root := t.TempDir()
	if err := os.WriteFile(filepath.Join(root, "a.txt"), []byte("hello"), 0o644); err != nil {
		t.Fatal(err)
	}
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatal(err)
	}
	shMgr := shares.New(cfg, "self", nil)
	t.Cleanup(func() { shMgr.StopAll() })
	sh, err := shMgr.Add(root, shares.AddOptions{LifetimeType: shares.LifetimeUntilStopped})
	if err != nil {
		t.Fatalf("Add share: %v", err)
	}
	rec := xferlog.New(100)
	s := &Server{shares: shMgr, auth: pairedBrowseAuth{}, log: quietLogger(), xlog: rec}

	withPeer := func(req *http.Request) *http.Request {
		return req.WithContext(context.WithValue(req.Context(), peerIDKey, "peer-fp"))
	}

	rr := httptest.NewRecorder()
	s.handleShareList(rr, withPeer(httptest.NewRequest(http.MethodGet, "/api/v1/shares", nil)))
	if rr.Code != http.StatusOK {
		t.Fatalf("share list status = %d body = %s", rr.Code, rr.Body.String())
	}

	rr = httptest.NewRecorder()
	req := withPeer(httptest.NewRequest(http.MethodGet, "/api/v1/shares/"+sh.ShareID+"/file?path=a.txt", nil))
	req.SetPathValue("id", sh.ShareID)
	s.handleFile(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("file status = %d body = %s", rr.Code, rr.Body.String())
	}

	want := map[string]bool{"share-list": false, "serve": false}
	for _, e := range rec.Entries() {
		if e.Area != xferlog.AreaPulling {
			t.Errorf("entry not tagged pulling: %+v", e)
		}
		if _, ok := want[e.Outcome]; ok {
			want[e.Outcome] = true
		}
		// A download entry must never carry the real path, only a class.
		if e.File == "a.txt" {
			t.Errorf("pull log stored a file name, want a class: %+v", e)
		}
	}
	for outcome, ok := range want {
		if !ok {
			t.Errorf("pull outcome %q not logged: %+v", outcome, rec.Entries())
		}
	}
}
