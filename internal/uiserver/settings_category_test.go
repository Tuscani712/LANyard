package uiserver

import (
	"os"
	"reflect"
	"strings"
	"testing"

	"lanyard/internal/config"
)

// Every field on config.Settings must belong to one of the seven Settings
// categories shared with the phone app. A new JSON field with no category
// fails here, which is the guard against a setting that would be invisible on
// the Settings tabs.
func TestEverySettingsFieldHasCategory(t *testing.T) {
	valid := make(map[config.SettingsCategory]bool)
	for _, c := range config.SettingsCategories() {
		valid[c] = true
	}
	if len(valid) != 7 {
		t.Fatalf("expected 7 Settings categories, got %d", len(valid))
	}

	typ := reflect.TypeOf(config.Settings{})
	for i := 0; i < typ.NumField(); i++ {
		f := typ.Field(i)
		name := strings.Split(f.Tag.Get("json"), ",")[0]
		if name == "" || name == "-" {
			t.Errorf("config.Settings.%s has no usable json tag (%q)", f.Name, f.Tag.Get("json"))
			continue
		}
		cat, ok := config.CategoryOfField(name)
		if !ok {
			t.Errorf("config.Settings.%s (json %q) has no Settings category", f.Name, name)
			continue
		}
		if !valid[cat] {
			t.Errorf("config.Settings.%s maps to unknown category %q", f.Name, cat)
		}
	}
}

// The seven category names are a contract with the phone concept; renaming one
// on either side would desynchronise the two tab strips.
func TestSettingsCategoryNames(t *testing.T) {
	want := []string{
		"General",
		"Receiving",
		"Network & Discovery",
		"Notifications",
		"Pairing & Security",
		"Logs & Diagnostics",
		"About",
	}
	got := config.SettingsCategories()
	if len(got) != len(want) {
		t.Fatalf("got %d categories, want %d", len(got), len(want))
	}
	for i, name := range want {
		if string(got[i]) != name {
			t.Errorf("category %d = %q, want %q", i, got[i], name)
		}
	}
}

// saveSettings must write its result into the live per-tab message node, not a
// detached one. There is no JS test dependency here, so this is a static guard:
// the id must be built from the active tab and re-queried after the re-render.
func TestAppJSSettingsMsgPerTab(t *testing.T) {
	src, err := os.ReadFile("web/app.js")
	if err != nil {
		t.Fatalf("read app.js: %v", err)
	}
	for _, want := range []string{
		`msg.id = "set-msg-" + tab;`,
		`$("set-msg-" + S.settingsTab)`,
	} {
		if !strings.Contains(string(src), want) {
			t.Errorf("app.js is missing per-tab message handling: %q", want)
		}
	}
}
