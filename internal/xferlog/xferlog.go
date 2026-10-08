// Package xferlog is the diagnostics event log. It records what actually
// happened across the four areas a transfer problem can live in — discovery,
// pairing, pushing and pulling — for both the sending and receiving side. It
// keeps a small bounded history in memory so the diagnostics report can show
// the most recent events, and it mirrors every entry into the normal desktop
// log through a *slog.Logger. It never stores full file paths, invite secrets,
// tokens or full fingerprints: callers pass short ids and redacted labels, and
// Report scrubs anything that still looks sensitive as a defence in depth.
package xferlog

import (
	"fmt"
	"log/slog"
	"strings"
	"sync"
	"time"
)

// Level is how serious one log entry is.
type Level string

const (
	// LevelDebug is for routine, healthy chatter (a successful probe, a
	// re-announced peer) that nobody needs in the default desktop log. It is
	// still kept in the in-memory diagnostics history.
	LevelDebug Level = "debug"
	LevelInfo  Level = "info"
	LevelWarn  Level = "warn"
	LevelError Level = "error"
)

// Area is which of the four diagnostic areas an entry belongs to.
type Area string

const (
	AreaDiscovery Area = "discovery"
	AreaPairing   Area = "pairing"
	AreaPushing   Area = "pushing"
	AreaPulling   Area = "pulling"
)

// Direction says which side of a transfer the entry describes.
const (
	DirectionSend    = "send"    // outgoing push from this desktop
	DirectionReceive = "receive" // incoming push into this desktop
)

// Step is the protocol stage a transfer entry belongs to.
const (
	StepOffer    = "offer"
	StepFile     = "file"     // one per-file PUT (send) or receipt (receive)
	StepComplete = "complete" // per-file or final finalize
	StepSnippet  = "snippet"  // short text message
)

// Entry is one recorded event. Peer/Step/Direction are kept for the transfer
// areas; the discovery and pairing areas use Outcome.
type Entry struct {
	Time      time.Time     `json:"time"`
	Area      Area          `json:"area"`
	Level     Level         `json:"level"`
	Outcome   string        `json:"outcome,omitempty"`
	Direction string        `json:"direction,omitempty"`
	Step      string        `json:"step,omitempty"`
	Job       string        `json:"job,omitempty"`
	Peer      string        `json:"peer,omitempty"` // friendly name, if known
	FP        string        `json:"fp,omitempty"`   // short fingerprint only
	Target    string        `json:"target,omitempty"`
	File      string        `json:"file,omitempty"` // base name or class only
	Bytes     int64         `json:"bytes,omitempty"`
	Offset    int64         `json:"offset,omitempty"`
	Size      int64         `json:"size,omitempty"`
	Misses    int           `json:"misses,omitempty"`
	Session   string        `json:"session,omitempty"`
	Reason    string        `json:"reason,omitempty"`
	Age       time.Duration `json:"age_ns,omitempty"`
	Elapsed   time.Duration `json:"elapsed_ns,omitempty"`
	SpeedBps  int64         `json:"speed_bps,omitempty"`
	Error     string        `json:"error,omitempty"`
}

// areaTag returns the entry's area, defaulting transfer entries to pushing.
func (e Entry) areaTag() string {
	if e.Area != "" {
		return string(e.Area)
	}
	if e.Direction != "" || e.Step != "" {
		return string(AreaPushing)
	}
	return "log"
}

// outcomeTag is the short outcome word, derived from Step/Level when not set.
func (e Entry) outcomeTag() string {
	if e.Outcome != "" {
		return e.Outcome
	}
	if e.Step != "" {
		return e.Step
	}
	return string(e.Level)
}

// summary is the short one-line form used for the desktop log message.
func (e Entry) summary() string {
	tag := e.outcomeTag()
	if e.Direction != "" {
		side := "outgoing"
		if e.Direction == DirectionReceive {
			side = "incoming"
		}
		return fmt.Sprintf("%s %s %s", e.areaTag(), side, tag)
	}
	return fmt.Sprintf("%s %s", e.areaTag(), tag)
}

