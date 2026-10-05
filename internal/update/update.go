// Package update implements the LANyard self-update check: fetch a manifest
// over HTTPS, compare its version with the running one, and download a release
// only after verifying its size, SHA-256 and (when a public key is configured)
// an Ed25519 signature.
//
// It is deliberately inert until a manifest URL is configured: the release
// channel is not live yet, so nothing is fetched by default. The URL must be
// https; the download URL is checked again when the file is fetched.
package update

import (
	"context"
	"crypto/ed25519"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net/http"
	"net/url"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
	"time"
)

// EmbeddedPublicKey, when non-empty, is the Ed25519 key that release
// signatures must verify against. It is empty until the release channel goes
// live; while empty, signature checking is skipped and only SHA-256 is used.
var EmbeddedPublicKey ed25519.PublicKey

// DefaultManifestURL is the release channel manifest. Empty means updates are
// disabled (the current state — the channel is not live yet).
const DefaultManifestURL = ""

// maxManifestBytes caps the manifest; maxDownloadBytes caps a release file.
const (
	maxManifestBytes = 1 << 20   // 1 MiB
	maxDownloadBytes = 512 << 20 // 512 MiB
)

// File is one platform build listed in the manifest.
type File struct {
	URL       string `json:"url"`
	SHA256    string `json:"sha256"`
	Size      int64  `json:"size,omitempty"`
	Signature string `json:"signature,omitempty"` // base64 Ed25519 over the file's SHA-256 digest
}

// Manifest is the release-channel document, e.g.
//
//	{
//	  "version": "1.0.1",
//	  "released": "2026-10-10T12:00:00Z",
//	  "notes": "…",
//	  "files": {
//	    "windows-amd64": {"url":"https://…/lanyard.exe","sha256":"…","size":123,"signature":"…"}
//	  }
//	}
type Manifest struct {
	Version  string          `json:"version"`
	Released string          `json:"released,omitempty"`
	Notes    string          `json:"notes,omitempty"`
	Files    map[string]File `json:"files"`
}

// Result describes the outcome of a check.
type Result struct {
	Configured bool   `json:"configured"`
	Current    string `json:"current"`
	Latest     string `json:"latest"`
	Available  bool   `json:"available"`
	Platform   string `json:"platform"`
	Notes      string `json:"notes,omitempty"`
	URL        string `json:"url,omitempty"`
	SHA256     string `json:"sha256,omitempty"`
	Size       int64  `json:"size,omitempty"`
	Signature  string `json:"signature,omitempty"`
}

// Platform is the manifest key for the running build, e.g. "windows-amd64".
func Platform() string { return runtime.GOOS + "-" + runtime.GOARCH }

// parseVersion splits a version like "v1.2.3-rc1" into numbers and a
// pre-release tag.
type parsedVersion struct {
	num [3]int
	pre string
}

func parseVersion(s string) parsedVersion {
	s = strings.TrimSpace(s)
	s = strings.TrimPrefix(s, "v")
	s = strings.TrimPrefix(s, "V")
	pre := ""
	if i := strings.IndexAny(s, "-+"); i >= 0 {
		pre = s[i+1:]
		s = s[:i]
	}
	var v parsedVersion
	v.pre = pre
	for i, part := range strings.Split(s, ".") {
		if i >= 3 {
			break
		}
		n, _ := strconv.Atoi(strings.TrimSpace(part))
		v.num[i] = n
	}
	return v
}

// Compare returns -1 if a < b, 0 if equal, 1 if a > b. A pre-release sorts
// below the release with the same numbers (1.0.0-rc1 < 1.0.0).
func Compare(a, b string) int {
	pa, pb := parseVersion(a), parseVersion(b)
	for i := 0; i < 3; i++ {
		switch {
		case pa.num[i] < pb.num[i]:
			return -1
		case pa.num[i] > pb.num[i]:
			return 1
		}
	}
	if pa.pre == pb.pre {
		return 0
	}
	if pa.pre == "" {
		return 1
	}
	if pb.pre == "" {
		return -1
	}
	return strings.Compare(pa.pre, pb.pre)
}

