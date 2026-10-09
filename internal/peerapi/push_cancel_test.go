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

// When the receiving person cancels an accepted push, the sender's in-flight
// file PUT must answer 410 Gone with the "cancelled by the receiver" reason (the
// desktop-receiver half of the symmetric cancel wording), and the push must be
// off the Receiving list.
func TestReceiverCancelAnswers410ToInFlightPUT(t *testing.T) {
	s, ib := newPushTestServer(t)

	rr := offerFiles(t, s, []map[string]any{{"rel_path": "big.bin", "size": 9}})
	if rr.Code != http.StatusOK {
		t.Fatalf("offer status = %d body = %s", rr.Code, rr.Body.String())
	}
	var offer pushOfferResp
	if err := json.Unmarshal(rr.Body.Bytes(), &offer); err != nil {
		t.Fatalf("offer decode: %v", err)
	}

	// The receiving person stops the push (the local UI path).
	if !ib.Cancel(offer.PushID) {
		t.Fatal("cancel reported that the push was not running")
	}

	put := func() *httptest.ResponseRecorder {
		rr := httptest.NewRecorder()
		req := withPeerFP(httptest.NewRequest(http.MethodPut, "/api/v1/push/"+offer.PushID+"/file?path=big.bin", strings.NewReader("tail")), "peer-a")
		req.SetPathValue("id", offer.PushID)
		req.Header.Set("Content-Range", "bytes 5-8/9")
		s.handlePushFile(rr, req)
		return rr
	}
	if got := put(); got.Code != http.StatusGone {
		t.Fatalf("in-flight PUT after receiver cancel = %d, want 410 (body %q)", got.Code, got.Body.String())
	} else if !strings.Contains(got.Body.String(), "cancelled by the receiver") {
		t.Errorf("410 body = %q, want it to say cancelled by the receiver", got.Body.String())
	}
	if got := ib.Incoming(); len(got) != 0 {
		t.Fatalf("cancelled push is still Receiving: %+v", got)
	}
}

// The final whole-push complete must also answer 410 Gone once the receiving
// person has stopped the push, so the sender's final step cannot silently mark
// the row Done. This is the complete-step half of F1 (not only a file PUT).
func TestReceiverCancelAnswers410ToFinalComplete(t *testing.T) {
	s, ib := newPushTestServer(t)

	rr := offerFiles(t, s, []map[string]any{{"rel_path": "small.txt", "size": 5}})
	if rr.Code != http.StatusOK {
		t.Fatalf("offer status = %d body = %s", rr.Code, rr.Body.String())
	}
	var offer pushOfferResp
	if err := json.Unmarshal(rr.Body.Bytes(), &offer); err != nil {
		t.Fatalf("offer decode: %v", err)
	}
	if !ib.Cancel(offer.PushID) {
		t.Fatal("cancel reported that the push was not running")
	}

	rr = httptest.NewRecorder()
	req := withPeerFP(httptest.NewRequest(http.MethodPost, "/api/v1/push/"+offer.PushID+"/complete", strings.NewReader(`{"all":true}`)), "peer-a")
	req.SetPathValue("id", offer.PushID)
	s.handlePushComplete(rr, req)
	if rr.Code != http.StatusGone {
		t.Fatalf("final complete after receiver cancel = %d, want 410 (body %q)", rr.Code, rr.Body.String())
	}
	if !strings.Contains(rr.Body.String(), "cancelled by the receiver") {
		t.Errorf("410 body = %q, want it to say cancelled by the receiver", rr.Body.String())
	}
}
