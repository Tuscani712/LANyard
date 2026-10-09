package main

import (
	"testing"

	"lanyard/internal/config"
)

// A fresh profile has no chosen peer port: the server records the port it
// actually bound so the banner and the next start agree.
func TestWriteBackBoundPeerPortFillsWhenUnset(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	if got := cfg.Get().PeerPort; got != 0 {
		t.Fatalf("fresh peer_port = %d, want 0 (unset)", got)
	}
	writeBackBoundPeerPort(cfg, 51234, false)
	if got := cfg.Get().PeerPort; got != 51234 {
		t.Fatalf("peer_port = %d, want the bound 51234", got)
	}
}

// A port the person chose is never overwritten by the bound-port write-back.
func TestWriteBackBoundPeerPortNeverOverwritesUserChoice(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	if err := cfg.Update(func(s *config.Settings) { s.PeerPort = 5000 }); err != nil {
		t.Fatalf("Update: %v", err)
	}
	writeBackBoundPeerPort(cfg, 51234, false)
	if got := cfg.Get().PeerPort; got != 5000 {
		t.Fatalf("peer_port = %d, want the user-set 5000", got)
	}
}

// A temporary fallback port is never persisted; the next start retries the
// configured (here, default) port.
func TestWriteBackSkipsTemporaryFallback(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	writeBackBoundPeerPort(cfg, 51234, true)
	if got := cfg.Get().PeerPort; got != 0 {
		t.Fatalf("peer_port = %d, want 0 after a temporary fallback", got)
	}
	if got := cfg.Get().EffectivePeerPort(); got != config.DefaultPeerPort {
		t.Fatalf("EffectivePeerPort = %d, want the default %d next start", got, config.DefaultPeerPort)
	}
}