// attrs formats the entry's fields for slog, scrubbing anything that looks
// sensitive so the desktop log never carries a token or full path either.
func (e Entry) attrs() []any {
	attrs := make([]any, 0, 20)
	attrs = append(attrs, "area", e.areaTag())
	attrs = append(attrs, "outcome", e.outcomeTag())
	if e.Direction != "" {
		attrs = append(attrs, "direction", e.Direction)
	}
	if e.Step != "" {
		attrs = append(attrs, "step", e.Step)
	}
	if e.Job != "" {
		attrs = append(attrs, "job", Scrub(e.Job))
	}
	if e.Peer != "" {
		attrs = append(attrs, "peer", Scrub(e.Peer))
	}
	if e.FP != "" {
		attrs = append(attrs, "fp", Scrub(e.FP))
	}
	if e.Target != "" {
		attrs = append(attrs, "target", Scrub(e.Target))
	}
	if e.File != "" {
		attrs = append(attrs, "file", Scrub(e.File))
	}
	if e.Bytes > 0 {
		attrs = append(attrs, "bytes", e.Bytes)
	}
	if e.Offset > 0 {
		attrs = append(attrs, "offset", e.Offset)
	}
	if e.Size > 0 {
		attrs = append(attrs, "size", e.Size)
	}
	if e.Misses > 0 {
		attrs = append(attrs, "misses", e.Misses)
	}
	if e.Session != "" {
		attrs = append(attrs, "session", Scrub(e.Session))
	}
	if e.Reason != "" {
		attrs = append(attrs, "reason", Scrub(e.Reason))
	}
	if e.Age > 0 {
		attrs = append(attrs, "age_ms", e.Age.Milliseconds())
	}
	if e.Elapsed > 0 {
		attrs = append(attrs, "elapsed_ms", e.Elapsed.Milliseconds())
	}
	if e.SpeedBps > 0 {
		attrs = append(attrs, "speed_bps", e.SpeedBps)
	}
	if e.Error != "" {
		attrs = append(attrs, "err", Scrub(e.Error))
	}
	return attrs
}

// Recorder is a bounded, concurrency-safe history of events. The zero value is
// not usable; call New. A nil *Recorder is a valid no-op.
type Recorder struct {
	mu   sync.Mutex
	max  int
	logs []Entry
	// logger is the fallback desktop logger used by Record when the caller has
	// none of its own (discovery, pairing).
	logger *slog.Logger
}

// New returns a recorder that keeps at most max entries (default 200).
func New(max int) *Recorder {
	if max <= 0 {
		max = 200
	}
	return &Recorder{max: max}
}

// NewWithLogger is New plus a fallback desktop logger. Record(nil, e) then
// still reaches the desktop log and the rotating file.
func NewWithLogger(max int, l *slog.Logger) *Recorder {
	r := New(max)
	r.logger = l
	return r
}

// SetLogger sets the fallback desktop logger.
func (r *Recorder) SetLogger(l *slog.Logger) {
	if r == nil {
		return
	}
	r.mu.Lock()
	r.logger = l
	r.mu.Unlock()
}

// loggerFallback returns the fallback logger, if any.
func (r *Recorder) loggerFallback() *slog.Logger {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.logger
}

// Add appends an entry to the in-memory history.
func (r *Recorder) Add(e Entry) {
	if r == nil {
		return
	}
	if e.Time.IsZero() {
		e.Time = time.Now()
	}
	r.mu.Lock()
	r.logs = append(r.logs, e)
	if len(r.logs) > r.max {
		r.logs = r.logs[len(r.logs)-r.max:]
	}
	r.mu.Unlock()
}

