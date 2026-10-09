package discovery

import (
	"strings"
	"testing"

	"lanyard/internal/xferlog"
)

// Discovery is one of the four logged areas: a device seen over mDNS, a failed
// liveness probe and an eviction must all land in the shared log with the area
// tag and a short fingerprint.
func TestDiscoveryLogsSeenProbeEvict(t *testing.T) {
	rec := xferlog.New(200)
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), failingProbe, discardLogger())
	m.SetXferLog(rec)

	short := strings.Repeat("a", 16)
	m.handle(Announcement{ShortID: short, Name: "phone", Port: 47800}, []string{"10.0.0.9"}, "mdns")
	m.wg.Wait()

	for i := 0; i < defaultEvictAfter+2; i++ {
		m.sweep()
		m.wg.Wait()
	}

	want := map[string]bool{"seen": false, "probe": false, "evict": false}
	for _, e := range rec.Entries() {
		if e.Area != xferlog.AreaDiscovery {
			t.Errorf("entry not tagged discovery: %+v", e)
		}
		if _, ok := want[e.Outcome]; ok {
			want[e.Outcome] = true
		}
	}
	for outcome, ok := range want {
		if !ok {
			t.Errorf("discovery outcome %q not logged: %+v", outcome, rec.Entries())
		}
	}
}

// The active-transfer guard keeps a peer and says so in the log.
func TestDiscoveryLogsActiveGuard(t *testing.T) {
	rec := xferlog.New(50)
	fp := strings.Repeat("b", 64)
	short := fp[:16]
	m := New(Announcement{Name: "me"}, strings.Repeat("f", 64), failingProbe, discardLogger())
	m.SetXferLog(rec)
	seedPeer(m, short, fp, []string{"10.0.0.10"}, 47800)
	m.SetActiveTransfer(func(s, f string) bool { return s == short || f == fp })

	m.sweep()
	m.wg.Wait()

	found := false
	for _, e := range rec.Entries() {
		if e.Outcome == "guard" && e.Area == xferlog.AreaDiscovery {
			found = true
		}
	}
	if !found {
		t.Errorf("active-transfer guard not logged: %+v", rec.Entries())
	}
}
