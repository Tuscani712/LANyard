package discovery

import (
	"context"
	"io"
	"log/slog"
	"strings"
	"testing"
)

func discardLogger() *slog.Logger {
	return slog.New(slog.NewTextHandler(io.Discard, nil))
}

// A pairing link pins a fingerprint; a device whose presented certificate does
// not match it must be rejected and never recorded.
func TestAddManualPinnedRejectsMismatch(t *testing.T) {
	certID := strings.Repeat("a", 64)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64),
		func(context.Context, string, int) (string, *Hello, error) {
			return certID, &Hello{Name: "peer"}, nil
		}, discardLogger())

	if _, err := m.AddManual(context.Background(), "192.168.1.20", 47800, strings.Repeat("b", 64)); err == nil {
		t.Fatal("a mismatched fingerprint must fail closed")
	}
	if len(m.Peers()) != 0 {
		t.Fatalf("a mismatched peer must not be recorded, got %v", m.Peers())
	}
}

func TestAddManualPinnedAcceptsMatch(t *testing.T) {
	certID := strings.Repeat("a", 64)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64),
		func(context.Context, string, int) (string, *Hello, error) {
			return certID, &Hello{Name: "peer"}, nil
		}, discardLogger())

	p, err := m.AddManual(context.Background(), "192.168.1.20", 47800, certID)
	if err != nil {
		t.Fatalf("AddManual: %v", err)
	}
	if p == nil || p.DeviceID != certID {
		t.Fatalf("peer = %+v, want DeviceID %s", p, certID)
	}
}

// With no pinned fingerprint, AddManual behaves as before (add by address).
func TestAddManualUnpinnedStillWorks(t *testing.T) {
	certID := strings.Repeat("a", 64)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64),
		func(context.Context, string, int) (string, *Hello, error) {
			return certID, &Hello{Name: "peer"}, nil
		}, discardLogger())

	if _, err := m.AddManual(context.Background(), "192.168.1.20", 47800, ""); err != nil {
		t.Fatalf("AddManual without a pin: %v", err)
	}
}
