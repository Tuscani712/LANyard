package io.github.tuscani712.lanyard.core

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Guards the settings taxonomy: every [AppSettings] property must map to
 * exactly one [SettingsCategory] tab, and the map must not drift from the data
 * class in either direction. If this fails after adding a setting, add it to
 * `SETTINGS_CATEGORIES` (and to the tab's UI in `SettingsScreen.kt`).
 */
class SettingsCategoryTest {
    private val fields: List<String> =
        AppSettings::class.java.declaredFields
            .filterNot { it.isSynthetic }
            .map { it.name }

    @Test
    fun everyAppSettingsPropertyMapsToExactlyOneCategory() {
        assertTrue(fields.isNotEmpty(), "no AppSettings fields were found by reflection")
        fields.forEach { name ->
            assertNotNull(categoryFor(name), "setting '$name' has no SettingsCategory")
        }
    }

    @Test
    fun categoryMapHasNoStaleOrForeignKeys() {
        assertEquals(
            fields.toSet(),
            SETTINGS_CATEGORIES.keys,
            "SETTINGS_CATEGORIES must name exactly the AppSettings properties",
        )
    }

    @Test
    fun tabNamesAreTheApprovedSeven() {
        assertEquals(
            listOf(
                "General",
                "Receiving",
                "Network & Discovery",
                "Notifications",
                "Pairing & Security",
                "Logs & Diagnostics",
                "About",
            ),
            SettingsCategory.entries.map { it.label },
        )
    }

    @Test
    fun expectedPlacements() {
        assertEquals(SettingsCategory.GENERAL, categoryFor("theme"))
        assertEquals(SettingsCategory.GENERAL, categoryFor("speedUnit"))
        assertEquals(SettingsCategory.RECEIVING, categoryFor("downloadFolder"))
        assertEquals(SettingsCategory.RECEIVING, categoryFor("bandwidthLimitMBps"))
        assertEquals(SettingsCategory.NETWORK_DISCOVERY, categoryFor("preferredPort"))
        assertEquals(SettingsCategory.NETWORK_DISCOVERY, categoryFor("wifiOnly"))
        assertEquals(SettingsCategory.NOTIFICATIONS, categoryFor("notifications"))
        assertEquals(SettingsCategory.NOTIFICATIONS, categoryFor("soundOnComplete"))
    }
}
