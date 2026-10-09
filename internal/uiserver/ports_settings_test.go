package uiserver

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"

	"lanyard/internal/config"
)

func putSettings(t *testing.T, cfg *config.Store, body string) *httptest.ResponseRecorder {
	t.Helper()
	enabled := true
	s := settingsTestServer(t, cfg, &enabled)
	rr := httptest.NewRecorder()
	s.handleSettingsPut(rr, httptest.NewRequest("PUT", "/api/settings", strings.NewReader(body)))
	return rr
}

// H1: the peer port is validated to the 1024-65535 range at both boundaries.
func TestSettingsPeerPortBoundaries(t *testing.T) {
	cases := []struct {
		port int
		code int
	}{
		{1023, http.StatusBadRequest},
		{1024, http.StatusOK},
		{65535, http.StatusOK},
		{65536, http.StatusBadRequest},
		{0, http.StatusBadRequest},
	}
	for _, c := range cases {
		cfg, err := config.Open(t.TempDir())
		if err != nil {
			t.Fatal(err)
		}
		rr := putSettings(t, cfg, `{"peer_port":`+itoa(c.port)+`}`)
		if rr.Code != c.code {
			t.Errorf("peer_port %d: code = %d, want %d (%s)", c.port, rr.Code, c.code, rr.Body.String())
		}
	}
}

// H3: the beacon port is validated to the same range.
func TestSettingsBeaconPortBoundaries(t *testing.T) {
	cases := []struct {
		port int
		code int
	}{
		{1023, http.StatusBadRequest},
		{1024, http.StatusOK},
		{65535, http.StatusOK},
		{65536, http.StatusBadRequest},
	}
	for _, c := range cases {
		cfg, err := config.Open(t.TempDir())
		if err != nil {
			t.Fatal(err)
		}
		rr := putSettings(t, cfg, `{"beacon_port":`+itoa(c.port)+`}`)
		if rr.Code != c.code {
			t.Errorf("beacon_port %d: code = %d, want %d (%s)", c.port, rr.Code, c.code, rr.Body.String())
		}
	}
}

// H1/H3: chosen ports persist across a reload of the store.
func TestSettingsPortsPersist(t *testing.T) {
	dir := t.TempDir()
	cfg, err := config.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	rr := putSettings(t, cfg, `{"peer_port":51000,"beacon_port":52000}`)
	if rr.Code != http.StatusOK {
		t.Fatalf("code = %d, want 200 (%s)", rr.Code, rr.Body.String())
	}
	var resp settingsView
	if err := json.Unmarshal(rr.Body.Bytes(), &resp); err != nil {
		t.Fatalf("decode: %v", err)
	}
	if resp.PeerPort != 51000 || resp.BeaconPort != 52000 {
		t.Fatalf("response ports = %d/%d, want 51000/52000", resp.PeerPort, resp.BeaconPort)
	}
	reopened, err := config.Open(dir)
	if err != nil {
		t.Fatal(err)
	}
	got := reopened.Get()
	if got.PeerPort != 51000 || got.EffectiveBeaconPort() != 52000 {
		t.Fatalf("persisted ports = %d/%d, want 51000/52000", got.PeerPort, got.EffectiveBeaconPort())
	}
}

func itoa(n int) string {
	if n == 0 {
		return "0"
	}
	neg := n < 0
	if neg {
		n = -n
	}
	var b [20]byte
	i := len(b)
	for n > 0 {
		i--
		b[i] = byte('0' + n%10)
		n /= 10
	}
	if neg {
		i--
		b[i] = '-'
	}
	return string(b[i:])
}
