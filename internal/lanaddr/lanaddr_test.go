package lanaddr

import (
	"net"
	"reflect"
	"testing"
)

func TestFilterKeepsPrivateAndLinkLocalOnly(t *testing.T) {
	in := []net.IP{
		net.ParseIP("192.168.1.20"), // private
		net.ParseIP("10.0.0.5"),     // private
		net.ParseIP("172.16.4.4"),   // private
		net.ParseIP("169.254.10.1"), // link-local
		net.ParseIP("8.8.8.8"),      // public: drop
		net.ParseIP("127.0.0.1"),    // loopback: drop
		net.ParseIP("0.0.0.0"),      // unspecified: drop
		net.ParseIP("fe80::1"),      // link-local v6: keep
		net.ParseIP("2001:4860::1"), // public v6: drop
		net.ParseIP("192.168.1.20"), // duplicate: collapse
	}
	got := Filter(in)
	want := []string{"10.0.0.5", "169.254.10.1", "172.16.4.4", "192.168.1.20", "fe80::1"}
	if !reflect.DeepEqual(got, want) {
		t.Fatalf("Filter = %v, want %v", got, want)
	}
}

func TestFilterEmpty(t *testing.T) {
	if got := Filter(nil); got != nil {
		t.Fatalf("Filter(nil) = %v, want nil", got)
	}
}
