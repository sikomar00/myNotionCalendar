package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** Phase 11c AD-01·05: 새 항목 시트가 보기 필터의 같음 조건 값을 미리 채워 보이고(고칠 수 있다), 그 값으로 만들거나 프리셋으로 저장한다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewAddPrefillTest {
    @get:Rule
    val compose = createComposeRule()

    private val kind = ViewColumn("c", "분류", "select", listOf(ViewChoice("a", "집"), ViewChoice("b", "회사")))
    private val saved = mutableListOf<Triple<String, LocalDate?, Map<String, JsonElement>>>()
    private val presets = mutableListOf<Pair<String, Map<String, JsonElement>>>()

    private fun show(prefill: ViewAddPrefill, canPreset: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                ViewAddContent(
                    viewName = "할 일", hasDate = false, today = LocalDate.of(2026, 11, 4),
                    onSave = { title, date, props -> saved += Triple(title, date, props) },
                    onCancel = {},
                    prefill = prefill,
                    onSavePreset = if (canPreset) { title, props -> presets += title to props } else null,
                )
            }
        }
    }

    @Test
    fun prefilledFieldsAreShownEditableAndSentWithTheNewItem() {
        show(ViewAddPrefill(listOf(kind), mapOf("c" to JsonPrimitive("a"))))
        compose.onNodeWithText("필터에서 채운 값").assertExists()
        compose.onNodeWithText("회사").performClick() // 채운 값을 바꾼다
        compose.onNodeWithText("제목").performTextInput("점심")
        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf(Triple("점심", null as LocalDate?, mapOf<String, JsonElement>("c" to JsonPrimitive("b")))), saved)
    }

    @Test
    fun noPrefilledSectionWhenTheFilterGivesNothing() {
        show(ViewAddPrefill())
        compose.onNodeWithText("필터에서 채운 값").assertDoesNotExist()
        compose.onNodeWithText("제목").performTextInput("점심")
        compose.onNodeWithText("추가").performClick()
        assertEquals(emptyMap<String, JsonElement>(), saved.single().third)
    }

    @Test
    fun savingAPresetNeedsATitleAndTakesTheCurrentValues() {
        show(ViewAddPrefill(listOf(kind), mapOf("c" to JsonPrimitive("a"))))
        compose.onNodeWithText("프리셋으로 저장").assertIsNotEnabled() // 제목이 프리셋 이름이다
        compose.onNodeWithText("제목").performTextInput("점심")
        compose.onNodeWithText("프리셋으로 저장").assertIsEnabled().performClick()
        assertEquals(listOf("점심" to mapOf<String, JsonElement>("c" to JsonPrimitive("a"))), presets)
        compose.onNodeWithText("프리셋을 저장했어요").assertExists()
        assertEquals(0, saved.size) // 저장은 만드는 것이 아니다
    }

    @Test
    fun noPresetButtonWhenTheSheetWasNotOpenedFromAWidget() {
        show(ViewAddPrefill(), canPreset = false)
        compose.onNodeWithText("프리셋으로 저장").assertDoesNotExist()
    }

    @Test
    fun prefillComesFromTheSimpleEqualsConditionsOfTheViewFilterOnly() {
        val board = ViewBoard(
            "v1", "할 일", "table", listOf("이름"), emptyList(), 1, null,
            listOf(ViewColumn("t", "이름", "title"), kind, ViewColumn("wh", "날짜", "date"), ViewColumn("fm", "수식", "formula")),
            filterJson = """{"and":[{"property":"c","select":{"equals":"집"}},{"property":"t","title":{"equals":"x"}},{"property":"wh","date":{"equals":"2026-01-01"}}]}""",
        )
        val prefill = prefillOf(board)
        assertEquals(listOf("c"), prefill.columns.map { it.id }) // 제목·날짜는 따로 정하고, 같음이 아닌 것은 채우지 않는다
        assertEquals(mapOf<String, JsonElement>("c" to JsonPrimitive("a")), prefill.values)
        assertEquals(ViewAddPrefill(), prefillOf(board.copy(filterJson = "")))
    }
}
