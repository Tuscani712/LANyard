package uiserver

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/http"
	"strconv"
	"strings"
	"time"

	"lanyard/internal/config"
	"lanyard/internal/diag"
	"lanyard/internal/discovery"
	"lanyard/internal/inbox"
	"lanyard/internal/lanaddr"
	"lanyard/internal/xferlog"
)

// diagEnv adapts the running server to the diagnostics checks.
type diagEnv struct {
	s      *Server
	device string
}

func (e diagEnv) RequestedPeerPort() int {
	if e.s.d.Self == nil {
		return 0
	}
	return e.s.d.Self().PeerPortRequested
}

func (e diagEnv) PeerPort() int {
	if e.s.d.Self == nil {
		return 0
	}
	return e.s.d.Self().PeerPort
}

func (e diagEnv) PeerPortFallback() bool {
	if e.s.d.Self == nil {
		return false
	}
	return e.s.d.Self().PeerPortFallback
}

// LocalAddrs for diagnostics includes IPv6 link-local (which the pairing link
// drops) so the report is complete.
func (e diagEnv) LocalAddrs() []string { return lanaddr.AllAddrs() }

func (e diagEnv) Peers() []diag.Peer {
	if e.s.d.Peers == nil {
		return nil
	}
	src := e.s.d.Peers()
	out := make([]diag.Peer, 0, len(src))
	for _, p := range src {
		out = append(out, toDiagPeer(p))
	}
	return out
}

func (e diagEnv) PeerByID(id string) (diag.Peer, bool) {
	p, ok := e.s.peerByID(id)
	if !ok {
		return diag.Peer{}, false
	}
	return toDiagPeer(p), true
}

func toDiagPeer(p discovery.Peer) diag.Peer {
	return diag.Peer{
		DeviceID: p.DeviceID, Name: p.Name, Addrs: p.Addrs,
		Port: p.Port, Source: p.Source, LastSeen: p.LastSeen,
	}
}

func (e diagEnv) TargetDevice() string { return e.device }

func (e diagEnv) DialTCP(ctx context.Context, host string, port int) error {
	d := net.Dialer{Timeout: 3 * time.Second}
	c, err := d.DialContext(ctx, "tcp", net.JoinHostPort(host, strconv.Itoa(port)))
	if err != nil {
		return err
	}
	_ = c.Close()
	return nil
}

func (e diagEnv) Probe(ctx context.Context, host string, port int) (string, error) {
	if e.s.d.Client == nil {
		return "", errors.New("peer client unavailable")
	}
	fp, _, err := e.s.d.Client.Probe(ctx, host, port)
	return fp, err
}

func (e diagEnv) FreeSpace(dir string) int64 { return inbox.FreeSpace(dir) }

func (e diagEnv) InboxDir() string {
	if e.s.d.Inbox == nil {
		return ""
	}
	return e.s.d.Inbox.Dir()
}

func (e diagEnv) DownloadDir() string {
	if e.s.d.Cfg == nil {
		return ""
	}
	return e.s.d.Cfg.Get().DefaultDownloadFolder
}

// PeerHelloTime reports that the protocol carries no clock. The wizard then
// marks the clock check as skipped rather than inventing one.
func (e diagEnv) PeerHelloTime() (time.Time, bool) { return time.Time{}, false }

func (e diagEnv) Now() time.Time { return time.Now() }

// handleDiagnostics runs the checks and returns them plus a plain-text report
// with no secrets (no tokens, nonces or full fingerprints).
func (s *Server) handleDiagnostics(w http.ResponseWriter, r *http.Request) {
	if s.d.Cfg == nil || s.d.Self == nil {
		http.Error(w, "diagnostics unavailable", http.StatusServiceUnavailable)
		return
	}
	device := strings.TrimSpace(r.URL.Query().Get("device"))
	ctx, cancel := context.WithTimeout(r.Context(), 20*time.Second)
	defer cancel()
	self := s.d.Self()
	checks := diag.Run(ctx, diagEnv{s: s, device: device})
	report := diag.Report(checks)
	// The four diagnostic areas — discovery, pairing, pushing and pulling — are
	// recorded by the discovery manager, the trust store, the transfer manager,
	// the peer service and the inbox reaper into one shared recorder. It is
	// shown in the panel, folded into the report, and offered on its own to
	// "Copy log".
	rec := s.d.XferLog
	if rec == nil && s.d.Transfers != nil {
		rec = s.d.Transfers.XferLog()
	}
	var entries []xferlog.Entry
	log := ""
	if rec != nil {
		entries = rec.Entries()
		log = rec.Report()
		if log != "" {
			report += "\n" + log
		}
	}
	beaconPort := config.DefaultBeaconPort
	if s.d.Cfg != nil {
		beaconPort = s.d.Cfg.Get().EffectiveBeaconPort()
	}
	writeJSON(w, map[string]any{
		"checks": checks, "report": report, "log": log, "transfer_log": entries,
		"peer_port":                 self.PeerPort,
		"peer_port_fallback":        self.PeerPortFallback,
		"peer_port_fallback_notice": self.PeerPortFallbackNotice,
		"beacon_port":               beaconPort,
		"firewall_commands":         FirewallCommands(self.PeerPort, beaconPort),
		"firewall_banner":           firewallBannerNeeded(self, checks),
	})
}

// FirewallCommands builds the copy-paste firewall rule for the ports LANyard
// actually uses: the peer TCP port it bound (or its configured/default value),
// the configured beacon UDP port, and the fixed mDNS port. Generating it from
// the live values is what keeps the banner, its Copy button and the README in
// step when a port changes.
func FirewallCommands(peerPort, beaconPort int) string {
	if peerPort <= 0 {
		peerPort = config.DefaultPeerPort
	}
	if beaconPort <= 0 {
		beaconPort = config.DefaultBeaconPort
	}
	return fmt.Sprintf("sudo ufw allow %d/tcp && sudo ufw allow %d/udp && sudo ufw allow %d/udp",
		peerPort, beaconPort, config.MDNSPort)
}

// defaultPeerPort is the port the peer service prefers. A firewall rule opened
// for it does not cover the random fallback port.
const defaultPeerPort = config.DefaultPeerPort

// firewallBannerNeeded reports whether the UI should show the actionable
// "other devices can't reach this computer" banner: the peer service bound a
// port other than the default, or the firewall check found inbound
// reachability blocked (warn/fail, not skipped).
func firewallBannerNeeded(self SelfInfo, checks []diag.Check) bool {
	if self.PeerPort > 0 && self.PeerPort != defaultPeerPort {
		return true
	}
	for _, c := range checks {
		if c.ID == "firewall" && (c.Status == diag.StatusWarn || c.Status == diag.StatusFail) {
			return true
		}
	}
	return false
}
