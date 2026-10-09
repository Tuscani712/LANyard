package uiserver

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"

	"lanyard/internal/config"
	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
	"lanyard/internal/trust"
)

func testLogger() (*slog.Logger, *bytes.Buffer) {
	var buf bytes.Buffer
	return slog.New(slog.NewTextHandler(&buf, nil)), &buf
}

// When the peer is not in the discovery registry, revokeRemote must fall back
// to the last address the trust store remembered and, because that address is
// unreachable here, log why it could not notify the peer.
func TestRevokeRemoteFallsBackToLastKnownAddressAndLogs(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	logger, buf := testLogger()
	s := New(Deps{
		Trust:  tr,
		Client: peerapi.NewClient(id),
		Peers:  func() []discovery.Peer { return nil }, // not visible right now
		Log:    logger,
	})

	// Port 1 is closed, so the TLS call fails immediately.
	s.revokeRemote("peer-fp", trust.Entry{Addrs: []string{"127.0.0.1"}, Port: 1})

	logs := buf.String()
	if !strings.Contains(logs, "last-known address") {
		t.Errorf("fallback to the trust address was not logged:\n%s", logs)
	}
	if !strings.Contains(logs, "127.0.0.1:1") {
		t.Errorf("attempted address was not logged:\n%s", logs)
	}
	if !strings.Contains(logs, "could not notify the peer") {
		t.Errorf("failure to notify was not logged:\n%s", logs)
	}
}

// With no address anywhere, revokeRemote logs the reason instead of silently
// giving up.
func TestRevokeRemoteNoAddressLogs(t *testing.T) {
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	logger, buf := testLogger()
	s := New(Deps{
		Client: peerapi.NewClient(id),
		Peers:  func() []discovery.Peer { return nil },
		Log:    logger,
	})
	s.revokeRemote("peer-fp", trust.Entry{})
	if !strings.Contains(buf.String(), "no known address") {
		t.Errorf("missing no-address log:\n%s", buf.String())
	}
}

// A peer that revokes us gets the local cleanup; a repeat (already unpaired)
// still cleans up but does not try to notify the peer back.
func TestHandleRemoteRevokeIsIdempotent(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	tr := trust.New(cfg, "self-fp", nil)
	tr.Pair(trust.Entry{DeviceID: "phone", Fingerprint: "peer-fp", Port: 1, Addrs: []string{"127.0.0.1"}})
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity.LoadOrCreate: %v", err)
	}
	logger, buf := testLogger()
	s := New(Deps{
		Trust:  tr,
		Client: peerapi.NewClient(id),
		Peers:  func() []discovery.Peer { return nil },
		Log:    logger,
	})

	s.HandleRemoteRevoke("peer-fp", true) // peer was paired: notify best effort
	if !strings.Contains(buf.String(), "could not notify the peer") {
		t.Errorf("paired revoke did not attempt notification:\n%s", buf.String())
	}
	if _, ok := tr.Entry("peer-fp"); !ok {
		t.Errorf("HandleRemoteRevoke must not remove the entry; peerapi owns that")
	}

	buf.Reset()
	s.HandleRemoteRevoke("peer-fp", false) // repeat: already unpaired
	if !strings.Contains(buf.String(), "already unpaired") {
		t.Errorf("repeat revoke was not logged as already unpaired:\n%s", buf.String())
	}
}
