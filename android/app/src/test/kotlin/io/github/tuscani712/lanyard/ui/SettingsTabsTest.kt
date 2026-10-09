package io.github.tuscani712.lanyard.ui

import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import io.github.tuscani712.lanyard.core.SettingsCategory
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * UI/state coverage for the tabbed Settings screen. It exercises the real
 * [SettingsTabs] scaffold (the same one `SettingsScreen` uses) with stub tab
 * bodies, because the real bodies reach app-scoped singletons (`PeerService`,
 * `SettingsHolder`) that need a started service. The category-to-field mapping
 * itself is covered by `SettingsCategoryTest` in `:core`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SettingsTabsTest {
    @get:Rule
    val rule = createAndroidComposeRule<ComponentActivity>()

    @Composable
    private fun Harness(saveError: String? = null) {
        var selected by rememberSaveable { mutableIntStateOf(0) }
        var savedTab by rememberSaveable { mutableIntStateOf(-1) }
        var showSub by rememberSaveable { mutableStateOf(false) }
        if (showSub) {
            BackHandler { showSub = false }
            Text("SUBSCREEN", modifier = Modifier.testTag("subscreen"))
            return
        }
        Column {
            SettingsTabs(
                selectedTab = selected,
                onTabSelected = { selected = it },
                savedTab = savedTab,
                saveError = saveError,
            ) { category ->
                Text("content-${category.name}", modifier = Modifier.testTag("content-${category.name}"))
                TextButton(onClick = { savedTab = selected }) { Text("mark-saved") }
                TextButton(onClick = { showSub = true }) { Text("open-sub") }
            }
        }
    }

    @Test
    fun allSevenTabsAreShown() {
        rule.setContent { Harness() }
        SettingsCategory.entries.forEach { category ->
            rule.onNodeWithText(category.label).assertExists()
        }
        assertEquals(7, SettingsCategory.entries.size)
    }

    private fun clickTab(category: SettingsCategory) {
        rule.onNodeWithTag(SettingsTestTags.tab(category)).performScrollTo().performClick()
    }

    @Test
    fun everyTabShowsItsOwnFields() {
        rule.setContent { Harness() }
        SettingsCategory.entries.forEach { category ->
            clickTab(category)
            rule.onNodeWithTag("content-${category.name}").assertIsDisplayed()
        }
    }

    @Test
    fun savedAppearsAfterAChangeOnTheCurrentTab() {
        rule.setContent { Harness() }
        rule.onNodeWithTag(SettingsTestTags.SAVED).assertDoesNotExist()
        rule.onNodeWithText("mark-saved").performClick()
        rule.onNodeWithTag(SettingsTestTags.SAVED).assertExists()
    }

    @Test
    fun savedDoesNotLeakToOtherTabs() {
        rule.setContent { Harness() }
        rule.onNodeWithText("mark-saved").performClick()
        rule.onNodeWithTag(SettingsTestTags.SAVED).assertExists()
        clickTab(SettingsCategory.NOTIFICATIONS)
        rule.onNodeWithTag(SettingsTestTags.SAVED).assertDoesNotExist()
    }

    @Test
    fun saveErrorIsSurfaced() {
        rule.setContent { Harness(saveError = "Couldn't save settings: disk full") }
        rule.onNodeWithTag(SettingsTestTags.SAVE_ERROR).assertExists()
    }

    @Test
    fun selectedTabSurvivesRecreation() {
        val restoration = StateRestorationTester(rule)
        restoration.setContent { Harness() }
        clickTab(SettingsCategory.NOTIFICATIONS)
        rule.onNodeWithTag("content-${SettingsCategory.NOTIFICATIONS.name}").assertIsDisplayed()
        restoration.emulateSavedInstanceStateRestore()
        rule.onNodeWithTag("content-${SettingsCategory.NOTIFICATIONS.name}").assertIsDisplayed()
    }

    @Test
    fun backFromSubScreenReturnsToTheSameTab() {
        rule.setContent { Harness() }
        clickTab(SettingsCategory.RECEIVING)
        rule.onNodeWithTag("content-${SettingsCategory.RECEIVING.name}").assertIsDisplayed()
        rule.onNodeWithText("open-sub").performClick()
        rule.onNodeWithTag("subscreen").assertIsDisplayed()
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
        rule.onNodeWithTag("content-${SettingsCategory.RECEIVING.name}").assertIsDisplayed()
    }
}
