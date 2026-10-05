// Package discovery finds other LANyard instances on the network using mDNS,
// a UDP broadcast beacon fallback, and manual addresses.
package discovery

import (
	"context"
	"net"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

const ProtocolVersion = "2"

// Announcement is what a node advertises about itself (mDNS TXT / beacon).
type Announcement struct {
	Version     string `json:"v"`
	ShortID     string `json:"id"`
	DeviceLabel string `json:"did,omitempty"` // user-facing Device ID label (§11.1)
	Name        string `json:"n"`
	OS          string `json:"os"`
	Port        int    `json:"p"`
}

// Hello is the response from a peer's /hello endpoint.
type Hello struct {
	DeviceID    string `json:"device_id"` // user-facing Device ID label
	Fingerprint string `json:"fingerprint"`
	Name        string `json:"name"`
	OS          string `json:"os"`
	Version     string `json:"version"`
	Port        int    `json:"port"`
}

// Prober dials a peer over TLS and returns the Device ID taken from the
// certificate actually presented (never from the JSON body) plus its Hello.
type Prober func(ctx context.Context, host string, port int) (certID string, h *Hello, err error)

type Peer struct {
	ShortID     string    `json:"short_id"`
	DeviceID    string    `json:"device_id"` // full fingerprint, meaningful once Verified
	DeviceLabel string    `json:"device_label,omitempty"`
	Name        string    `json:"name"`
	OS          string    `json:"os"`
	Addrs       []string  `json:"addrs"`
	Port        int       `json:"port"`
	Verified    bool      `json:"verified"`
	Source      string    `json:"source"`
	LastSeen    time.Time `json:"last_seen"`
	State       string    `json:"state"`

	fails     int
	lastProbe time.Time
	probing   bool
}

type registry struct {
	mu    sync.Mutex
	peers map[string]*Peer // keyed by ShortID
	subs  map[chan struct{}]struct{}
}

func newRegistry() *registry {
	return &registry{peers: map[string]*Peer{}, subs: map[chan struct{}]struct{}{}}
}

// notify must be called with r.mu held.
func (r *registry) notify() {
	for ch := range r.subs {
		select {
		case ch <- struct{}{}:
		default:
		}
	}
}

func (r *registry) subscribe() (<-chan struct{}, func()) {
	ch := make(chan struct{}, 1)
	r.mu.Lock()
	r.subs[ch] = struct{}{}
	r.mu.Unlock()
	return ch, func() {
		r.mu.Lock()
		delete(r.subs, ch)
		r.mu.Unlock()
	}
}

func (r *registry) list() []Peer {
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]Peer, 0, len(r.peers))
	for _, p := range r.peers {
		c := *p
		c.Addrs = append([]string(nil), p.Addrs...)
		out = append(out, c)
	}
	sort.Slice(out, func(i, j int) bool {
		a, b := strings.ToLower(out[i].Name), strings.ToLower(out[j].Name)
		if a != b {
			return a < b
		}
		return out[i].ShortID < out[j].ShortID
	})
	return out
}

// upsert records an announcement and returns a copy of the peer.
func (r *registry) upsert(a Announcement, ips []string, source string) Peer {
	r.mu.Lock()
	defer r.mu.Unlock()
	p, ok := r.peers[a.ShortID]
	if !ok {
		p = &Peer{ShortID: a.ShortID, State: "Unpaired"}
		r.peers[a.ShortID] = p
	}
	changed := !ok || p.Name != a.Name || p.Port != a.Port || p.OS != a.OS || (a.DeviceLabel != "" && p.DeviceLabel != a.DeviceLabel)
	p.Name, p.OS, p.Port = a.Name, a.OS, a.Port
	if a.DeviceLabel != "" {
		p.DeviceLabel = a.DeviceLabel
	}
	p.LastSeen = time.Now()
	if p.Source == "" || source == "mdns" {
		p.Source = source
	}
	for _, ip := range ips {
		if !contains(p.Addrs, ip) {
			p.Addrs = append(p.Addrs, ip)
			changed = true
		}
	}
	if changed {
		r.notify()
	}
	c := *p
	c.Addrs = append([]string(nil), p.Addrs...)
	return c
}

func (r *registry) remove(shortID string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if _, ok := r.peers[shortID]; ok {
		delete(r.peers, shortID)
		r.notify()
	}
}

func contains(s []string, v string) bool {
	for _, x := range s {
		if x == v {
			return true
		}
	}
	return false
}

// usableIP filters addresses that cannot be dialed without an interface zone.
func usableIP(ip net.IP) bool {
	if ip == nil || ip.IsUnspecified() || ip.IsLoopback() || ip.IsMulticast() {
		return false
	}
	if ip.To4() == nil && ip.IsLinkLocalUnicast() {
		return false
	}
	return true
}

func hostPort(host string, port int) string {
	return net.JoinHostPort(host, strconv.Itoa(port))
}
