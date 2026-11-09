package com.ysdoing.notioncal.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** UX-02·03: 토글·선택 줄은 줄 전체가 눌리고(최소 48dp) 글자가 컨트롤의 이름이다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class ToggleRowsTest {
    @get:Rule
    val compose = createComposeRule()

    private fun hasRole(role: Role) = SemanticsMatcher.expectValue(SemanticsProperties.Role, role)

    @Test
    fun pressingTheLabelFlipsTheSwitchRowAndItIsNamedByItsText() {
        var on by mutableStateOf(false)
        compose.setContent { MaterialTheme { SwitchRow("완료한 일정 숨기기", on) { on = it } } }
        compose.onNodeWithText("완료한 일정 숨기기").assertIsOff().assert(hasRole(Role.Switch))
        compose.onNodeWithText("완료한 일정 숨기기").performClick()
        compose.onNodeWithText("완료한 일정 숨기기").assertIsOn()
        assertEquals(true, on)
    }

    @Test
    fun theSwitchRowShowsItsNoteAndKeepsAtLeast48dp() {
        compose.setContent { MaterialTheme { SwitchRow("금액 가리기", true, note = "●●●로 보여요") {} } }
        compose.onNodeWithText("금액 가리기").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("●●●로 보여요", useUnmergedTree = true).assertExists()
    }

    @Test
    fun pressingTheLabelFlipsTheCheckboxRow() {
        var checked by mutableStateOf(true)
        compose.setContent { MaterialTheme { CheckboxRow("모든 데이터베이스", checked) { checked = it } } }
        compose.onNodeWithText("모든 데이터베이스").assertIsOn().assert(hasRole(Role.Checkbox)).assertHeightIsAtLeast(48.dp)
        compose.onNodeWithText("모든 데이터베이스").performClick()
        compose.onNodeWithText("모든 데이터베이스").assertIsOff()
    }

    @Test
    fun pressingTheLabelSelectsTheRadioRow() {
        var picked by mutableStateOf("a")
        compose.setContent {
            MaterialTheme {
                RadioRow("가장 가까운 것", picked == "a") { picked = "a" }
                RadioRow("보류", picked == "b") { picked = "b" }
            }
        }
        compose.onNodeWithText("가장 가까운 것").assertIsSelected().assert(hasRole(Role.RadioButton))
        compose.onNodeWithText("보류").assertIsNotSelected().assertHeightIsAtLeast(48.dp).performClick()
        compose.onNodeWithText("보류").assertIsSelected()
        compose.onNodeWithText("가장 가까운 것").assertIsNotSelected()
    }
}
