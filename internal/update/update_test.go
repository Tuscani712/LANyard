package update

import (
	"context"
	"crypto/ed25519"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/hex"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"testing"
)

func TestCompare(t *testing.T) {
	cases := []struct {
		a, b string
		want int
	}{
		{"1.0.0", "1.0.0", 0},
		{"1.0.0", "1.0.1", -1},
		{"1.0.1", "1.0.0", 1},
		{"v1.0.0", "1.0.0", 0},
		{"1.0.0-rc1", "1.0.0", -1},
		{"1.0.0", "1.0.0-rc1", 1},
		{"1.0.0-rc1", "1.0.0-rc2", -1},
		{"1.2", "1.10", -1},
		{"2.0.0", "1.9.9", 1},
	}
	for _, c := range cases {
		if got := Compare(c.a, c.b); got != c.want {
			t.Errorf("Compare(%q,%q)=%d want %d", c.a, c.b, got, c.want)
		}
	}
}

func sha256Hex(b []byte) string {
	s := sha256.Sum256(b)
	return hex.EncodeToString(s[:])
}

// newManifestServer serves a manifest and one binary over TLS.
func newManifestServer(t *testing.T, bin []byte, sign ed25519.PrivateKey) (*httptest.Server, string) {
	t.Helper()
	mux := http.NewServeMux()
	srv := httptest.NewTLSServer(mux)
	t.Cleanup(srv.Close)

	file := File{URL: srv.URL + "/bin", SHA256: sha256Hex(bin), Size: int64(len(bin))}
	if sign != nil {
		sum := sha256.Sum256(bin)
		file.Signature = base64.StdEncoding.EncodeToString(ed25519.Sign(sign, sum[:]))
	}
	man := Manifest{Version: "1.1.0", Notes: "test", Files: map[string]File{Platform(): file}}
	manRaw, _ := json.Marshal(man)

	mux.HandleFunc("/latest.json", func(w http.ResponseWriter, r *http.Request) { w.Write(manRaw) })
	mux.HandleFunc("/bin", func(w http.ResponseWriter, r *http.Request) { w.Write(bin) })
	return srv, srv.URL + "/latest.json"
}

func TestCheckAndDownload(t *testing.T) {
	bin := []byte("a fake but complete release binary")
	pub, priv, _ := ed25519.GenerateKey(rand.Reader)
	srv, manifestURL := newManifestServer(t, bin, priv)

	r, err := Check(context.Background(), srv.Client(), manifestURL, "1.0.0")
	if err != nil {
		t.Fatalf("Check: %v", err)
	}
	if !r.Available || r.Latest != "1.1.0" || r.SHA256 != sha256Hex(bin) {
		t.Fatalf("unexpected result: %+v", r)
	}
	// Already current: not available.
	if r2, _ := Check(context.Background(), srv.Client(), manifestURL, "1.1.0"); r2.Available {
		t.Error("1.1.0 should not see an update to 1.1.0")
	}

	dest := filepath.Join(t.TempDir(), "lanyard.exe")
	if err := Download(context.Background(), srv.Client(), File{URL: r.URL, SHA256: r.SHA256, Size: r.Size, Signature: r.Signature}, dest, pub); err != nil {
		t.Fatalf("Download: %v", err)
	}
	got, _ := os.ReadFile(dest)
	if string(got) != string(bin) {
		t.Fatalf("downloaded content mismatch")
	}

	// Bad hash must be refused and leave no dest file.
	bad := filepath.Join(t.TempDir(), "bad.exe")
	if err := Download(context.Background(), srv.Client(), File{URL: r.URL, SHA256: sha256Hex([]byte("nope")), Size: r.Size}, bad, nil); err == nil {
		t.Error("a wrong sha256 must fail the download")
	}
	if _, err := os.Stat(bad); !os.IsNotExist(err) {
		t.Error("a failed download must not leave the destination file")
	}

	// A signature is required when a public key is configured.
	unsigned := filepath.Join(t.TempDir(), "u.exe")
	if err := Download(context.Background(), srv.Client(), File{URL: r.URL, SHA256: r.SHA256, Size: r.Size}, unsigned, pub); err == nil {
		t.Error("unsigned release must be refused when a key is configured")
	}

	// http:// must be rejected.
	if _, err := Fetch(context.Background(), srv.Client(), "http://example.com/latest.json"); err == nil {
		t.Error("http manifest URL must be rejected")
	}
}

func TestApplyStaged(t *testing.T) {
	dir := t.TempDir()
	exe := filepath.Join(dir, "lanyard.exe")
	if err := os.WriteFile(exe, []byte("old binary"), 0o755); err != nil {
		t.Fatal(err)
	}
	next := []byte("new binary")
	if err := os.WriteFile(exe+".new", next, 0o755); err != nil {
		t.Fatal(err)
	}
	st := Staged{Version: "1.1.0", SHA256: sha256Hex(next), Size: int64(len(next)), StagedAt: "now"}
	b, _ := json.Marshal(st)
	if err := os.WriteFile(exe+".new.json", b, 0o600); err != nil {
		t.Fatal(err)
	}
	applied, err := ApplyStaged(exe, nil)
	if err != nil || !applied {
		t.Fatalf("ApplyStaged applied=%v err=%v", applied, err)
	}
	got, _ := os.ReadFile(exe)
	if string(got) != string(next) {
		t.Fatalf("exe not replaced: %q", got)
	}
	if _, err := os.Stat(exe + ".new.json"); !os.IsNotExist(err) {
		t.Error("metadata should be removed after applying")
	}

	// A tampered staged file must be refused.
	if err := os.WriteFile(exe+".new", []byte("tampered"), 0o755); err != nil {
		t.Fatal(err)
	}
	b2, _ := json.Marshal(Staged{Version: "1.2.0", SHA256: sha256Hex(next)})
	_ = os.WriteFile(exe+".new.json", b2, 0o600)
	if applied, err := ApplyStaged(exe, nil); err == nil || applied {
		t.Errorf("tampered staged update must be refused, applied=%v err=%v", applied, err)
	}
	// Nothing staged: no-op.
	_ = os.Remove(exe + ".new")
	_ = os.Remove(exe + ".new.json")
	if applied, err := ApplyStaged(exe, nil); applied || err != nil {
		t.Errorf("no staged update should be a no-op, applied=%v err=%v", applied, err)
	}
}
