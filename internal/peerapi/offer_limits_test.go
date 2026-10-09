package peerapi

import (
	"encoding/json"
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/discovery"
	"lanyard/internal/inbox"
)

// An advertised value wins; a missing (legacy) value falls back to the floor.
func TestEffectiveOfferLimitsFallback(t *testing.T) {
	if b, f := EffectiveOfferLimits(nil); b != FallbackOfferBytes || f != FallbackOfferFiles {
		t.Errorf("nil hello = %d/%d, want %d/%d", b, f, FallbackOfferBytes, FallbackOfferFiles)
	}
	if b, f := EffectiveOfferLimits(&discovery.Hello{}); b != FallbackOfferBytes || f != FallbackOfferFiles {
		t.Errorf("empty hello = %d/%d, want fallback %d/%d", b, f, FallbackOfferBytes, FallbackOfferFiles)
	}
	if b, f := EffectiveOfferLimits(&discovery.Hello{MaxOfferBytes: 1 << 20}); b != 1<<20 || f != FallbackOfferFiles {
		t.Errorf("partial hello = %d/%d, want %d/%d", b, f, int64(1<<20), FallbackOfferFiles)
	}
	if b, f := EffectiveOfferLimits(&discovery.Hello{MaxOfferBytes: MaxOfferBytes, MaxOfferFiles: MaxOfferFiles}); b != MaxOfferBytes || f != MaxOfferFiles {
		t.Errorf("advertised hello = %d/%d, want %d/%d", b, f, int64(MaxOfferBytes), MaxOfferFiles)
	}
}

// The desktop's /hello response must carry its advertised caps.
func TestHelloAdvertisesOfferLimits(t *testing.T) {
	s := NewServer(nil, func() discovery.Hello {
		return discovery.Hello{Name: "desk", MaxOfferBytes: MaxOfferBytes, MaxOfferFiles: MaxOfferFiles}
	}, nil, nil, AllowAll(), slog.New(slog.NewTextHandler(io.Discard, nil)))
	req := httptest.NewRequest(http.MethodGet, "/api/v1/hello", nil)
	rr := httptest.NewRecorder()
	s.handleHello(rr, req)
	if rr.Code != http.StatusOK {
		t.Fatalf("hello status = %d", rr.Code)
	}
	var got discovery.Hello
	if err := json.Unmarshal(rr.Body.Bytes(), &got); err != nil {
		t.Fatalf("hello decode: %v", err)
	}
	if got.MaxOfferBytes != MaxOfferBytes || got.MaxOfferFiles != MaxOfferFiles {
		t.Errorf("hello limits = %d/%d, want %d/%d", got.MaxOfferBytes, got.MaxOfferFiles, int64(MaxOfferBytes), MaxOfferFiles)
	}
}

// An unreadable /hello must not leave the connection reusable: it answers 413
// and asks the peer to close. The file-count cap takes the same 413 branch as a
// body that overruns max_offer_bytes.
func TestOfferTooLargeClosesConnection(t *testing.T) {
	ib := inbox.New(t.TempDir(), nil)
	t.Cleanup(func() { ib.Close() })
	s := &Server{inbox: ib, auth: AllowAll(), log: slog.New(slog.NewTextHandler(io.Discard, nil))}

	var sb strings.Builder
	sb.WriteString(`{"files":[`)
	for i := 0; i < MaxOfferFiles+1; i++ {
		if i > 0 {
			sb.WriteByte(',')
		}
		sb.WriteString(`{"rel_path":"a","size":0}`)
	}
	sb.WriteString(`]}`)

	rr := httptest.NewRecorder()
	req := httptest.NewRequest(http.MethodPost, "/api/v1/push/offer", strings.NewReader(sb.String()))
	s.handlePushOffer(rr, req)
	if rr.Code != http.StatusRequestEntityTooLarge {
		t.Fatalf("status = %d, want 413 (body %q)", rr.Code, rr.Body.String())
	}
	if got := rr.Header().Get("Connection"); got != "close" {
		t.Errorf("Connection = %q, want close", got)
	}
}
