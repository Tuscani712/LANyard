package uiserver

import (
	"encoding/json"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// Bug A: the desktop UI used to rebuild whole pages on unconditional timers and
// on every SSE frame, so the element under the cursor was replaced between
// mousedown and mouseup and a button needed several clicks. The fix gates each
// page rebuild behind a cheap signature of the data it depends on, and defers a
// rebuild while the user is interacting with the view. These tests pin that
// structure down and run the real helpers under node to prove both directions:
// a no-change refresh is a no-op, and a changed field still renders.
func readAppJS(t *testing.T) string {
	t.Helper()
	src, err := os.ReadFile("web/app.js")
	if err != nil {
		t.Fatalf("read app.js: %v", err)
	}
	return string(src)
}

// extractBlock returns the text between the start marker and the following end
// marker, or fails the test.
func extractBlock(t *testing.T, js, start, end string) string {
	t.Helper()
	i := strings.Index(js, start)
	if i < 0 {
		t.Fatalf("app.js is missing block start marker %q", start)
	}
	j := strings.Index(js[i:], end)
	if j < 0 {
		t.Fatalf("app.js is missing block end marker %q", end)
	}
	return js[i : i+j]
}

func TestAppJSRenderSignatureSkipsUnchanged(t *testing.T) {
	js := readAppJS(t)

	want := []string{
		// The shared mechanism and the counters the measurement harness reads.
		"function sigUnchanged(key, value) {",
		"function renderDecision(key, value, container) {",
		"function deferRebuild(key, value, container) {",
		"function interactionActive(container) {",
		"const _pendingRebuilds = new Set();",
		"function initRebuildFlush() {",
		"initRebuildFlush();",
		"const renderCounts = { shares: 0, paired: 0, devices: 0, explorer: 0, side: 0, body: 0, transfers: 0, nav: 0, toolbar: 0 };",
		"const PAGE_SIGNATURES = { shares: sharesSig, paired: pairedSig, devices: exSideSig, transfers: transfersSig };",
		// Every hot page is gated before it touches the DOM.
		`if (!force && deferRebuild("nav", navSig(), $("nav"))) return false;`,
		`if (!force && deferRebuild("exSide", exSideSig(), $("ex-side"))) return false;`,
		`if (!force && deferRebuild("toolbar", toolbarSig(), $("crumbs"))) return false;`,
		`if (!force && deferRebuild("body", bodySig(), body)) return false;`,
		`if (!force && deferRebuild("paired", pairedSig(), box)) return false;`,
		`if (!force && deferRebuild("transfers", transfersSig(), box)) return false;`,
		`const decision = renderDecision("shares", sharesSig(), box);`,
		// The signatures must cover everything the page shows. In particular the
		// Transfers signature carries the whole live rows (progress/speed/ETA/
		// state) and Paired/Devices carry the online set so a reachability flip
		// always rebuilds.
		`return { transfers: transfersList, incoming: incomingList, snippets: snippetsList, filter: S.historyFilter || "all" };`,
		`online: peers.filter((p) => p.verified).map((p) => p.device_id),`,
		`peers: peers.filter((x) => x.verified).map((x) => [x.device_id, x.name, x.os]),`,
		`function sharesSig() { return sharesList; }`,
		// handleSessions must not re-render when the poll found no change.
		"if (!changed) return;",
		// The 1 s Shares tick stays (the countdown must keep updating) but it now
		// only refreshes the lifetime text in place when the data is unchanged.
		`setInterval(() => { if (S.view === "shares" && !document.hidden) renderSharesPage(); }, 1000);`,
		"for (const tick of _shareTickEls.values()) tick();",
	}
	for _, w := range want {
		if !strings.Contains(js, w) {
			t.Errorf("app.js is missing render-churn guard %q", w)
		}
	}

	// The no-change path must run before the container is cleared: a no-change
	// refresh may not replace nodes.
	gate := `const decision = renderDecision("shares", sharesSig(), box);`
	gi := strings.Index(js, gate)
	if gi < 0 {
		t.Fatalf("shares gate not found")
	}
	ci := strings.Index(js[gi:], "clear(box);")
	if ci < 0 {
		t.Fatalf("clear(box) not found after the shares gate")
	}
	if !strings.Contains(js[gi:gi+ci], "return false;") {
		t.Error("shares no-change path must return before clear(box)")
	}
}

// The signature helper is pure (no DOM), so its real source can be extracted
// and executed under node: calling the update path twice with identical data
// must report "unchanged" (and therefore skip the rebuild), while new data must
// report "changed".
func TestAppJSSigUnchangedIdempotent(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed; skipping JS-level signature check")
	}
	js := readAppJS(t)
	helpers := extractBlock(t, js,
		"// ---- render-signature helpers (see render_churn_static_test.go) ----",
		"// ---- end render-signature helpers ----")

	script := helpers + `
const out = [];
out.push(_renderSig.size === 0);              // no entries before first call
out.push(sigUnchanged("k", { a: 1, b: [2, 3] })); // first sight -> changed
out.push(sigUnchanged("k", { a: 1, b: [2, 3] })); // identical  -> unchanged (skip)
out.push(sigUnchanged("k", { a: 1, b: [2, 4] })); // different  -> changed
// key order must not defeat the signature: same logical object, keys reversed.
out.push(sigUnchanged("ko", { a: 1, b: 2 }));     // first sight -> changed
out.push(sigUnchanged("ko", { b: 2, a: 1 }));     // reordered  -> unchanged (skip)
out.push(sigUnchanged("k2", "same"));             // first sight -> changed
out.push(sigUnchanged("k2", "same"));             // identical  -> unchanged (skip)
out.push(renderCounts.shares === 0);            // a skip must not count as a render
console.log(JSON.stringify(out));
`
	dir := t.TempDir()
	file := filepath.Join(dir, "sig_check.mjs")
	if err := os.WriteFile(file, []byte(script), 0o600); err != nil {
		t.Fatalf("write node script: %v", err)
	}
	out, err := exec.Command(node, file).CombinedOutput()
	if err != nil {
		t.Fatalf("node run failed: %v\n%s", err, out)
	}
	var got []bool
	if err := json.Unmarshal([]byte(strings.TrimSpace(string(out))), &got); err != nil {
		t.Fatalf("parse node output %q: %v", out, err)
	}
	want := []bool{true, false, true, false, false, true, false, true, true}
	if len(got) != len(want) {
		t.Fatalf("got %d results, want %d: %v", len(got), len(want), got)
	}
	for k := range want {
		if got[k] != want[k] {
			t.Errorf("sigUnchanged check %d = %v, want %v (all: %v)", k, got[k], want[k], got)
		}
	}
}

