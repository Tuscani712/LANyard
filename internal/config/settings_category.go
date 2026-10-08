package config

// SettingsCategory names one of the seven Settings destinations. The desktop
// and phone apps share these exact names and the same field-to-category
// mapping, so a new setting cannot silently land on one platform only.
type SettingsCategory string

const (
	CategoryGeneral          SettingsCategory = "General"
	CategoryReceiving        SettingsCategory = "Receiving"
	CategoryNetworkDiscovery SettingsCategory = "Network & Discovery"
	CategoryNotifications    SettingsCategory = "Notifications"
	CategoryPairingSecurity  SettingsCategory = "Pairing & Security"
	CategoryLogsDiagnostics  SettingsCategory = "Logs & Diagnostics"
	CategoryAbout            SettingsCategory = "About"
)

// SettingsCategories is the ordered list of the seven categories; the tab strip
// uses this order on both platforms.
func SettingsCategories() []SettingsCategory {
	return []SettingsCategory{
		CategoryGeneral,
		CategoryReceiving,
		CategoryNetworkDiscovery,
		CategoryNotifications,
		CategoryPairingSecurity,
		CategoryLogsDiagnostics,
		CategoryAbout,
	}
}

// settingsFieldCategory maps every Settings JSON field name to its category.
// It is the single source of truth shared by the desktop test and the phone
// concept; a new Settings field must be added here or the guard test fails.
var settingsFieldCategory = map[string]SettingsCategory{
	"device_name":             CategoryGeneral,
	"device_id_label":         CategoryGeneral,
	"theme":                   CategoryGeneral,
	"speed_unit":              CategoryGeneral,
	"start_on_login":          CategoryGeneral,
	"minimize_to_tray":        CategoryGeneral,
	"default_download_folder": CategoryReceiving,
	"inbox_folder":            CategoryReceiving,
	"bandwidth_limit_mbps":    CategoryReceiving,
	"peer_port":               CategoryNetworkDiscovery,
	"ui_port":                 CategoryNetworkDiscovery,
	"sound_on_complete":       CategoryNotifications,
	"notifications":           CategoryNotifications,
	"mounts":                  CategoryPairingSecurity,
	"shares":                  CategoryPairingSecurity,
	"trust":                   CategoryPairingSecurity,
	"transfers":               CategoryLogsDiagnostics,
	"update_url":              CategoryAbout,
	"auto_update":             CategoryAbout,
}

// CategoryOfField returns the category assigned to a Settings JSON field name,
// and whether one is assigned at all.
func CategoryOfField(jsonField string) (SettingsCategory, bool) {
	c, ok := settingsFieldCategory[jsonField]
	return c, ok
}
