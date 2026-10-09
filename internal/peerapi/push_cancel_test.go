package peerapi

import (
	"context"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/inbox"
)

// withPeerFP runs a request as the given authenticated peer fingerprint.
func withPeerFP(req *http.Request, fp string) *http.Request {
	return req.WithContext(context.WithValue(req.Context(), peerIDKey, fp))
}

// The sender's cancel route is owner-only and idempotent: the owner stops its
// own push (200, row gone), a different peer's request is a harmless 200 no-op
// that leaves the push intact, and an unknown id is a harmless 200.
func TestPushCancelOwnerOnlyAndIdempotent(t *testing.T) {
	s, ib := newPushTestServer(t)

	offerBody, _ := json.Marshal(map[string]any{
		"files": []map[string]any{{"rel_path": "hello.txt", "size": 5}},
	})
	rr := httptest.NewRecorder()
	req := withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/push/offer", strings.NewReader(string(offerBody))), "peer-a")
	s.handlePushOffer(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("offer status = %d body = %s", rr.Code, rr.Body.String())
	}
	var offer pushOfferResp
	if err := json.Unmarshal(rr.Body.Bytes(), &offer); err != nil {
		t.Fatalf("offer decode: %v", err)
	}

	cancel := func(fp, id string) *httptest.ResponseRecorder {
		rr := httptest.NewRecorder()
		req := withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/push/"+id+"/cancel", nil), fp)
		req.SetPathValue("id", id)
		s.handlePushCancel(rr, req)
		return rr
	}

	// A non-owner may not stop the push, but the route is harmless (200).
	if rr := cancel("peer-b", offer.PushID); rr.Code != http.StatusOK {
		t.Fatalf("non-owner cancel status = %d body = %s", rr.Code, rr.Body.String())
	}
	if got := ib.Incoming(); len(got) != 1 {
		t.Fatalf("a non-owner cancel stopped the push: %+v", got)
	}

	// The owner stops it: 200 and the row leaves the Receiving list at once.
	if rr := cancel("peer-a", offer.PushID); rr.Code != http.StatusOK {
		t.Fatalf("owner cancel status = %d body = %s", rr.Code, rr.Body.String())
	}
	if got := ib.Incoming(); len(got) != 0 {
		t.Fatalf("owner cancel left the push Receiving: %+v", got)
	}

	// Idempotent: the same id again, and an unknown id, are both 200 no-ops.
	if rr := cancel("peer-a", offer.PushID); rr.Code != http.StatusOK {
		t.Fatalf("repeated cancel status = %d, want 200", rr.Code)
	}
	if rr := cancel("peer-a", "p_does-not-exist"); rr.Code != http.StatusOK {
		t.Fatalf("unknown id cancel status = %d, want 200", rr.Code)
	}
}

// A paired peer without push permission may not use the cancel route: the
// authorization gate answers 403 before any push is touched.
func TestPushCancelRequiresPushPermission(t *testing.T) {
	ib := inbox.New(t.TempDir(), nil)
	t.Cleanup(ib.Close)
	s := &Server{inbox: ib, auth: pairedBrowseAuth{}, log: quietLogger()}
	rr := httptest.NewRecorder()
	req := withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/push/p_x/cancel", nil), "peer-a")
	req.SetPathValue("id", "p_x")
	s.handlePushCancel(rr, req)
	if rr.Code != http.StatusForbidden {
		t.Fatalf("cancel without push permission status = %d, want 403", rr.Code)
	}
}
