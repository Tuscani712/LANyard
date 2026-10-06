// Package diag runs the diagnostics wizard: a set of independent checks that
// explain why discovery or a transfer may not be working, in plain language,
// without needing to read logs. It is pure: every side effect is reached
// through the Env interface, so the logic is testable with a fake environment.
package diag

import (
	"context"
	"fmt"
	"regexp"
	"strings"
	"sync"
	"time"
)

// Status is the outcome of one check.
type Status string

const (
	StatusOK   Status = "ok"
	StatusWarn Status = "warn"
	StatusFail Status = "fail"
	StatusSkip Status = "skip"
)

// Check is one diagnostic result.
type Check struct {
	ID     string `json:"id"`
	Title  string `json:"title"`
	Status Status `json:"status"`
	Detail string `json:"detail,omitempty"`
	Fix    string `json:"fix,omitempty"`
}

// Peer is the small view of a discovered peer the checks need.
type Peer struct {
	DeviceID string
	Name     string
	Addrs    []string
	Port     int
	Source   string // "mdns", "beacon", "manual", …
	LastSeen time.Time
}

// Env supplies the environment the checks inspect. Tests provide a fake.
type Env interface {
	// ConfiguredPeerPort is the port the user asked for (settings); PeerPort is
	// the port the server actually bound (it may differ if the first was busy).
	ConfiguredPeerPort() int
	PeerPort() int
	// LocalAddrs are this host's private/link-local addresses.
	LocalAddrs() []string
	Peers() []Peer
	PeerByID(id string) (Peer, bool)
	// TargetDevice is the optional peer to check reachability for ("" = none).
	TargetDevice() string

	// DialTCP opens and closes a plain TCP connection.
	DialTCP(ctx context.Context, host string, port int) error
	// Probe performs a TLS handshake and returns the presented certificate
	// fingerprint (not trusted; the caller compares it).
	Probe(ctx context.Context, host string, port int) (fingerprint string, err error)

	// FreeSpace returns bytes free on the volume holding dir (0 = unknown).
	FreeSpace(dir string) int64
	InboxDir() string
	DownloadDir() string

	// PeerHelloTime returns the peer's clock if the protocol carries one.
	PeerHelloTime() (time.Time, bool)
	// Now is the current time (faked in tests).
	Now() time.Time
}

// Timeouts. Each check gets a short budget; a hung probe must not stall the
// wizard.
const (
	checkTimeout = 4 * time.Second
	recentWindow = 60 * time.Second
	lowDisk      = 100 << 20 // warn below 100 MB free
	clockDrift   = 2 * time.Minute
)

// Run executes every check and returns them in a fixed order. Independent
// checks run in parallel; each is bounded by checkTimeout.
func Run(ctx context.Context, env Env) []Check {
	fns := []func(context.Context, Env) Check{
		checkPeerPort, checkLocalAddrs, checkDiscovery, checkFirewall,
		checkPeer, checkClock, checkDisk,
	}
	out := make([]Check, len(fns))
	var wg sync.WaitGroup
	for i, fn := range fns {
		wg.Add(1)
		go func(i int, fn func(context.Context, Env) Check) {
			defer wg.Done()
			cctx, cancel := context.WithTimeout(ctx, checkTimeout)
			defer cancel()
			out[i] = fn(cctx, env)
		}(i, fn)
	}
	wg.Wait()
	return out
}

func checkPeerPort(ctx context.Context, env Env) Check {
	c := Check{ID: "peer-port", Title: "Peer service port"}
	actual := env.PeerPort()
	if actual <= 0 {
		c.Status = StatusFail
		c.Detail = "LANyard is not listening for peer connections."
		c.Fix = "Restart LANyard."
		return c
	}
	if want := env.ConfiguredPeerPort(); want != 0 && want != actual {
		c.Status = StatusWarn
		c.Detail = fmt.Sprintf("Port %d was in use by another program, so the peer service is running on port %d instead.", want, actual)
		c.Fix = "Free that port or allow LANyard through the firewall on its current port."
		return c
	}
	if err := env.DialTCP(ctx, "127.0.0.1", actual); err != nil {
		c.Status = StatusFail
		c.Detail = fmt.Sprintf("Nothing is answering on peer port %d.", actual)
		c.Fix = "Restart LANyard; if it still fails, another program may be holding the port."
		return c
	}
	c.Status = StatusOK
	c.Detail = fmt.Sprintf("The peer service is listening on port %d.", actual)
	return c
}

