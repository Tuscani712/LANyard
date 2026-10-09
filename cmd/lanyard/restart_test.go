package main

import (
	"context"
	"io"
	"log/slog"
	"net"
	"testing"
	"time"

	"lanyard/internal/discovery"
	"lanyard/internal/identity"
	"lanyard/internal/peerapi"
)

// H1: applying a new peer port rebinds the live listener and re-announces it,
// without a full restart.
func TestRestartPeerListenerRebinds(t *testing.T) {
	id, err := identity.LoadOrCreate(t.TempDir(), "test")
	if err != nil {
		t.Fatalf("identity: %v", err)
	}
	log := slog.New(slog.NewTextHandler(io.Discard, nil))
	srv := peerapi.NewServer(id, func() discovery.Hello { return discovery.Hello{} }, nil, nil, peerapi.AllowAll(), log)

	port1 := freeTCPPort(t)
	ln, err := srv.Listen(port1)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	go func() { _ = srv.Serve(ln) }()
	time.Sleep(50 * time.Millisecond) // let Serve publish its http.Server

	disc := discovery.New(discovery.Announcement{Name: "test", Port: port1}, id.DeviceID,
		func(context.Context, string, int) (string, *discovery.Hello, error) {
			return "", nil, nil
		}, log)

	port2 := freeTCPPort(t)
	restartPeerListener(srv, disc, port2, log)

	if got := srv.RequestedPort(); got != port2 {
		t.Fatalf("RequestedPort = %d, want %d", got, port2)
	}
	if got := srv.Port(); got != port2 {
		t.Fatalf("Port = %d, want the rebound %d", got, port2)
	}
	if srv.PortFellBack() {
		t.Fatal("rebinding to a free port must not fall back")
	}
}

func freeTCPPort(t *testing.T) int {
	t.Helper()
	ln, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatalf("pick free port: %v", err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	_ = ln.Close()
	return port
}