// Record writes the entry to the desktop log (debug for routine healthy
// chatter, info for normal steps, warn and error for failures) and keeps it for
// the diagnostics report. l may be nil, in which case the recorder's fallback
// logger is used. A nil recorder is a no-op.
func (r *Recorder) Record(l *slog.Logger, e Entry) {
	if r == nil {
		return
	}
	r.Add(e)
	if l == nil {
		l = r.loggerFallback()
	}
	if l == nil {
		return
	}
	switch e.Level {
	case LevelError:
		l.Error(e.summary(), e.attrs()...)
	case LevelWarn:
		l.Warn(e.summary(), e.attrs()...)
	case LevelDebug:
		l.Debug(e.summary(), e.attrs()...)
	default:
		l.Info(e.summary(), e.attrs()...)
	}
}

// Entries returns a copy of the recorded history, oldest first.
func (r *Recorder) Entries() []Entry {
	if r == nil {
		return nil
	}
	r.mu.Lock()
	defer r.mu.Unlock()
	out := make([]Entry, len(r.logs))
	copy(out, r.logs)
	return out
}

// Report renders the history as plain text for the copyable diagnostics log.
// Every line carries a timestamp, the area tag, the peer short fingerprint and
// the outcome, and the whole block is scrubbed of secrets and full paths.
// It returns a trailing-newline-terminated block, or "" when empty.
func (r *Recorder) Report() string {
	entries := r.Entries()
	if len(entries) == 0 {
		return ""
	}
	var b strings.Builder
	b.WriteString("Diagnostics log\n")
	for _, e := range entries {
		fmt.Fprintf(&b, "  %s [%s] area=%s %s",
			e.Time.Format(time.RFC3339), strings.ToUpper(string(e.Level)), e.areaTag(), e.logTag())
		if e.FP != "" {
			fmt.Fprintf(&b, " peer=%s", Scrub(e.FP))
		} else if e.Peer != "" {
			fmt.Fprintf(&b, " peer=%s", Scrub(e.Peer))
		}
		fmt.Fprintf(&b, " outcome=%s", Scrub(e.outcomeTag()))
		if e.Target != "" {
			fmt.Fprintf(&b, " target=%s", Scrub(e.Target))
		}
		if e.File != "" {
			fmt.Fprintf(&b, " file=%s", Scrub(e.File))
		}
		if e.Session != "" {
			fmt.Fprintf(&b, " session=%s", Scrub(e.Session))
		}
		if e.Reason != "" {
			fmt.Fprintf(&b, " reason=%q", Scrub(e.Reason))
		}
		if e.Bytes > 0 {
			fmt.Fprintf(&b, " bytes=%d", e.Bytes)
		}
		if e.Offset > 0 {
			fmt.Fprintf(&b, " offset=%d", e.Offset)
		}
		if e.Size > 0 {
			fmt.Fprintf(&b, " size=%d", e.Size)
		}
		if e.Misses > 0 {
			fmt.Fprintf(&b, " misses=%d", e.Misses)
		}
		if e.Age > 0 {
			fmt.Fprintf(&b, " age=%s", e.Age.Round(time.Millisecond))
		}
		if e.Elapsed > 0 {
			fmt.Fprintf(&b, " elapsed=%s", e.Elapsed.Round(time.Millisecond))
		}
		if e.SpeedBps > 0 {
			fmt.Fprintf(&b, " speed=%d B/s", e.SpeedBps)
		}
		if e.Error != "" {
			fmt.Fprintf(&b, " err=%q", Scrub(e.Error))
		}
		b.WriteByte('\n')
	}
	return b.String()
}

// logTag is the "<direction>/<step>" token kept for transfer entries so the
// existing diagnostics readers and tests stay valid; other areas use the
// outcome.
func (e Entry) logTag() string {
	if e.Direction != "" && e.Step != "" {
		return e.Direction + "/" + e.Step
	}
	if e.Step != "" {
		return e.Step
	}
	return e.outcomeTag()
}