// Fetch downloads and parses the manifest. The URL must be https.
func Fetch(ctx context.Context, client *http.Client, manifestURL string) (*Manifest, error) {
	if strings.TrimSpace(manifestURL) == "" {
		return nil, errors.New("no update URL is configured")
	}
	u, err := url.Parse(manifestURL)
	if err != nil {
		return nil, fmt.Errorf("bad update URL: %w", err)
	}
	if u.Scheme != "https" {
		return nil, errors.New("the update URL must use https")
	}
	if client == nil {
		client = &http.Client{Timeout: 30 * time.Second}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, manifestURL, nil)
	if err != nil {
		return nil, err
	}
	req.Header.Set("Accept", "application/json")
	resp, err := client.Do(req)
	if err != nil {
		return nil, err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("update manifest: %s", resp.Status)
	}
	body, err := io.ReadAll(io.LimitReader(resp.Body, maxManifestBytes))
	if err != nil {
		return nil, err
	}
	var m Manifest
	if err := json.Unmarshal(body, &m); err != nil {
		return nil, fmt.Errorf("update manifest is not valid JSON: %w", err)
	}
	if strings.TrimSpace(m.Version) == "" {
		return nil, errors.New("update manifest has no version")
	}
	for key, f := range m.Files {
		if f.URL != "" && !strings.HasPrefix(f.URL, "https://") {
			return nil, fmt.Errorf("manifest entry %q must use an https URL", key)
		}
	}
	return &m, nil
}

// Check fetches the manifest and reports whether a newer build exists for this
// platform.
func Check(ctx context.Context, client *http.Client, manifestURL, current string) (*Result, error) {
	m, err := Fetch(ctx, client, manifestURL)
	if err != nil {
		return nil, err
	}
	plat := Platform()
	r := &Result{Configured: true, Current: current, Latest: m.Version, Platform: plat, Notes: m.Notes}
	f, ok := m.Files[plat]
	if !ok {
		return r, fmt.Errorf("no build for %s in the manifest", plat)
	}
	r.Available = Compare(current, m.Version) < 0
	r.URL, r.SHA256, r.Size, r.Signature = f.URL, f.SHA256, f.Size, f.Signature
	return r, nil
}

// Download fetches f into dest, verifying the size, SHA-256 and (when pub is
// non-nil) the Ed25519 signature over the file's SHA-256 digest. The file is
// written to a temporary neighbour and renamed into place only after it passes
// every check, so a partial or tampered download never lands as dest.
func Download(ctx context.Context, client *http.Client, f File, dest string, pub ed25519.PublicKey) error {
	if strings.TrimSpace(f.URL) == "" {
		return errors.New("manifest file has no URL")
	}
	u, err := url.Parse(f.URL)
	if err != nil {
		return fmt.Errorf("bad download URL: %w", err)
	}
	if u.Scheme != "https" {
		return errors.New("the download URL must use https")
	}
	if !isHex64(f.SHA256) {
		return errors.New("manifest file has no usable sha256")
	}
	if client == nil {
		client = &http.Client{Timeout: 15 * time.Minute}
	}
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, f.URL, nil)
	if err != nil {
		return err
	}
	resp, err := client.Do(req)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("download: %s", resp.Status)
	}

	tmp, err := os.CreateTemp(filepath.Dir(dest), ".lanyard-dl-*")
	if err != nil {
		return err
	}
	tmpName := tmp.Name()
	cleanup := func() { tmp.Close(); os.Remove(tmpName) }

	h := sha256.New()
	n, err := io.Copy(io.MultiWriter(tmp, h), io.LimitReader(resp.Body, maxDownloadBytes+1))
	if err != nil {
		cleanup()
		return err
	}
	if n > maxDownloadBytes {
		cleanup()
		return fmt.Errorf("download exceeds the %d byte limit", int64(maxDownloadBytes))
	}
	if f.Size > 0 && n != f.Size {
		cleanup()
		return fmt.Errorf("download is %d bytes, manifest says %d", n, f.Size)
	}
	sum := h.Sum(nil)
	if !strings.EqualFold(hex.EncodeToString(sum), f.SHA256) {
		cleanup()
		return errors.New("download failed its SHA-256 check")
	}
	if pub != nil {
		if f.Signature == "" {
			cleanup()
			return errors.New("release is unsigned but a signing key is configured")
		}
		sig, err := base64.StdEncoding.DecodeString(f.Signature)
		if err != nil {
			cleanup()
			return errors.New("release signature is not valid base64")
		}
		if !ed25519.Verify(pub, sum, sig) {
			cleanup()
			return errors.New("release signature does not verify")
		}
	}
	if err := tmp.Sync(); err != nil {
		cleanup()
		return err
	}
	if err := tmp.Close(); err != nil {
		os.Remove(tmpName)
		return err
	}
	_ = os.Chmod(tmpName, 0o755)
	if err := os.Rename(tmpName, dest); err != nil {
		os.Remove(tmpName)
		return err
	}
	return nil
}

