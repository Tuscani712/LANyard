// Package pairlink builds and parses the LANyard pairing link carried by a QR
// code (or pasted as text). The link binds a device fingerprint, a display
// name, a list of reachable addresses and a one-time invite nonce. The
// fingerprint is pinned by the scanning side, so the link is what replaces the
// 6-digit code compare for QR pairing.
package pairlink

import (
	"errors"
	"net"
	"net/url"
	"strconv"
	"strings"
)

const (
	// Scheme and authority of the pairing link.
	Scheme = "lanyard"
	Host   = "pair"

	// MaxLen caps the whole link; MaxAddrs caps how many addresses it carries.
	// Both keep a hostile link from growing without bound.
	MaxLen   = 2048
	MaxAddrs = 16
	// maxAddrLen caps a single host:port.
	maxAddrLen = 255
	// maxNameLen caps the display name (matching the UI's own limit).
	maxNameLen = 200

	minNonceLen = 32 // 128 bits, hex-encoded
	maxNonceLen = 128
)

var errMalformed = errors.New("malformed pairing link")

// Payload is the decoded pairing link.
type Payload struct {
	Fingerprint string   // full certificate fingerprint (Device ID)
	Name        string   // display name (optional)
	Addrs       []string // host:port, at least one
	Nonce       string   // one-time invite token (hex)
}

// Build renders p as a pairing link.
func Build(p Payload) string {
	q := url.Values{}
	q.Set("fp", p.Fingerprint)
	if p.Name != "" {
		q.Set("name", p.Name)
	}
	q.Set("addr", strings.Join(p.Addrs, ","))
	q.Set("n", p.Nonce)
	return Scheme + "://" + Host + "?" + q.Encode()
}

// Parse decodes a pairing link. It rejects anything malformed, any unexpected
// or duplicated parameter, oversized input, and too many addresses.
func Parse(raw string) (Payload, error) {
	if raw == "" || len(raw) > MaxLen {
		return Payload{}, errMalformed
	}
	u, err := url.Parse(raw)
	if err != nil || u.Scheme != Scheme || u.Host != Host {
		return Payload{}, errMalformed
	}
	q := u.Query()
	for k := range q {
		switch k {
		case "fp", "name", "addr", "n":
		default:
			return Payload{}, errMalformed
		}
	}
	for _, k := range []string{"fp", "addr", "n"} {
		if len(q[k]) != 1 {
			return Payload{}, errMalformed
		}
	}
	if len(q["name"]) > 1 {
		return Payload{}, errMalformed
	}

	fp := q.Get("fp")
	if !isHex(fp, 64, 64) {
		return Payload{}, errMalformed
	}
	nonce := q.Get("n")
	if !isHex(nonce, minNonceLen, maxNonceLen) {
		return Payload{}, errMalformed
	}

	name := q.Get("name")
	if len(name) > maxNameLen {
		name = name[:maxNameLen]
	}

	parts := strings.Split(q.Get("addr"), ",")
	addrs := make([]string, 0, len(parts))
	for _, a := range parts {
		a = strings.TrimSpace(a)
		if a == "" {
			continue
		}
		if !validAddr(a) {
			return Payload{}, errMalformed
		}
		if len(addrs) >= MaxAddrs {
			return Payload{}, errMalformed
		}
		addrs = append(addrs, a)
	}
	if len(addrs) == 0 {
		return Payload{}, errMalformed
	}
	return Payload{Fingerprint: fp, Name: name, Addrs: addrs, Nonce: nonce}, nil
}

// validAddr accepts a dialable host:port. The host must be an IP literal, so a
// link cannot smuggle a DNS name or a URL through the address field.
func validAddr(a string) bool {
	if len(a) > maxAddrLen {
		return false
	}
	host, portStr, err := net.SplitHostPort(a)
	if err != nil || net.ParseIP(host) == nil {
		return false
	}
	port, err := strconv.Atoi(portStr)
	return err == nil && port >= 1 && port <= 65535
}

func isHex(s string, min, max int) bool {
	if len(s) < min || len(s) > max || len(s)%2 != 0 {
		return false
	}
	for _, c := range s {
		switch {
		case c >= '0' && c <= '9', c >= 'a' && c <= 'f', c >= 'A' && c <= 'F':
		default:
			return false
		}
	}
	return true
}
