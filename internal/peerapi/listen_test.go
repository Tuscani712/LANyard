package peerapi

import (
	"io"
	"log/slog"
	"net"
	"testing"
)

func quietLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

func TestListenFallsBackWhenPortBusy(t *testing.T) {
	busy, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	defer busy.Close()
	port := busy.Addr().(*net.TCPAddr).Port

	srv := &Server{log: quietLogger()}
	ln, err := srv.Listen(port)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer ln.Close()

	if !srv.PortFellBack() {
		t.Fatal("expected PortFellBack to be true when the requested port is busy")
	}
	if srv.Port() == port {
		t.Fatal("the busy port must not be reused")
	}
	if srv.RequestedPort() != port {
		t.Fatalf("RequestedPort = %d, want %d", srv.RequestedPort(), port)
	}
}

func TestListenKeepsRequestedPort(t *testing.T) {
	free, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	port := free.Addr().(*net.TCPAddr).Port
	free.Close()

	srv := &Server{log: quietLogger()}
	ln, err := srv.Listen(port)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer ln.Close()

	if srv.PortFellBack() {
		t.Fatal("PortFellBack must be false when the requested port was free")
	}
	if srv.Port() != port || srv.RequestedPort() != port {
		t.Fatalf("got port=%d requested=%d, want %d", srv.Port(), srv.RequestedPort(), port)
	}
}
