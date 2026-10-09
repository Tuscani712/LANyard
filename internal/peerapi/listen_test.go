package peerapi

import (
	"io"
	"log/slog"
	"net"
	"strings"
	"testing"
	"time"
)

func quietLogger() *slog.Logger { return slog.New(slog.NewTextHandler(io.Discard, nil)) }

// Tests must not wait the production grace period on a busy port.
func init() {
	portRetryWindow = 50 * time.Millisecond
	portRetryInterval = 5 * time.Millisecond
}

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
	if srv.FallbackNotice() != "" {
		t.Fatalf("no fallback should carry no notice, got %q", srv.FallbackNotice())
	}
}

// A busy configured port yields a temporary port and a person-facing notice
// naming the port and, when known, the process holding it.
func TestListenFallbackNotice(t *testing.T) {
	busy, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	defer busy.Close()
	port := busy.Addr().(*net.TCPAddr).Port

	old := portHolderFn
	portHolderFn = func(int) string { return "other-app" }
	defer func() { portHolderFn = old }()

	srv := &Server{log: quietLogger()}
	ln, err := srv.Listen(port)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer ln.Close()

	if !srv.PortFellBack() {
		t.Fatal("expected PortFellBack")
	}
	notice := srv.FallbackNotice()
	if notice != TemporaryPortMessage(srv.Port(), port, "other-app") {
		t.Fatalf("FallbackNotice = %q, want the temporary-port message", notice)
	}
	for _, want := range []string{"temporary port", "in use", "other-app"} {
		if !strings.Contains(notice, want) {
			t.Errorf("notice %q missing %q", notice, want)
		}
	}
}

// The notice omits the process when it cannot be identified.
func TestTemporaryPortMessageWithoutHolder(t *testing.T) {
	got := TemporaryPortMessage(51234, 47800, "")
	want := "Using temporary port 51234 because 47800 is in use"
	if got != want {
		t.Fatalf("TemporaryPortMessage = %q, want %q", got, want)
	}
	if strings.Contains(got, " by ") {
		t.Fatalf("notice should not name a process: %q", got)
	}
}

// Listen waits briefly for the configured port to free up before falling back,
// which is what lets a restart overlap keep its port. The requested port is
// tried again; a temporary port is not "remembered" as the choice.
func TestListenRetriesConfiguredPort(t *testing.T) {
	busy, err := net.Listen("tcp", ":0")
	if err != nil {
		t.Fatal(err)
	}
	port := busy.Addr().(*net.TCPAddr).Port

	portRetryWindow = 3 * time.Second
	portRetryInterval = 10 * time.Millisecond
	defer func() {
		portRetryWindow = 50 * time.Millisecond
		portRetryInterval = 5 * time.Millisecond
	}()

	go func() {
		time.Sleep(40 * time.Millisecond)
		busy.Close()
	}()

	srv := &Server{log: quietLogger()}
	ln, err := srv.Listen(port)
	if err != nil {
		t.Fatalf("Listen: %v", err)
	}
	defer ln.Close()
	if srv.PortFellBack() {
		t.Fatal("the port freed within the grace period; expected no fallback")
	}
	if srv.Port() != port {
		t.Fatalf("Port = %d, want the configured %d", srv.Port(), port)
	}
}
