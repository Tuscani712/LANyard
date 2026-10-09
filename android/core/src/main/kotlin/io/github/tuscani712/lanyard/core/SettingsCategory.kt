package io.github.tuscani712.lanyard.core

/**
 * The tabs on the Settings screen, in the order they are shown. Every
 * user-facing setting belongs to exactly one of these, so a new setting cannot
 * ship without a home (see [SETTINGS_CATEGORIES] and its test).
 */
enum class SettingsCategory(val label: String) {
    GENERAL("General"),
    RECEIVING("Receiving"),
    NETWORK_DISCOVERY("Network & Discovery"),
    NOTIFICATIONS("Notifications"),
    PAIRING_SECURITY("Pairing & Security"),
    LOGS_DIAGNOSTICS("Logs & Diagnostics"),
    ABOUT("About"),
}

/**
 * Maps every persisted [AppSettings] property name to the tab it is shown on.
 *
 * The mapping is by property name, not by hand-written UI code, so the test in
 * `SettingsCategoryTest` can reflect over [AppSettings] and fail loudly if a
 * property is ever added without a category. Settings that are not part of
 * [AppSettings] (device name in SharedPreferences, paired devices, shares,
 * history actions) are placed on their tabs directly in `SettingsScreen.kt`.
 */
val SETTINGS_CATEGORIES: Map<String, SettingsCategory> = mapOf(
    "theme" to SettingsCategory.GENERAL,
    "speedUnit" to SettingsCategory.GENERAL,
    "notifications" to SettingsCategory.NOTIFICATIONS,
    "soundOnComplete" to SettingsCategory.NOTIFICATIONS,
    "wifiOnly" to SettingsCategory.NETWORK_DISCOVERY,
    "keepScreenOn" to SettingsCategory.RECEIVING,
    "downloadFolder" to SettingsCategory.RECEIVING,
    "bandwidthLimitMBps" to SettingsCategory.RECEIVING,
    "preferredPort" to SettingsCategory.NETWORK_DISCOVERY,
)

/** The category for a persisted [AppSettings] property name, or null if unknown. */
fun categoryFor(settingKey: String): SettingsCategory? = SETTINGS_CATEGORIES[settingKey]
