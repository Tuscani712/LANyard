package discovery

import (
	"context"
	"encoding/json"
	"net"
	"strings"
	"testing"
	"time"
)

// The beacon must broadcast and listen on the configured port, not a fixed one.
func TestBeaconUsesConfiguredPort(t *testing.T) {
	probe := func(context.Context, string, int) (string, *Hello, error) {
		return strings.Repeat("a", 64), &Hello{Name: "peer"}, nil
	}
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), probe, discardLogger())

	// Pick a free UDP port to hand to the beacon.
	free, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := free.LocalAddr().(*net.UDPAddr).Port
	free.Close()
	m.SetBeaconPort(port)
	if m.BeaconPort() != port {
		t.Fatalf("BeaconPort = %d, want %d", m.BeaconPort(), port)
	}

	ctx, cancel := context.WithCancel(context.Background())
	defer m.wg.Wait()
	defer cancel()
	m.startBeacon(ctx)

	conn, err := net.ListenPacket("udp4", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer conn.Close()
	msg, _ := json.Marshal(beaconMsg{Type: "probe", Announcement: Announcement{
		Version: ProtocolVersion, ShortID: "peer", Name: "peer", Port: 47800,
	}})
	if _, err := conn.WriteTo(msg, &net.UDPAddr{IP: net.IPv4(127, 0, 0, 1), Port: port}); err != nil {
		t.Fatal(err)
	}
	_ = conn.SetReadDeadline(time.Now().Add(3 * time.Second))
	buf := make([]byte, 2048)
	n, _, err := conn.ReadFrom(buf)
	if err != nil {
		t.Fatalf("no beacon reply on the configured port %d: %v", port, err)
	}
	var got beaconMsg
	if json.Unmarshal(buf[:n], &got) != nil || got.Type != "beacon" {
		t.Fatalf("unexpected beacon reply %q", buf[:n])
	}
}

// A changed peer port is reflected in what we advertise (the beacon picks it up
// and mDNS is re-registered).
func TestSetPortUpdatesAnnouncement(t *testing.T) {
	m := New(Announcement{Name: "me", Port: 47800}, strings.Repeat("f", 64),
		func(context.Context, string, int) (string, *Hello, error) { return "", nil, nil }, discardLogger())
	m.SetPort(50000)
	if got := m.selfAnn().Port; got != 50000 {
		t.Fatalf("advertised port = %d, want 50000", got)
	}
	m.SetPort(0) // out of range: ignored
	if got := m.selfAnn().Port; got != 50000 {
		t.Fatalf("an invalid port must be ignored, got %d", got)
	}
}

// H5: a re-announcement with the same address but a new port is a real change
// and must notify subscribers so the stored paired address is refreshed.
func TestUpsertPortChangeNotifies(t *testing.T) {
	r := newRegistry()
	a := Announcement{Version: ProtocolVersion, ShortID: "peer", Name: "P", Port: 47800}
	if _, changed := r.upsert(a, []string{"10.0.0.5"}, "beacon"); !changed {
		t.Fatal("a first sighting should be a change")
	}
	a.Port = 51234
	_, changed := r.upsert(a, []string{"10.0.0.5"}, "beacon")
	if !changed {
		t.Fatal("a new port for the same address must be reported as a change")
	}
	p := r.peers["peer"]
	if p.Port != 51234 {
		t.Fatalf("stored port = %d, want 51234", p.Port)
	}
}
