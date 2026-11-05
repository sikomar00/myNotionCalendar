package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.view.FormField
import com.ysdoing.notioncal.core.data.view.InputForm
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

/**
 * Phase 11g AD-05 '프리셋 만들기': 위젯 편집 화면에서 연 새 항목 시트는 항목을 만들지 않고 프리셋만 저장한다
 * (제목 = 프리셋 이름, 값 = 양식·칩의 값). 날짜는 프리셋에 담기지 않으니 날짜 칩도 문장의 날짜 읽기도 없다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewAddPresetOnlyTest {
    @get:Rule
    val compose = createComposeRule()

    private val amount = ViewColumn("n", "금액", "number")
    private val kind = ViewColumn("k", "분류", "select", listOf(ViewChoice("o1", "식비"), ViewChoice("o2", "교통")))
    private val columns = listOf(ViewColumn("t", "이름", "title"), amount, kind, ViewColumn("d", "날짜", "date"))
    private val saved = mutableListOf<String>()
    private val presets = mutableListOf<Pair<String, Map<String, JsonElement>>>()
    private var presetResult = true

    private fun show(input: InputForm? = null, canAdd: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                ViewAddContent(
                    viewName = "가계부", hasDate = true, today = LocalDate.of(2026, 11, 4), columns = columns, input = input, canAdd = canAdd, presetOnly = true,
                    onSave = { title, _, _ -> saved += title }, onCancel = {},
                    onSavePreset = { title, props -> presets += title to props; presetResult },
                )
            }
        }
    }

    @Test
    fun theSheetOnlyOffersToSaveThePreset() {
        show()
        compose.onNodeWithText("프리셋 만들기").assertExists()
        compose.onNodeWithText("추가").assertDoesNotExist()
        compose.onNodeWithText("오늘").assertDoesNotExist() // 날짜 칩 없음
        compose.onNodeWithText("프리셋으로 저장").assertExists()
    }

    @Test
    fun savingStoresTheTitleAsTheNameWithTheFormValuesAndNeverCreatesAnItem() {
        show(InputForm(listOf(FormField("k", JsonPrimitive("o2")), FormField("n"))))
        compose.onNodeWithText("제목").performTextInput("택시")
        compose.onNodeWithText("프리셋으로 저장").performClick()
        assertEquals(listOf("택시" to mapOf<String, JsonElement>("k" to JsonPrimitive("o2"))), presets)
        assertEquals(emptyList<String>(), saved)
    }

    @Test
    fun readValuesFromTheSentenceGoIntoThePresetButADateWordStaysInTheName() {
        show()
        compose.onNodeWithText("제목").performTextInput("내일 점심 12000 식비")
        compose.onNodeWithText("금액 12000").assertExists()
        compose.onNodeWithText("11/5").assertDoesNotExist()
        compose.onNodeWithText("프리셋으로 저장").performClick()
        assertEquals(listOf("내일 점심" to mapOf<String, JsonElement>("n" to JsonPrimitive(12000.0), "k" to JsonPrimitive("o1"))), presets)
    }

    @Test
    fun anItemUnavailableNoticeIsNotShownBecauseNothingIsCreated() {
        show(canAdd = false)
        compose.onNodeWithText("프리셋으로 저장").assertExists()
        assertEquals(0, compose.onAllNodesWithTextCount("만들 수 없"))
    }

    @Test
    fun aBlankNameCannotBeSaved() {
        show()
        compose.onNodeWithText("프리셋으로 저장").performClick()
        assertEquals(emptyList<Any>(), presets)
    }

    @Test
    fun aFailedSaveIsSaidSo() {
        presetResult = false
        show()
        compose.onNodeWithText("제목").performTextInput("택시")
        compose.onNodeWithText("프리셋으로 저장").performClick()
        compose.onNodeWithText("프리셋을 저장하지 못했어요", substring = true).assertExists()
    }
}

private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTextCount(text: String): Int =
    onAllNodes(androidx.compose.ui.test.hasText(text, substring = true)).fetchSemanticsNodes().size
