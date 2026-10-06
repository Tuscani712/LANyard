package lanaddr

import (
	"net"
	"reflect"
	"testing"
)

func TestChooseAddrsPrimaryFirst(t *testing.T) {
	entries := []ifaceAddr{
		{name: "docker0", ip: net.ParseIP("172.17.0.1")},
		{name: "eth0", ip: net.ParseIP("192.168.90.121")},
		{name: "lxcbr0", ip: net.ParseIP("10.0.3.1")},
		{name: "br-abc123", ip: net.ParseIP("172.18.0.1")},
	}
	got := chooseAddrs("192.168.90.121", entries)
	want := []string{"192.168.90.121"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("chooseAddrs = %v, want %v", got, want)
	}
}

func TestChooseAddrsDropsVirtualWhenRealExists(t *testing.T) {
	entries := []ifaceAddr{
		{name: "docker0", ip: net.ParseIP("172.17.0.1")},
		{name: "eth0", ip: net.ParseIP("192.168.1.20")},
		{name: "wlan0", ip: net.ParseIP("10.0.0.5")},
	}
	got := chooseAddrs("", entries)
	want := []string{"10.0.0.5", "192.168.1.20"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("chooseAddrs = %v, want %v", got, want)
	}
}

func TestChooseAddrsVirtualFallback(t *testing.T) {
	entries := []ifaceAddr{
		{name: "docker0", ip: net.ParseIP("172.17.0.1")},
		{name: "lxcbr0", ip: net.ParseIP("10.0.3.1")},
	}
	got := chooseAddrs("", entries)
	want := []string{"10.0.3.1", "172.17.0.1"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("chooseAddrs = %v, want %v", got, want)
	}
}

func TestChooseAddrsFiltersAndDedupes(t *testing.T) {
	entries := []ifaceAddr{
		{name: "eth0", ip: net.ParseIP("8.8.8.8")},      // public: drop
		{name: "lo", ip: net.ParseIP("127.0.0.1")},      // loopback: drop
		{name: "eth0", ip: net.ParseIP("0.0.0.0")},      // unspecified: drop
		{name: "eth0", ip: net.ParseIP("192.168.1.20")}, // private
		{name: "eth0", ip: net.ParseIP("192.168.1.20")}, // duplicate: collapse
		{name: "eth1", ip: net.ParseIP("fe80::1")},      // link-local v6: keep
	}
	got := chooseAddrs("", entries)
	want := []string{"192.168.1.20", "fe80::1"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("chooseAddrs = %v, want %v", got, want)
	}
}

func TestChooseAddrsEmpty(t *testing.T) {
	if got := chooseAddrs("192.168.1.20", nil); got == nil || len(got) != 0 {
		t.Fatalf("chooseAddrs(nil) = %v, want empty", got)
	}
}
