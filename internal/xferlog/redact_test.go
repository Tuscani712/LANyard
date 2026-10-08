package xferlog

import (
	"bytes"
	"log/slog"
	"strings"
	"testing"
)

// A sample log that (by mistake or a hostile peer) carries secrets, full paths
// and a full fingerprint must come out of both the copyable report and the
// desktop logger with none of them.
func TestReportRedactsSecrets(t *testing.T) {
	const (
		fullFP  = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
		token   = "deadbeefdeadbeefdeadbeefdeadbeef"
		invite  = "invite=" + token
		unixP   = "/home/alice/secret/Inbox/taxes-2025.pdf"
		winP    = `C:\Users\alice\Documents\private\report.docx`
		shortFP = "abcdef0123456789"
	)

	rec := New(20)
	rec.Add(Entry{
		Area: AreaPulling, Level: LevelWarn, Outcome: "download",
		FP: shortFP, File: unixP, Reason: "open failed",
		Error: "open " + unixP + ": permission denied; presented " + fullFP,
	})
	rec.Add(Entry{
		Area: AreaPairing, Level: LevelError, Outcome: "refuse", Session: "c_" + token,
		Reason: winP, Error: invite,
	})

	var buf bytes.Buffer
	log := slog.New(slog.NewTextHandler(&buf, nil))
	rec.Record(log, Entry{
		Area: AreaDiscovery, Level: LevelInfo, Outcome: "seen",
		FP: shortFP, Target: winP, Error: "token " + token,
	})

	report := rec.Report()
	logged := buf.String()

	for _, secret := range []string{fullFP, token, unixP, winP} {
		if strings.Contains(report, secret) {
			t.Errorf("report leaked %q:\n%s", secret, report)
		}
		if strings.Contains(logged, secret) {
			t.Errorf("desktop log leaked %q:\n%s", secret, logged)
		}
	}
	// The short fingerprint is required in the log and must survive, and the
	// full one that did appear is visibly marked as redacted.
	if !strings.Contains(report, shortFP) {
		t.Errorf("report dropped the short fingerprint:\n%s", report)
	}
	if !strings.Contains(report, "<fingerprint>") {
		t.Errorf("full fingerprint was not marked as redacted:\n%s", report)
	}
}

func TestScrubKeepsShortAndRedactsLong(t *testing.T) {
	const short = "abcdef0123456789"
	const full = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
	const tok = "deadbeefdeadbeefdeadbeefdeadbeef"

	got := Scrub(short + " " + full + " " + tok + " invite=" + tok + " /a/b/c.txt")
	if !strings.Contains(got, short) {
		t.Errorf("short id removed: %q", got)
	}
	for _, bad := range []string{full, tok, "/a/b/c.txt"} {
		if strings.Contains(got, bad) {
			t.Errorf("Scrub left %q in %q", bad, got)
		}
	}
}
