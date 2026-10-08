package xferlog

import "regexp"

// Redaction is defence in depth. Callers are expected to pass short ids and
// base names, but an error string from the filesystem can still carry a full
// path, and a peer can put anything in a message. Scrub removes:
//
//   - a full 64-hex certificate fingerprint,
//   - a 32-hex token, invite nonce or session nonce (after the full fingerprint
//     so a 64-hex value is already gone),
//   - an "invite=…"/"nonce=…"/"token=…" assignment,
//   - a multi-segment Unix path or a Windows drive path.
//
// A short 16-hex fingerprint is deliberately left alone: it is the peer short
// id the log is required to show.
var (
	reFullFP   = regexp.MustCompile(`[0-9a-fA-F]{64}`)
	reToken    = regexp.MustCompile(`[0-9a-fA-F]{32}`)
	reSecret   = regexp.MustCompile(`(?i)(invite|nonce|token|secret)([=:][ \t]*|\s+)[A-Za-z0-9._~+/=-]+`)
	reWinPath  = regexp.MustCompile(`[A-Za-z]:\\[^\s"']+`)
	reUnixPath = regexp.MustCompile(`(?:/(?:[A-Za-z0-9._~@%+-]+)){2,}`)
)

// Scrub returns s with fingerprints, tokens, secrets and full paths removed.
func Scrub(s string) string {
	if s == "" {
		return s
	}
	s = reFullFP.ReplaceAllString(s, "<fingerprint>")
	s = reToken.ReplaceAllString(s, "<token>")
	s = reSecret.ReplaceAllString(s, "$1=<redacted>")
	s = reWinPath.ReplaceAllString(s, "<path>")
	s = reUnixPath.ReplaceAllString(s, "<path>")
	return s
}
