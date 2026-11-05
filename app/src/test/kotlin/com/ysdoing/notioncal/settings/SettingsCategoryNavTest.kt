package com.ysdoing.notioncal.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.core.data.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 11f ST-01·03: 폭 600dp 미만은 위쪽 탭 줄, 이상은 왼쪽 레일. 탭은 누르기로만 바꾸고 문제 있는 카테고리는 글자로도 알린다. */
@RunWith(RobolectricTestRunner::class)
class SettingsCategoryNavTest {
    @get:Rule
    val compose = createComposeRule()

    private fun show(selected: SettingsTab = SettingsTab.CONNECTION, badges: Set<SettingsTab> = emptySet(), onSelect: (SettingsTab) -> Unit = {}) {
        compose.setContent {
            MaterialTheme { SettingsCategoryNav(selected, badges, onSelect) { Text("내용 ${selected.id}") } }
        }
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun narrowWidthUsesAScrollableTabRowWithEveryCategory() {
        show()
        compose.onNodeWithTag("settingsTabRow").assertExists()
        compose.onNodeWithTag("settingsRail").assertDoesNotExist()
        SettingsTab.entries.forEach { compose.onNodeWithTag("settingsTab_${it.id}").assertExists() }
        compose.onNodeWithTag("settingsTab_connection").assertIsSelected()
        compose.onNodeWithText("내용 connection").assertExists()
    }

    @Test
    @Config(qualifiers = "w720dp-h891dp-xxhdpi")
    fun wideWidthUsesANavigationRail() {
        show(SettingsTab.DISPLAY)
        compose.onNodeWithTag("settingsRail").assertExists()
        compose.onNodeWithTag("settingsTabRow").assertDoesNotExist()
        compose.onNodeWithTag("settingsTab_display").assertIsSelected()
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun pressingATabSelectsIt() {
        val picked = mutableListOf<SettingsTab>()
        show(onSelect = { picked += it })
        compose.onNodeWithTag("settingsTab_sync").performClick()
        assertEquals(listOf(SettingsTab.SYNC), picked)
    }

    @Test
    @Config(qualifiers = "w411dp-h891dp-xxhdpi")
    fun aCategoryWithAProblemIsSpokenAsSuchNotOnlyDotted() {
        show(badges = setOf(SettingsTab.NOTIFICATIONS))
        compose.onNodeWithTag("settingsTab_notifications").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "문제 있음"))
        compose.onNodeWithTag("settingsTab_sync").assert(SemanticsMatcher.keyNotDefined(SemanticsProperties.StateDescription))
    }

    @Test
    @Config(qualifiers = "w720dp-h891dp-xxhdpi")
    fun theRailSpeaksProblemsToo() {
        show(badges = setOf(SettingsTab.DATABASE))
        compose.onNodeWithTag("settingsTab_database").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "문제 있음"))
    }
}
