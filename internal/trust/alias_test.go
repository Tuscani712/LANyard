package trust

import (
	"testing"

	"lanyard/internal/config"
)

// An alias is a local-only display name: it survives a reload (storage
// round-trip), wins over the broadcast name in DisplayName, and clearing it
// reverts to the broadcast name.
func TestAliasRoundTripAndFallback(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	st := New(cfg, "self", nil)
	st.Pair(Entry{DeviceID: "peer-dev", Name: "Pixel 8 Pro", Fingerprint: "peer-fp"})

	// No alias: the broadcast name is shown.
	if e, _ := st.Entry("peer-fp"); e.DisplayName() != "Pixel 8 Pro" {
		t.Fatalf("DisplayName without alias = %q, want the broadcast name", e.DisplayName())
	}

	if !st.SetAlias("peer-fp", "  My Phone  ") {
		t.Fatal("SetAlias on a paired device returned false")
	}
	e, _ := st.Entry("peer-fp")
	if e.Alias != "My Phone" || e.DisplayName() != "My Phone" {
		t.Fatalf("alias = %q display = %q, want trimmed My Phone", e.Alias, e.DisplayName())
	}
	// The broadcast name is untouched by an alias.
	if e.Name != "Pixel 8 Pro" {
		t.Fatalf("setting an alias changed the broadcast name to %q", e.Name)
	}

	// Reload from the persisted config: the alias survives.
	st2 := New(cfg, "self", nil)
	if err := st2.Load(); err != nil {
		t.Fatalf("Load: %v", err)
	}
	if e, ok := st2.Entry("peer-fp"); !ok || e.Alias != "My Phone" || e.DisplayName() != "My Phone" {
		t.Fatalf("alias did not survive reload: %+v ok=%v", e, ok)
	}

	// Clearing reverts to the broadcast name.
	if !st.SetAlias("peer-fp", "") {
		t.Fatal("clearing the alias returned false")
	}
	if e, _ := st.Entry("peer-fp"); e.Alias != "" || e.DisplayName() != "Pixel 8 Pro" {
		t.Fatalf("after clear: alias=%q display=%q, want fallback to the broadcast name", e.Alias, e.DisplayName())
	}
}

// Unpairing drops the paired entry, and with it the alias; re-pairing starts
// fresh with no alias.
func TestUnpairClearsAliasAndRepairStartsFresh(t *testing.T) {
	cfg, err := config.Open(t.TempDir())
	if err != nil {
		t.Fatalf("config.Open: %v", err)
	}
	st := New(cfg, "self", nil)
	st.Pair(Entry{DeviceID: "peer-dev", Name: "Pixel 8 Pro", Fingerprint: "peer-fp"})
	st.SetAlias("peer-fp", "My Phone")
	if !st.Unpair("peer-fp") {
		t.Fatal("Unpair returned false")
	}
	if _, ok := st.Entry("peer-fp"); ok {
		t.Fatal("the entry (and its alias) should be gone after unpair")
	}
	// Re-pair: the fresh entry carries no alias.
	st.Pair(Entry{DeviceID: "peer-dev", Name: "Pixel 8 Pro", Fingerprint: "peer-fp"})
	if e, _ := st.Entry("peer-fp"); e.Alias != "" || e.DisplayName() != "Pixel 8 Pro" {
		t.Fatalf("re-pair should start fresh: alias=%q display=%q", e.Alias, e.DisplayName())
	}
}

// SetAlias on an unpaired device is a no-op that reports false, so a stale
// request cannot resurrect an alias for a device that was removed.
func TestSetAliasUnpairedIsNoop(t *testing.T) {
	st := newStore(t)
	if st.SetAlias("ghost-fp", "Ghost") {
		t.Fatal("SetAlias on an unpaired device should return false")
	}
}
