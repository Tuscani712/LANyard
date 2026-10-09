package io.github.tuscani712.lanyard.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import io.github.tuscani712.lanyard.core.SettingsCategory

/** Test tags for the Settings tabs, kept in one place for the UI test. */
object SettingsTestTags {
    const val TAB_ROW = "settings-tab-row"
    const val TAB_CONTENT = "settings-tab-content"
    const val SAVED = "settings-saved"
    const val SAVE_ERROR = "settings-save-error"

    fun tab(category: SettingsCategory): String = "settings-tab-${category.name}"
}

/**
 * The Settings tab strip and its scrolling body, shared by the screen and its
 * tests. The selected tab and the "saved" marker are hoisted by the caller so
 * they live above the Troubleshoot/Licenses early returns (surviving rotation
 * and process death) and so Back from a sub-screen lands on the same tab.
 */
@Composable
internal fun SettingsTabs(
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    savedTab: Int,
    saveError: String?,
    modifier: Modifier = Modifier,
    content: @Composable (SettingsCategory) -> Unit,
) {
    Column(modifier = modifier.fillMaxSize()) {
        ScrollableTabRow(
            selectedTabIndex = selectedTab,
            edgePadding = 0.dp,
            modifier = Modifier.testTag(SettingsTestTags.TAB_ROW),
        ) {
            SettingsCategory.entries.forEachIndexed { index, category ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { onTabSelected(index) },
                    text = { Text(category.label, maxLines = 1, softWrap = false) },
                    modifier = Modifier.testTag(SettingsTestTags.tab(category)),
                )
            }
        }
        val category = SettingsCategory.entries[selectedTab]
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(24.dp)
                .testTag(SettingsTestTags.TAB_CONTENT),
        ) {
            content(category)
            SavedStatus(visible = savedTab == selectedTab, error = saveError)
        }
    }
}

/**
 * The per-tab save line. A storage error always wins because it is the more
 * important signal; otherwise "Saved." shows only on the tab that was changed.
 */
@Composable
private fun SavedStatus(visible: Boolean, error: String?) {
    when {
        error != null -> {
            Spacer(Modifier.height(16.dp))
            Text(
                error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag(SettingsTestTags.SAVE_ERROR),
            )
        }
        visible -> {
            Spacer(Modifier.height(16.dp))
            Text(
                "Saved.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag(SettingsTestTags.SAVED),
            )
        }
    }
}
