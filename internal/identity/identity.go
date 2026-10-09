// Package identity manages the device's long-term Ed25519 key, its self-signed
// certificate, and the fingerprint-based Device ID.
package identity

import (
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/binary"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"math/big"
	"os"
	"path/filepath"
	"strings"
	"time"

	"lanyard/internal/config"
)

type Identity struct {
	Cert     tls.Certificate
	DeviceID string // lowercase hex SHA-256 of the certificate's public key (64 chars)
}

// FingerprintOf returns the Device ID for a certificate.
func FingerprintOf(c *x509.Certificate) string {
	sum := sha256.Sum256(c.RawSubjectPublicKeyInfo)
	return hex.EncodeToString(sum[:])
}

// SAS computes the 6-digit Short Authentication String that binds both
// certificate fingerprints and both session nonces (spec §3.4). The two
// fingerprints are sorted and each nonce is carried with its fingerprint's
// sorted position, so both sides derive the same code without having to agree
// on who sent the first nonce. A man-in-the-middle presenting different
// certificates to each side obtains different fingerprints and therefore a
// different code.
func SAS(fpA, fpB, nonceA, nonceB string) string {
	a, b, na, nb := fpA, fpB, nonceA, nonceB
	if a > b {
		a, b, na, nb = b, a, nb, na
	}
	h := sha256.New()
	h.Write([]byte(a))
	h.Write([]byte{0})
	h.Write([]byte(b))
	h.Write([]byte{0})
	h.Write([]byte(na))
	h.Write([]byte{0})
	h.Write([]byte(nb))
	sum := h.Sum(nil)
	n := binary.BigEndian.Uint32(sum[:4]) % 1_000_000
	return fmt.Sprintf("%06d", n)
}

// ShortID is the prefix advertised in discovery records.
func ShortID(id string) string {
	if len(id) > 16 {
		return id[:16]
	}
	return id
}

// Pretty formats an ID as groups of 4 hex chars, upper case.
func Pretty(id string) string {
	id = strings.ToUpper(id)
	var parts []string
	for i := 0; i < len(id); i += 4 {
		j := i + 4
		if j > len(id) {
			j = len(id)
		}
		parts = append(parts, id[i:j])
	}
	return strings.Join(parts, " ")
}

// LoadOrCreate loads the identity from dir, generating it on first run.
func LoadOrCreate(dir, deviceName string) (*Identity, error) {
	certPath := filepath.Join(dir, "identity.crt")
	keyPath := filepath.Join(dir, "identity.key")

	if cert, err := tls.LoadX509KeyPair(certPath, keyPath); err == nil {
		leaf, perr := x509.ParseCertificate(cert.Certificate[0])
		if perr == nil && time.Now().Before(leaf.NotAfter) {
			cert.Leaf = leaf
			return &Identity{Cert: cert, DeviceID: FingerprintOf(leaf)}, nil
		}
	} else if !errors.Is(err, os.ErrNotExist) {
		// Unreadable or corrupt files: refuse to silently replace an identity
		// that peers may have paired with.
		if _, e1 := os.Stat(certPath); e1 == nil {
			return nil, err
		}
	}

	pub, priv, err := ed25519.GenerateKey(rand.Reader)
	if err != nil {
		return nil, err
	}
	serial, _ := rand.Int(rand.Reader, new(big.Int).Lsh(big.NewInt(1), 120))
	tmpl := &x509.Certificate{
		SerialNumber:          serial,
		Subject:               pkix.Name{CommonName: "LANyard " + deviceName},
		NotBefore:             time.Now().Add(-time.Hour),
		NotAfter:              time.Now().AddDate(10, 0, 0),
		KeyUsage:              x509.KeyUsageDigitalSignature,
		ExtKeyUsage:           []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth, x509.ExtKeyUsageClientAuth},
		BasicConstraintsValid: true,
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, pub, priv)
	if err != nil {
		return nil, err
	}
	keyDER, err := x509.MarshalPKCS8PrivateKey(priv)
	if err != nil {
		return nil, err
	}
	certPEM := pem.EncodeToMemory(&pem.Block{Type: "CERTIFICATE", Bytes: der})
	keyPEM := pem.EncodeToMemory(&pem.Block{Type: "PRIVATE KEY", Bytes: keyDER})
	if err := config.WriteFileAtomic(keyPath, keyPEM, 0o600); err != nil {
		return nil, err
	}
	if err := config.WriteFileAtomic(certPath, certPEM, 0o644); err != nil {
		return nil, err
	}
	cert, err := tls.X509KeyPair(certPEM, keyPEM)
	if err != nil {
		return nil, err
	}
	leaf, _ := x509.ParseCertificate(der)
	cert.Leaf = leaf
	return &Identity{Cert: cert, DeviceID: FingerprintOf(leaf)}, nil
}