// harnessPrologue declares the globals the extracted signature block reads and
// a fake render that counts real node replacements (a skipped or deferred gate
// does not touch the nodes).
const harnessPrologue = `
let peers = [], trustList = [], sessions = [], sharesList = [], transfersList = [], incomingList = [], snippetsList = [];
let S = { view: "devices", actionable: 0, search: "", mode: "grid", historyFilter: "all", ex: { roots: null, hist: [{ kind: "home" }], hi: 0 } };
const doc = { activeElement: null, querySelector: () => null };
globalThis.document = doc;

function makeNode() { return { scrollTop: 0 }; }
const nodeCount = { shares: 0, paired: 0, devices: 0, transfers: 0 };
function render(key, container) {
  if (renderDecision(key, PAGE_SIGNATURES[key](), container) !== "rebuild") return false;
  nodeCount[key]++;
  return true;
}
`

// TestAppJSPagesNoopOnIdleAndRenderOnChange runs the real signature functions and
// gate under node for the four first-class pages. It proves both directions:
// (a) a no-change refresh (idle) replaces no nodes, and (b) a changed visible
// field does render. It also re-measures the idle render rate.
func TestAppJSPagesNoopOnIdleAndRenderOnChange(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed; skipping JS-level page-render check")
	}
	js := readAppJS(t)
	helpers := extractBlock(t, js,
		"// ---- render-signature helpers (see render_churn_static_test.go) ----",
		"// ---- end render-signature helpers ----")
	sigs := extractBlock(t, js,
		"// ---- render signatures (extracted verbatim by render_churn_static_test.go) ----",
		"// ---- end render signatures ----")

	script := harnessPrologue + helpers + sigs + `
const c = makeNode();
const out = {};

// Seed one of each page with data.
peers = [{ device_id: "p1", name: "A", os: "linux", verified: true }];
trustList = [{ cert_fingerprint: "p1", name: "A", os: "linux", permissions: { browse: true, push: false } }];
sessions = [{ id: "s1", status: "active", mode: "connect", incoming: false, peer_name: "A", peer_fp: "p1", peer_device: "dev" }];
sharesList = [{ share_id: "sh1", label: "x", path: "/x", kind: "folder", visibility: "everyone", state: "active", active_transfers: 0, lifetime: { type: "persistent" } }];
transfersList = [{ id: "t1", state: "Transferring", done: 10, total: 100, speed_mbps: 5, eta_seconds: 18, files_total: 1, files_done: 0, direction: "download" }];

// Prime every page, then idle-refresh them for a simulated 60 s (600 frames at
// 100 ms each, far faster than the 1 s / 1.5 s timers). Counts must not move.
for (const k of Object.keys(nodeCount)) render(k, c);
const primed = Object.assign({}, nodeCount);
for (let i = 0; i < 600; i++) for (const k of Object.keys(nodeCount)) render(k, c);
for (const k of Object.keys(nodeCount)) out["idle60s_" + k] = nodeCount[k] - primed[k];

// (b) changed data must replace nodes, for each page, and identical data after
// that must not.
function changed(key, mutate, reset) {
  mutate();
  const before = nodeCount[key];
  render(key, c);
  const rendered = nodeCount[key] === before + 1;
  const mid = nodeCount[key];
  render(key, c);
  render(key, c);
  const unchanged = nodeCount[key] === mid;
  reset();
  return { rendered, unchanged };
}

// Paired: a device going offline changes its dot and last-seen text.
out.paired = changed("paired",
  () => { peers = []; },
  () => { peers = [{ device_id: "p1", name: "A", os: "linux", verified: true }]; render("paired", c); });
// Devices: the side tree loses the online dot when the peer is away.
out.devices = changed("devices",
  () => { peers = []; },
  () => { peers = [{ device_id: "p1", name: "A", os: "linux", verified: true }]; render("devices", c); });
// Transfers: live progress/speed changes must repaint.
out.transfers = changed("transfers",
  () => { transfersList = [Object.assign({}, transfersList[0], { done: 55, speed_mbps: 9 })]; },
  () => { transfersList = [Object.assign({}, transfersList[0], { done: 10, speed_mbps: 5 })]; render("transfers", c); });
// Shares: a row field changes.
out.shares = changed("shares",
  () => { sharesList = [Object.assign({}, sharesList[0], { label: "y" })]; },
  () => { sharesList = [Object.assign({}, sharesList[0], { label: "x" })]; render("shares", c); });

// The idle rate, reported per minute.
out.idle_renders_per_min = 0;
for (const k of Object.keys(nodeCount)) out.idle_renders_per_min += out["idle60s_" + k];
console.log(JSON.stringify(out));
`
	dir := t.TempDir()
	file := filepath.Join(dir, "page_check.mjs")
	if err := os.WriteFile(file, []byte(script), 0o600); err != nil {
		t.Fatalf("write node script: %v", err)
	}
	raw := runNode(t, node, file)
	var got map[string]any
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("parse node output %q: %v", raw, err)
	}
	t.Logf("idle renders over simulated 60s per page: shares=%v paired=%v devices=%v transfers=%v; total/min=%v",
		got["idle60s_shares"], got["idle60s_paired"], got["idle60s_devices"], got["idle60s_transfers"], got["idle_renders_per_min"])

	for _, k := range []string{"shares", "paired", "devices", "transfers"} {
		if v, _ := got["idle60s_"+k].(float64); v != 0 {
			t.Errorf("idle refresh rebuilt %s nodes %v times; want 0", k, v)
		}
		dir, ok := got[k].(map[string]any)
		if !ok {
			t.Fatalf("missing both-direction result for %s: %v", k, got[k])
		}
		if r, _ := dir["rendered"].(bool); !r {
			t.Errorf("%s: changed data did NOT render", k)
		}
		if u, _ := dir["unchanged"].(bool); !u {
			t.Errorf("%s: identical data after a change still replaced nodes", k)
		}
	}
}

