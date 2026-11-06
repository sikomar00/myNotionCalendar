package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
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
 * Phase 11g AD-03 새 항목 시트의 한 줄 입력: 쓰는 동안 읽은 날짜·금액·옵션이 칩으로 보이고(누르면 끔), 추가하면 제목에서 빠진 채 값이 함께 쌓인다.
 * 필터에서 채운 속성은 사용자가 정한 값이라 해석이 건드리지 않는다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class ViewAddQuickInputTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 3)
    private val amount = ViewColumn("n1", "금액", "number")
    private val category = ViewColumn("c1", "분류", "select", listOf(ViewChoice("o-food", "식비"), ViewChoice("o-move", "교통")))
    private val date = ViewColumn("d1", "날짜", "date")
    private val columns = listOf(ViewColumn("t", "이름", "title"), amount, category, date)
    private val saved = mutableListOf<Triple<String, LocalDate?, Map<String, JsonElement>>>()

    private fun show(prefill: ViewAddPrefill = ViewAddPrefill(), withColumns: List<ViewColumn> = columns) {
        compose.setContent {
            MaterialTheme {
                ViewAddContent(
                    viewName = "가계부", hasDate = true, today = today, columns = withColumns, prefill = prefill,
                    onSave = { title, day, props -> saved += Triple(title, day, props) }, onCancel = {},
                )
            }
        }
    }

    private fun type(text: String) = compose.onNodeWithText("제목").performTextInput(text)

    @Test
    fun aSentenceShowsItsReadValuesAsChips() {
        show()
        type("내일 점심 12000 식비")
        compose.onNodeWithText("11/4").assertIsSelected()
        compose.onNodeWithText("금액 12000").assertIsSelected()
        compose.onNodeWithText("분류 식비").assertIsSelected()
    }

    @Test
    fun addingSavesTheTitleWithoutTheReadWordsAndTheValues() {
        show()
        type("내일 점심 12000 식비")
        compose.onNodeWithText("추가").performClick()
        val (title, day, props) = saved.single()
        assertEquals("점심", title)
        assertEquals(today.plusDays(1), day)
        assertEquals(mapOf<String, JsonElement>("n1" to JsonPrimitive(12000.0), "c1" to JsonPrimitive("o-food")), props)
    }

    @Test
    fun pressingAChipTurnsItOffAndItsWordsStayInTheTitle() {
        show()
        type("점심 12000 식비")
        compose.onNodeWithText("금액 12000").performClick()
        compose.onNodeWithText("금액 12000").assertExists() // 꺼도 칩은 남아 다시 켤 수 있다
        compose.onNodeWithText("추가").performClick()
        val (title, _, props) = saved.single()
        assertEquals("점심 12000", title)
        assertEquals(mapOf<String, JsonElement>("c1" to JsonPrimitive("o-food")), props)
    }

    @Test
    fun aTurnedOffChipCanBeTurnedBackOn() {
        show()
        type("점심 12000 식비")
        compose.onNodeWithText("금액 12000").performClick()
        compose.onNodeWithText("금액 12000").performClick()
        compose.onNodeWithText("추가").performClick()
        assertEquals("점심", saved.single().first)
    }

    @Test
    fun anExplicitDateChipBeatsTheDateReadFromTheSentenceAndLeavesItsWordInTheTitle() {
        show()
        type("내일 보고서")
        compose.onNodeWithText("11/4").assertIsSelected()
        compose.onNodeWithText("오늘").performClick()
        compose.onNodeWithText("11/4").assertIsNotSelected() // 문장에서 읽은 날짜는 쓰이지 않으니 선택으로 보이면 안 된다
        compose.onNodeWithText("추가").performClick()
        assertEquals(today, saved.single().second)
        assertEquals("내일 보고서", saved.single().first) // 읽지 않은 날짜 낱말은 제목에 남는다
    }

    @Test
    fun theNoDateChipDropsTheDateReadFromTheSentence() {
        show()
        type("내일 보고서")
        compose.onNodeWithText("날짜 없음").assertIsNotSelected() // 읽은 날짜가 쓰이는 동안은 그 칩이 선택이다
        compose.onNodeWithText("날짜 없음").performClick()
        compose.onNodeWithText("날짜 없음").assertIsSelected()
        compose.onNodeWithText("추가").performClick()
        assertEquals(null, saved.single().second)
        assertEquals("내일 보고서", saved.single().first)
    }

    @Test
    fun turningTheReadDateBackOnTakesBackTheExplicitDate() {
        show()
        type("내일 보고서")
        compose.onNodeWithText("오늘").performClick()
        compose.onNodeWithText("11/4").performClick()
        compose.onNodeWithText("11/4").assertIsSelected()
        compose.onNodeWithText("추가").performClick()
        assertEquals(today.plusDays(1), saved.single().second)
        assertEquals("보고서", saved.single().first)
    }

    @Test
    fun aValueFreedByTurningAnInterpretationOffGetsItsOwnChip() {
        show()
        type("마트 1.5 쇼핑")
        compose.onNodeWithText("1/5").assertIsSelected() // 점이 든 숫자는 날짜 열이 있으면 먼저 날짜로 읽힌다
        compose.onNodeWithText("1/5").performClick()
        compose.onNodeWithText("1/5").assertIsNotSelected() // 끈 칩은 남아 다시 켤 수 있다
        compose.onNodeWithText("금액 1.5").assertIsSelected() // 풀린 숫자는 금액으로 읽히고 보이는 칩이 있다
        compose.onNodeWithText("추가").performClick()
        assertEquals(null, saved.single().second)
        assertEquals("마트 쇼핑", saved.single().first)
        assertEquals(mapOf<String, JsonElement>("n1" to JsonPrimitive(1.5)), saved.single().third)
    }

    @Test
    fun withoutColumnInfoNothingIsReadAndTheTitleIsSavedAsTyped() {
        show(withColumns = emptyList())
        type("점심 12000 식비")
        compose.onNodeWithText("금액 12000").assertDoesNotExist()
        compose.onNodeWithText("추가").performClick()
        assertEquals("점심 12000 식비", saved.single().first)
    }

    @Test
    fun aPrefilledPropertyIsLeftToTheUserSoItsWordStaysInTheTitle() {
        val prefill = ViewAddPrefill(listOf(category), mapOf("c1" to JsonPrimitive("o-move")))
        show(prefill)
        type("점심 12000 식비")
        compose.onNodeWithText("분류 식비").assertDoesNotExist()
        compose.onNodeWithText("추가").performClick()
        val (title, _, props) = saved.single()
        assertEquals("점심 식비", title)
        assertEquals(mapOf<String, JsonElement>("c1" to JsonPrimitive("o-move"), "n1" to JsonPrimitive(12000.0)), props)
    }

    @Test
    fun aSentenceThatWouldLeaveNoTitleIsNotRead() {
        show()
        type("12000 식비")
        compose.onNodeWithText("금액 12000").assertDoesNotExist()
        compose.onNodeWithText("추가").performClick()
        assertEquals("12000 식비", saved.single().first)
    }
}