// Staged is the metadata written beside a downloaded update.
type Staged struct {
	Version   string `json:"version"`
	SHA256    string `json:"sha256"`
	Size      int64  `json:"size,omitempty"`
	Signature string `json:"signature,omitempty"`
	StagedAt  string `json:"staged_at"`
}

// StageDownload downloads the newer build to "<exe>.new" and writes its
// verification metadata to "<exe>.new.json". It is applied on the next start by
// ApplyStaged.
func StageDownload(ctx context.Context, client *http.Client, exe string, r *Result, pub ed25519.PublicKey) (*Staged, error) {
	f := File{URL: r.URL, SHA256: r.SHA256, Size: r.Size, Signature: r.Signature}
	if err := Download(ctx, client, f, exe+".new", pub); err != nil {
		return nil, err
	}
	st := &Staged{Version: r.Latest, SHA256: r.SHA256, Size: r.Size, Signature: r.Signature,
		StagedAt: time.Now().UTC().Format(time.RFC3339)}
	b, _ := json.MarshalIndent(st, "", "  ")
	if err := os.WriteFile(exe+".new.json", b, 0o600); err != nil {
		os.Remove(exe + ".new")
		return nil, err
	}
	return st, nil
}

// ApplyStaged swaps in a previously staged, re-verified update. It renames the
// running binary aside and moves the new one into place; on Windows a running
// executable may be renamed but not overwritten, so this is done before the
// server starts. It reports whether an update was applied.
func ApplyStaged(exe string, pub ed25519.PublicKey) (bool, error) {
	newPath := exe + ".new"
	metaPath := exe + ".new.json"
	if _, err := os.Stat(newPath); err != nil {
		return false, nil
	}
	raw, err := os.ReadFile(metaPath)
	if err != nil {
		return false, fmt.Errorf("staged update has no metadata: %w", err)
	}
	var st Staged
	if err := json.Unmarshal(raw, &st); err != nil {
		return false, fmt.Errorf("staged update metadata is invalid: %w", err)
	}
	// Re-verify the staged file before trusting it.
	f, err := os.Open(newPath)
	if err != nil {
		return false, err
	}
	h := sha256.New()
	n, err := io.Copy(h, f)
	f.Close()
	if err != nil {
		return false, err
	}
	if !strings.EqualFold(hex.EncodeToString(h.Sum(nil)), st.SHA256) {
		return false, errors.New("staged update failed its SHA-256 check")
	}
	if st.Size > 0 && n != st.Size {
		return false, errors.New("staged update size does not match its metadata")
	}
	if pub != nil {
		sig, err := base64.StdEncoding.DecodeString(st.Signature)
		if err != nil || !ed25519.Verify(pub, h.Sum(nil), sig) {
			return false, errors.New("staged update signature does not verify")
		}
	}
	old := exe + ".old"
	_ = os.Remove(old)
	if err := os.Rename(exe, old); err != nil {
		return false, fmt.Errorf("could not move the running binary aside: %w", err)
	}
	if err := os.Rename(newPath, exe); err != nil {
		_ = os.Rename(old, exe) // roll back
		return false, fmt.Errorf("could not move the update into place: %w", err)
	}
	_ = os.Remove(metaPath)
	return true, nil
}

func isHex64(s string) bool {
	if len(s) != 64 {
		return false
	}
	_, err := hex.DecodeString(s)
	return err == nil
}