// TestAppJSInteractionGuardDefersRebuild proves the focused-element/dialog guard:
// a changed signature is deferred while a text entry inside the view holds focus
// (or a dialog is open), and rebuilds as soon as the interaction ends.
func TestAppJSInteractionGuardDefersRebuild(t *testing.T) {
	node, err := exec.LookPath("node")
	if err != nil {
		t.Skip("node not installed; skipping JS-level interaction guard check")
	}
	js := readAppJS(t)
	helpers := extractBlock(t, js,
		"// ---- render-signature helpers (see render_churn_static_test.go) ----",
		"// ---- end render-signature helpers ----")
	sigs := extractBlock(t, js,
		"// ---- render signatures (extracted verbatim by render_churn_static_test.go) ----",
		"// ---- end render signatures ----")

	script := harnessPrologue + helpers + sigs + `
const out = {};
const input = { tagName: "INPUT" };
const container = { scrollTop: 0, contains: (e) => e === input };

// A focused input inside the container defers a changed rebuild.
doc.activeElement = input;
const d1 = renderDecision("guard", exSideSig(), container);
out.focus_defers = d1 === "defer";
out.focus_pending = _pendingRebuilds.has("guard");
// The signature was left untouched, so once focus leaves the same data rebuilds.
doc.activeElement = { tagName: "BODY" };
const d2 = renderDecision("guard", exSideSig(), container);
out.focus_resumes = d2 === "rebuild";

// An open overlay that does not contain the container defers the rebuild.
doc.activeElement = null;
doc.querySelector = (sel) => (sel === ".overlay:not([hidden])" ? { contains: () => false } : null);
out.dialog_defers = renderDecision("guard2", exSideSig(), container) === "defer";

// An open context menu defers too.
doc.querySelector = (sel) => (sel === "#ctx-root .ctx" ? { contains: () => false } : null);
out.menu_defers = renderDecision("guard3", exSideSig(), container) === "defer";

// Focus on a plain button (not a text entry) does not defer: a click that
// already landed must still be able to refresh the view.
doc.querySelector = () => null;
doc.activeElement = { tagName: "BUTTON" };
out.button_allows = renderDecision("guard4", exSideSig(), container) === "rebuild";
console.log(JSON.stringify(out));
`
	dir := t.TempDir()
	file := filepath.Join(dir, "guard_check.mjs")
	if err := os.WriteFile(file, []byte(script), 0o600); err != nil {
		t.Fatalf("write node script: %v", err)
	}
	raw := runNode(t, node, file)
	var got map[string]bool
	if err := json.Unmarshal(raw, &got); err != nil {
		t.Fatalf("parse node output %q: %v", raw, err)
	}
	for _, k := range []string{"focus_defers", "focus_pending", "focus_resumes", "dialog_defers", "menu_defers", "button_allows"} {
		if !got[k] {
			t.Errorf("interaction guard check %s = false; want true (all: %v)", k, got)
		}
	}
}

func runNode(t *testing.T, node, file string) []byte {
	t.Helper()
	out, err := exec.Command(node, file).CombinedOutput()
	if err != nil {
		t.Fatalf("node run failed: %v\n%s", err, out)
	}
	trimmed := strings.TrimSpace(string(out))
	if i := strings.LastIndexByte(trimmed, '\n'); i >= 0 {
		trimmed = trimmed[i+1:]
	}
	return []byte(trimmed)
}