func checkLocalAddrs(_ context.Context, env Env) Check {
	c := Check{ID: "local-addrs", Title: "Local network addresses"}
	addrs := env.LocalAddrs()
	if len(addrs) == 0 {
		c.Status = StatusFail
		c.Detail = "No private LAN address was found on this device."
		c.Fix = "Connect to the same Wi-Fi or Ethernet network as the other device."
		return c
	}
	c.Status = StatusOK
	shown := addrs
	if len(shown) > 4 {
		shown = shown[:4]
	}
	c.Detail = fmt.Sprintf("LAN address(es): %s.", strings.Join(shown, ", "))
	return c
}

func checkDiscovery(_ context.Context, env Env) Check {
	c := Check{ID: "discovery", Title: "Device discovery"}
	now := env.Now()
	var viaCast, manual int
	for _, p := range env.Peers() {
		if p.LastSeen.IsZero() || now.Sub(p.LastSeen) > recentWindow {
			continue
		}
		if p.Source == "manual" {
			manual++
		} else {
			viaCast++
		}
	}
	switch {
	case viaCast > 0:
		c.Status = StatusOK
		c.Detail = fmt.Sprintf("Seeing %d device(s) automatically (mDNS/beacon).", viaCast)
	case manual > 0:
		c.Status = StatusWarn
		c.Detail = "Only manually added devices are visible; automatic discovery is not receiving anything."
		c.Fix = "Some networks block multicast. Add the other device by address, or pair it with a QR code."
	default:
		c.Status = StatusWarn
		c.Detail = "No devices were seen in the last minute."
		c.Fix = "Make sure the other device is running LANyard on the same network. Guest Wi-Fi and some routers block mDNS; try Add by address."
	}
	return c
}

func checkFirewall(ctx context.Context, env Env) Check {
	c := Check{ID: "firewall", Title: "Inbound reachability (firewall)"}
	port := env.PeerPort()
	addrs := env.LocalAddrs()
	if port <= 0 || len(addrs) == 0 {
		c.Status = StatusSkip
		c.Detail = "No LAN address to probe."
		return c
	}
	_, loopback := env.Probe(ctx, "127.0.0.1", port)
	_, lan := env.Probe(ctx, addrs[0], port)
	switch {
	case loopback == nil && lan == nil:
		c.Status = StatusOK
		c.Detail = fmt.Sprintf("The peer service is reachable on %s:%d.", addrs[0], port)
	case loopback == nil && lan != nil:
		c.Status = StatusWarn
		c.Detail = fmt.Sprintf("Reachable on loopback but not on %s:%d. The host firewall is most likely blocking inbound connections.", addrs[0], port)
		c.Fix = fmt.Sprintf("Allow LANyard (or TCP port %d) through your firewall for private networks.", port)
	default:
		c.Status = StatusWarn
		c.Detail = "Could not reach the peer service even on loopback."
		c.Fix = "Restart LANyard and try again."
	}
	return c
}

func checkPeer(ctx context.Context, env Env) Check {
	c := Check{ID: "peer", Title: "Reach a device"}
	id := env.TargetDevice()
	if id == "" {
		c.Status = StatusSkip
		c.Detail = "Pick a device to check its reachability."
		return c
	}
	p, ok := env.PeerByID(id)
	if !ok {
		c.Status = StatusFail
		c.Detail = "That device is not currently visible."
		c.Fix = "Make sure it is on and on the same network, or add it by address."
		return c
	}
	who := p.Name
	if who == "" {
		who = short(id)
	}
	if len(p.Addrs) == 0 || p.Port == 0 {
		c.Status = StatusFail
		c.Detail = fmt.Sprintf("%s has no known address.", who)
		c.Fix = "Rediscover the device or add it by address."
		return c
	}
	host := p.Addrs[0]
	if err := env.DialTCP(ctx, host, p.Port); err != nil {
		c.Status = StatusFail
		c.Detail = fmt.Sprintf("Could not open a TCP connection to %s (%s:%d).", who, host, p.Port)
		c.Fix = "Check the device is powered on and the firewall allows LANyard. A changed address may need a fresh discovery."
		return c
	}
	certFP, err := env.Probe(ctx, host, p.Port)
	if err != nil {
		c.Status = StatusFail
		c.Detail = fmt.Sprintf("Connected to %s but the encrypted handshake failed.", who)
		c.Fix = "The other device may be busy or running a much older version. Try again."
		return c
	}
	if certFP == "" {
		c.Status = StatusFail
		c.Detail = fmt.Sprintf("%s did not present a certificate.", who)
		c.Fix = "This is not expected; make sure the other device is running LANyard."
		return c
	}
	if expected := expectedFP(p); expected != "" && !fingerprintMatches(certFP, expected) {
		c.Status = StatusFail
		c.Detail = fmt.Sprintf("%s's identity has changed (presented %s…, expected %s…).", who, short(certFP), short(expected))
		c.Fix = "Do not trust it until you have verified the other device in person. Unpair and pair it again only if you are sure."
		return c
	}
	c.Status = StatusOK
	c.Detail = fmt.Sprintf("TCP, encrypted handshake and identity all check out with %s (%s…).", who, short(certFP))
	return c
}

