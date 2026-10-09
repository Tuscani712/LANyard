package uiserver

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// The desktop speed formatter must be adaptive so a slow transfer never reads
// as the useless "0.0 MB/s". This extracts the real fmtRate/fmtSpeed source and
// runs it under node to pin the unit boundaries. The Go-side mirror
// (xferlog.FormatSpeed) is tested against the same boundaries.
func TestAppJSSpeedFormatAdaptiveUnits(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed; skipping JS-level speed format check")
	}
	js := readAppJS(t)
	block := extractBlock(t, js,
		"// ---- speed formatting (extracted verbatim by speed_format_static_test.go) ----",
		"// ---- end speed formatting ----")

	script := `let settings = { speed_unit: "mbs" };
` + block + `
settings.speed_unit = "mbs";
const bytes = [
  [0, "--"],
  [1, "1 B/s"],
  [999, "999 B/s"],
  [1000, "1.0 KB/s"],
  [1500, "1.5 KB/s"],
  [1000000, "1.0 MB/s"],
  [2500000, "2.5 MB/s"],
  [1000000000, "1.0 GB/s"],
];
settings.speed_unit = "mbps";
const bits = [
  [500, "4.0 Kbps"],   // 500 B/s
  [1000, "8.0 Kbps"],  // 1 KB/s
  [125000, "1.0 Mbps"],
];
const out = { bytes: [], speed: [], bits: [] };
settings.speed_unit = "mbs";
for (const [v, want] of bytes) out.bytes.push([fmtRate(v, false), want]);
// fmtSpeed takes the API's MB/s (bytes/1e6): 0.0005 MB/s is 500 B/s, never 0.0 MB/s.
out.speed.push([fmtSpeed(0.0005), "500 B/s"]);
out.speed.push([fmtSpeed(2), "2.0 MB/s"]);
out.speed.push([fmtSpeed(0), "--"]);
settings.speed_unit = "mbps";
for (const [v, want] of bits) out.bits.push([fmtRate(v, true), want]);
out.speed.push([fmtSpeed(1), "8.0 Mbps"]);
out.speed.push([fmtSpeed(0.0001), "800 bps"]);
console.log(JSON.stringify(out));
`
	dir := t.TempDir()
	file := filepath.Join(dir, "speed_check.mjs")
	if err := os.WriteFile(file, []byte(script), 0o600); err != nil {
		t.Fatalf("write node script: %v", err)
	}
	raw := runNode(t, node, file)

	var got map[string][][]string
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("parse node output %q: %v", raw, err)
	}
	check := func(group string) {
		for i, pair := range got[group] {
			if len(pair) != 2 {
				t.Fatalf("%s case %d malformed: %v", group, i, pair)
			}
			if pair[0] != pair[1] {
				t.Errorf("%s case %d = %q, want %q", group, i, pair[0], pair[1])
			}
		}
	}
	check("bytes")
	check("bits")
	check("speed")

	// The formatter must be the only speed formatter; the old fixed-unit
	// expression must be gone.
	if strings.Contains(js, `toFixed(1)} MB/s`) {
		t.Error("app.js still contains the fixed-unit `toFixed(1) MB/s` formatter")
	}
}
