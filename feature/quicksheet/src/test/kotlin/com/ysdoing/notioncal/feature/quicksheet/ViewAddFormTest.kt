package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsSelected
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
 * Phase 11g AD-02 새 항목 시트의 입력 양식: 양식이 있으면 양식의 속성만 그 순서대로 보이고 기본값으로 시작한다. 필터에서 채운 값이 기본값보다 우선하고,
 * '이 값을 기본값으로'가 양식 속성의 지금 값을 돌려 준다. 양식이 없으면 예전과 같다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewAddFormTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 4)
    private val amount = ViewColumn("n", "금액", "number")
    private val kind = ViewColumn("k", "분류", "select", listOf(ViewChoice("o1", "식비"), ViewChoice("o2", "교통")))
    private val memo = ViewColumn("r", "메모", "rich_text")
    private val date = ViewColumn("d", "날짜", "date")
    private val columns = listOf(ViewColumn("t", "이름", "title"), amount, kind, memo, date)
    private val saved = mutableListOf<Triple<String, LocalDate?, Map<String, JsonElement>>>()
    private val defaultsSaved = mutableListOf<Map<String, JsonElement>>()
    private var defaultsResult = true

    private fun show(input: InputForm?, prefill: ViewAddPrefill = ViewAddPrefill(), canSaveDefaults: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                ViewAddContent(
                    viewName = "가계부", hasDate = true, today = today, columns = columns, prefill = prefill, input = input,
                    onSave = { title, day, props -> saved += Triple(title, day, props) }, onCancel = {},
                    onSaveDefaults = if (canSaveDefaults) { values -> defaultsSaved += values; defaultsResult } else null,
                )
            }
        }
    }

    private fun form(vararg fields: FormField, showDate: Boolean = true) = InputForm(fields.toList(), showDate)

    @Test
    fun onlyTheFormPropertiesAreShown() {
        show(form(FormField("n"), FormField("k")))
        compose.onNodeWithText("금액").assertExists()
        compose.onNodeWithText("분류").assertExists()
        compose.onNodeWithText("메모").assertDoesNotExist()
    }

    @Test
    fun withoutAFormNoPropertyEditorIsShownAsBefore() {
        show(null)
        compose.onNodeWithText("금액").assertDoesNotExist()
        compose.onNodeWithText("이 값을 기본값으로").assertDoesNotExist()
    }

    @Test
    fun defaultsStartFilledAndAreSentWithTheNewItem() {
        show(form(FormField("n", JsonPrimitive(5000.0)), FormField("k", JsonPrimitive("o2"))))
        compose.onNodeWithText("교통").assertIsSelected()
        compose.onNodeWithText("제목").performTextInput("택시")
        compose.onNodeWithText("추가").performClick()
        assertEquals(mapOf<String, JsonElement>("n" to JsonPrimitive(5000.0), "k" to JsonPrimitive("o2")), saved.single().third)
    }

    @Test
    fun aFilterPrefillBeatsTheFormDefaultAndIsShownOnlyOnce() {
        show(form(FormField("k", JsonPrimitive("o1")), FormField("n")), ViewAddPrefill(listOf(kind), mapOf("k" to JsonPrimitive("o2"))))
        compose.onNodeWithText("교통").assertIsSelected()
        compose.onNodeWithText("분류").assertExists() // 속성 이름이 한 번만(필터 구역) 보인다
        compose.onNodeWithText("제목").performTextInput("택시")
        compose.onNodeWithText("추가").performClick()
        assertEquals(mapOf<String, JsonElement>("k" to JsonPrimitive("o2")), saved.single().third)
    }

    @Test
    fun hidingTheDateChipRemovesTheChips() {
        show(form(FormField("n"), showDate = false))
        compose.onNodeWithText("오늘").assertDoesNotExist()
        compose.onNodeWithText("내일").assertDoesNotExist()
    }

    @Test
    fun theDateChipIsShownByDefault() {
        show(form(FormField("n")))
        compose.onNodeWithText("오늘").assertExists()
    }

    @Test
    fun aHiddenDateIsNotReadFromTheSentenceEither() {
        show(form(FormField("n"), showDate = false))
        compose.onNodeWithText("제목").performTextInput("내일 점심")
        compose.onNodeWithText("11/5").assertDoesNotExist()
        compose.onNodeWithText("추가").performClick()
        assertEquals("내일 점심", saved.single().first)
        assertEquals(null, saved.single().second)
    }

    @Test
    fun aPropertyOutsideTheFormIsNotReadFromTheSentence() {
        show(form(FormField("k")))
        compose.onNodeWithText("제목").performTextInput("점심 12000")
        compose.onNodeWithText("금액 12000").assertDoesNotExist()
        compose.onNodeWithText("추가").performClick()
        assertEquals("점심 12000", saved.single().first)
    }

    @Test
    fun aFormPropertyWithoutADefaultIsStillReadFromTheSentence() {
        show(form(FormField("n"), FormField("k", JsonPrimitive("o1"))))
        compose.onNodeWithText("제목").performTextInput("점심 12000")
        compose.onNodeWithText("금액 12000").assertIsSelected()
        compose.onNodeWithText("추가").performClick()
        assertEquals("점심", saved.single().first)
        assertEquals(mapOf<String, JsonElement>("n" to JsonPrimitive(12000.0), "k" to JsonPrimitive("o1")), saved.single().third)
    }

    @Test
    fun aPropertyWithADefaultIsLeftToTheEditorSoItsWordStaysInTheTitle() {
        show(form(FormField("n", JsonPrimitive(5000.0))))
        compose.onNodeWithText("제목").performTextInput("점심 12000")
        compose.onNodeWithText("금액 12000").assertDoesNotExist()
        compose.onNodeWithText("추가").performClick()
        assertEquals("점심 12000", saved.single().first)
        assertEquals(mapOf<String, JsonElement>("n" to JsonPrimitive(5000.0)), saved.single().third)
    }

    @Test
    fun saveDefaultsGivesBackTheFormPropertiesCurrentValuesOnly() {
        show(form(FormField("k", JsonPrimitive("o1")), FormField("n")))
        compose.onNodeWithText("교통").performClick() // 기본값을 바꾼다
        compose.onNodeWithText("이 값을 기본값으로").performClick()
        assertEquals(listOf(mapOf<String, JsonElement>("k" to JsonPrimitive("o2"))), defaultsSaved)
        compose.onNodeWithText("기본값으로 저장했습니다").assertExists()
    }

    @Test
    fun aFailedDefaultsSaveIsSaidSo() {
        defaultsResult = false
        show(form(FormField("n")))
        compose.onNodeWithText("이 값을 기본값으로").performClick()
        compose.onNodeWithText("기본값을 저장하지 못했습니다").assertExists()
    }

    @Test
    fun noDefaultsButtonWithoutAWidgetOrWithoutFormProperties() {
        show(form(FormField("n")), canSaveDefaults = false)
        compose.onNodeWithText("이 값을 기본값으로").assertDoesNotExist()
    }

    @Test
    fun noDefaultsButtonWhenTheFormHasNoProperties() {
        show(form(showDate = false))
        compose.onNodeWithText("이 값을 기본값으로").assertDoesNotExist()
    }

    @Test
    fun sheetPlanSplitsTheEditorsStartValuesAndReadableColumns() {
        val prefill = ViewAddPrefill(listOf(kind), mapOf("k" to JsonPrimitive("o2")))
        val input = form(FormField("k", JsonPrimitive("o1")), FormField("n", JsonPrimitive(1.0)), FormField("r"))
        val plan = sheetPlan(columns, hasDate = true, input = input, prefill = prefill)
        assertEquals(listOf("n", "r"), plan.fields.map { it.id })
        assertEquals(mapOf<String, JsonElement>("k" to JsonPrimitive("o2"), "n" to JsonPrimitive(1.0)), plan.start)
        assertEquals(listOf("r", "d"), plan.readable.map { it.id }) // 시작 값이 있는 속성은 해석하지 않는다
        assertEquals(true, plan.showDate)
    }

    @Test
    fun sheetPlanWithoutAFormReadsEverythingExceptPrefilledAndKeepsTheDate() {
        val plan = sheetPlan(columns, hasDate = true, input = null, prefill = ViewAddPrefill(listOf(kind), mapOf("k" to JsonPrimitive("o1"))))
        assertEquals(emptyList<String>(), plan.fields.map { it.id })
        assertEquals(mapOf<String, JsonElement>("k" to JsonPrimitive("o1")), plan.start)
        assertEquals(listOf("t", "n", "r", "d"), plan.readable.map { it.id })
        assertEquals(true, plan.showDate)
        val noDate = sheetPlan(columns, hasDate = false, input = null, prefill = ViewAddPrefill())
        assertEquals(false, noDate.showDate)
        assertEquals(listOf("t", "n", "k", "r"), noDate.readable.map { it.id }) // 날짜를 안 쓰면 문장에서 날짜도 읽지 않는다
    }
}