func checkClock(_ context.Context, env Env) Check {
	c := Check{ID: "clock", Title: "Clock difference"}
	remote, ok := env.PeerHelloTime()
	if !ok {
		c.Status = StatusSkip
		c.Detail = "The protocol does not carry the other device's clock, so this cannot be checked."
		return c
	}
	drift := env.Now().Sub(remote)
	if drift < 0 {
		drift = -drift
	}
	if drift > clockDrift {
		c.Status = StatusWarn
		c.Detail = fmt.Sprintf("The other device's clock differs by about %s.", drift.Round(time.Second))
		c.Fix = "Set both devices to update their time automatically; large differences can break secure connections."
		return c
	}
	c.Status = StatusOK
	c.Detail = "The clocks are close enough."
	return c
}

func checkDisk(_ context.Context, env Env) Check {
	c := Check{ID: "disk", Title: "Free disk space"}
	type target struct{ name, dir string }
	targets := []target{{"Inbox", env.InboxDir()}, {"Downloads", env.DownloadDir()}}
	var parts []string
	worst := StatusOK
	for _, t := range targets {
		if strings.TrimSpace(t.dir) == "" {
			continue
		}
		free := env.FreeSpace(t.dir)
		if free <= 0 {
			parts = append(parts, t.name+": unknown")
			if worst == StatusOK {
				worst = StatusWarn
			}
			continue
		}
		parts = append(parts, fmt.Sprintf("%s: %s free", t.name, humanBytes(free)))
		if free < lowDisk {
			worst = StatusWarn
		}
	}
	if len(parts) == 0 {
		c.Status = StatusSkip
		c.Detail = "No Inbox or download folder is configured."
		return c
	}
	c.Status = worst
	c.Detail = strings.Join(parts, " · ") + "."
	if worst == StatusWarn {
		c.Fix = "Free up space so transfers have room to finish."
	}
	return c
}

// expectedFP returns the fingerprint a probe result must match: the full
// Device ID when the peer has been verified, otherwise its advertised prefix.
func expectedFP(p Peer) string {
	if p.DeviceID != "" {
		return p.DeviceID
	}
	return ""
}

// fingerprintMatches compares a presented fingerprint to an expected one: an
// exact match for a full fingerprint, otherwise a prefix match (an unverified
// peer only advertises a 16-hex prefix).
func fingerprintMatches(got, expected string) bool {
	if len(expected) == 64 {
		return got == expected
	}
	return strings.HasPrefix(got, expected)
}

func short(fp string) string {
	if len(fp) > 8 {
		return fp[:8]
	}
	return fp
}

func humanBytes(n int64) string {
	const unit = 1024
	if n < unit {
		return fmt.Sprintf("%d B", n)
	}
	div, exp := int64(unit), 0
	for v := n / unit; v >= unit; v /= unit {
		div *= unit
		exp++
	}
	return fmt.Sprintf("%.1f %cB", float64(n)/float64(div), "KMGTPE"[exp])
}

var fullFP = regexp.MustCompile(`\b[0-9a-fA-F]{64}\b`)

// redact removes anything that looks like a full fingerprint. Checks avoid
// emitting them, so this is a defence in depth for the copyable report.
func redact(s string) string {
	return fullFP.ReplaceAllString(s, "<fingerprint>")
}

// Report renders the checks as plain text for the "Copy report" button. It
// contains no tokens, invite nonces or full fingerprints.
func Report(checks []Check) string {
	var b strings.Builder
	b.WriteString("LANyard diagnostics\n")
	for _, c := range checks {
		fmt.Fprintf(&b, "[%s] %s: %s\n", statusLabel(c.Status), c.Title, redact(c.Detail))
		if c.Fix != "" {
			fmt.Fprintf(&b, "    Fix: %s\n", redact(c.Fix))
		}
	}
	return b.String()
}

func statusLabel(s Status) string {
	switch s {
	case StatusOK:
		return "OK"
	case StatusWarn:
		return "WARN"
	case StatusFail:
		return "FAIL"
	default:
		return "SKIP"
	}
}
