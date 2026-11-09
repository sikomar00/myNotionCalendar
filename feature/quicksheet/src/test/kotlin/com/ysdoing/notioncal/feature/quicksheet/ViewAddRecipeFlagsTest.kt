package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.view.FormField
import com.ysdoing.notioncal.core.data.view.InputForm
import com.ysdoing.notioncal.core.data.view.ViewColumn
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * Phase 11h 레시피가 켜는 입력 양식 플래그: 날짜를 오늘로 시작(`dateToday`), 제목이 비면 'M월 d일'(`titleDate`), 하루 한 줄(`oncePerDay`).
 * 직접 고른 칩과 글에서 읽은 날짜가 언제나 기본값을 이긴다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewAddRecipeFlagsTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 4)
    private val columns = listOf(ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date"), ViewColumn("c", "운동", "checkbox"))
    private val saved = mutableListOf<Triple<String, LocalDate?, Map<String, JsonElement>>>()

    private fun show(input: InputForm?, taken: Set<LocalDate> = emptySet(), presetOnly: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                ViewAddContent(
                    viewName = "습관", hasDate = true, today = today, columns = columns, input = input, takenDates = taken, presetOnly = presetOnly,
                    onSave = { title, day, props -> saved += Triple(title, day, props) }, onCancel = {},
                    onSavePreset = { _, _ -> true },
                )
            }
        }
    }

    private fun type(text: String) = compose.onNodeWithText(if (text.isEmpty()) "제목" else "제목", substring = true).performTextInput(text)

    // --- dateToday

    @Test
    fun dateTodayStartsOnTodayAndSavesIt() {
        show(InputForm(dateToday = true))
        compose.onNodeWithText("오늘").assertIsSelected()
        compose.onNodeWithText("날짜 없음").assertIsNotSelected()
        type("점심")
        compose.onNodeWithText("추가").performClick()
        assertEquals(today, saved.single().second)
    }

    @Test
    fun withoutTheFlagTheDateStaysEmptyAsBefore() {
        show(InputForm(fields = listOf(FormField("c"))))
        compose.onNodeWithText("날짜 없음").assertIsSelected()
        type("점심")
        compose.onNodeWithText("추가").performClick()
        assertEquals(null, saved.single().second)
    }

    @Test
    fun aDateReadFromTheSentenceBeatsTheTodayDefault() {
        show(InputForm(dateToday = true))
        type("내일 점심")
        compose.onNodeWithText("11/5").assertIsSelected()
        compose.onNodeWithText("오늘").assertIsNotSelected()
        compose.onNodeWithText("추가").performClick()
        assertEquals(today.plusDays(1), saved.single().second)
        assertEquals("점심", saved.single().first)
    }

    @Test
    fun theNoDateChipBeatsTheTodayDefault() {
        show(InputForm(dateToday = true))
        type("점심")
        compose.onNodeWithText("날짜 없음").performClick()
        compose.onNodeWithText("날짜 없음").assertIsSelected()
        compose.onNodeWithText("오늘").assertIsNotSelected()
        compose.onNodeWithText("추가").performClick()
        assertEquals(null, saved.single().second)
    }

    @Test
    fun anExplicitChipBeatsTheTodayDefault() {
        show(InputForm(dateToday = true))
        type("점심")
        compose.onNodeWithText("내일").performClick()
        compose.onNodeWithText("추가").performClick()
        assertEquals(today.plusDays(1), saved.single().second)
    }

    @Test
    fun turningTheDateChipsOffMeansNoDateEvenWithTheFlag() {
        show(InputForm(showDate = false, dateToday = true))
        type("점심")
        compose.onNodeWithText("추가").performClick()
        assertEquals(null, saved.single().second) // 날짜 칩을 숨긴 양식은 날짜를 쓰지 않는다
    }

    // --- titleDate

    @Test
    fun titleDateLetsTheTitleBeBlankAndUsesTodaysDate() {
        show(InputForm(titleDate = true))
        compose.onNodeWithText("제목 (비우면 11월 4일)").assertExists()
        compose.onNodeWithText("추가").assertIsEnabled()
        compose.onNodeWithText("추가").performClick()
        assertEquals("11월 4일", saved.single().first)
    }

    @Test
    fun theDateTitleFollowsTheChosenDayNotAlwaysToday() {
        show(InputForm(titleDate = true))
        compose.onNodeWithText("내일").performClick()
        compose.onNodeWithText("제목 (비우면 11월 5일)").assertExists() // 칸 이름도 고른 날을 따른다
        compose.onNodeWithText("추가").performClick()
        assertEquals("11월 5일", saved.single().first)
        assertEquals(today.plusDays(1), saved.single().second) // 제목과 날짜가 어긋나지 않는다
    }

    @Test
    fun aTypedTitleBeatsTheDateTitle() {
        show(InputForm(titleDate = true))
        type("아침 운동")
        compose.onNodeWithText("추가").performClick()
        assertEquals("아침 운동", saved.single().first)
    }

    @Test
    fun withoutTitleDateABlankTitleCannotBeAdded() {
        show(InputForm(dateToday = true))
        compose.onNodeWithText("추가").assertIsNotEnabled()
    }

    @Test
    fun makingAPresetStillNeedsAName() {
        show(InputForm(titleDate = true), presetOnly = true)
        compose.onNodeWithText("프리셋으로 저장").assertIsNotEnabled()
    }

    // --- oncePerDay

    @Test
    fun oncePerDayBlocksAddingWhenTheChosenDayIsTaken() {
        show(InputForm(dateToday = true, oncePerDay = true), taken = setOf(today))
        type("점심")
        compose.onNodeWithText("이 날짜의 항목이 이미 있습니다", substring = true).assertExists()
        compose.onNodeWithText("추가").assertIsNotEnabled()
        compose.onNodeWithText("내일").performClick() // 다른 날은 만들 수 있다
        compose.onNodeWithText("이 날짜의 항목이 이미 있습니다", substring = true).assertDoesNotExist()
        compose.onNodeWithText("추가").assertIsEnabled()
        compose.onNodeWithText("추가").performClick()
        assertEquals(today.plusDays(1), saved.single().second)
    }

    @Test
    fun oncePerDayAllowsAddingWhenOnlyOtherDaysAreTaken() {
        show(InputForm(dateToday = true, oncePerDay = true), taken = setOf(today.minusDays(1)))
        type("점심")
        compose.onNodeWithText("추가").assertIsEnabled()
        compose.onNodeWithText("추가").performClick()
        assertEquals(today, saved.single().second)
    }

    @Test
    fun withoutTheFlagATakenDayDoesNotBlock() {
        show(InputForm(dateToday = true), taken = setOf(today))
        type("점심")
        compose.onNodeWithText("추가").assertIsEnabled()
    }
}
