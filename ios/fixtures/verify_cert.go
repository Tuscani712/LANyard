//go:build ignore

// Verifies a DER-encoded Ed25519 self-signed certificate produced by the Swift
// builder. Reads the DER from a file argument, or from stdin when the argument
// is "-". Exits non-zero unless every check passes:
//
//   - x509.ParseCertificate succeeds
//   - the Ed25519 self-signature verifies
//   - the subject CN starts with "LANyard "
//   - the validity window is sane (notAfter > notBefore)
//   - ExtKeyUsage contains both ServerAuth and ClientAuth
//
// Note: `cert.CheckSignatureFrom(cert)` deliberately cannot be used here. A
// self-signed *leaf* with BasicConstraints CA=false is, by RFC 5280 4.2.1.9,
// not permitted to sign certificates, so Go returns a ConstraintViolationError.
// The signature is instead verified directly with `CheckSignature`, which is
// exactly the Ed25519-over-TBS check we want.
//
// Run: go run ios/fixtures/verify_cert.go cert.der
//
//	swift run certgen <seed> | xxd -r -p | go run ios/fixtures/verify_cert.go -
package main

import (
	"crypto/ed25519"
	"crypto/sha256"
	"crypto/x509"
	"encoding/hex"
	"fmt"
	"io"
	"os"
	"strings"
)

func main() {
	if err := run(); err != nil {
		fmt.Fprintf(os.Stderr, "FAIL: %v\n", err)
		os.Exit(1)
	}
}

func run() error {
	if len(os.Args) != 2 {
		return fmt.Errorf("usage: verify_cert <der-file>|-")
	}

	var (
		der []byte
		err error
	)
	if os.Args[1] == "-" {
		der, err = io.ReadAll(os.Stdin)
	} else {
		der, err = os.ReadFile(os.Args[1])
	}
	if err != nil {
		return fmt.Errorf("reading DER: %w", err)
	}
	if len(der) == 0 {
		return fmt.Errorf("empty DER input")
	}

	cert, err := x509.ParseCertificate(der)
	if err != nil {
		return fmt.Errorf("x509.ParseCertificate: %w", err)
	}

	if _, ok := cert.PublicKey.(ed25519.PublicKey); !ok {
		return fmt.Errorf("public key is %T, want ed25519.PublicKey", cert.PublicKey)
	}
	if err := cert.CheckSignature(cert.SignatureAlgorithm, cert.RawTBSCertificate, cert.Signature); err != nil {
		return fmt.Errorf("signature verification: %w", err)
	}
	// A CA=false leaf must not be accepted as a certificate signer; report the
	// expected rejection so the constraint is explicitly asserted rather than
	// silently ignored.
	if err := cert.CheckSignatureFrom(cert); err == nil {
		return fmt.Errorf("CheckSignatureFrom unexpectedly accepted a CA=false self-signed leaf")
	}
	if !strings.HasPrefix(cert.Subject.CommonName, "LANyard ") {
		return fmt.Errorf("subject CN %q does not start with %q", cert.Subject.CommonName, "LANyard ")
	}
	if !cert.NotAfter.After(cert.NotBefore) {
		return fmt.Errorf("validity window is not sane: notBefore=%s notAfter=%s", cert.NotBefore, cert.NotAfter)
	}

	var haveServer, haveClient bool
	for _, u := range cert.ExtKeyUsage {
		switch u {
		case x509.ExtKeyUsageServerAuth:
			haveServer = true
		case x509.ExtKeyUsageClientAuth:
			haveClient = true
		}
	}
	if !haveServer || !haveClient {
		return fmt.Errorf("ExtKeyUsage missing serverAuth/clientAuth: %v", cert.ExtKeyUsage)
	}

	sum := sha256.Sum256(cert.RawSubjectPublicKeyInfo)
	fmt.Println("PASS")
	fmt.Printf("  subject:     %s\n", cert.Subject.CommonName)
	fmt.Printf("  validity:    %s .. %s\n", cert.NotBefore.UTC().Format("2006-01-02T15:04:05Z"), cert.NotAfter.UTC().Format("2006-01-02T15:04:05Z"))
	fmt.Printf("  ext key use: serverAuth, clientAuth\n")
	fmt.Printf("  fingerprint: %s\n", hex.EncodeToString(sum[:]))
	return nil
}
