package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** VW-08 새 항목 입력 시트: 제목이 있어야 추가할 수 있고, 날짜 열이 있는 보기만 날짜 칩을 보인다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class ViewAddContentTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 11, 3)
    private val saved = mutableListOf<Pair<String, LocalDate?>>()
    private var cancelled = 0

    private fun show(hasDate: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                ViewAddContent(viewName = "할 일", hasDate = hasDate, today = today, onSave = { title, date, _ -> saved += title to date }, onCancel = { cancelled++ })
            }
        }
    }

    @Test
    fun addIsDisabledUntilThereIsATitle() {
        show()
        compose.onNodeWithText("추가").assertIsNotEnabled()
        compose.onNodeWithText("제목").performTextInput("장보기")
        compose.onNodeWithText("추가").assertIsEnabled()
    }

    @Test
    fun savesWithoutADateByDefault() {
        show()
        compose.onNodeWithText("제목").performTextInput("장보기")
        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf<Pair<String, LocalDate?>>("장보기" to null), saved)
    }

    @Test
    fun todayAndTomorrowChipsSetTheDate() {
        show()
        compose.onNodeWithText("제목").performTextInput("보고서")
        compose.onNodeWithText("오늘").performClick()
        compose.onNodeWithText("추가").performClick()
        compose.onNodeWithText("내일").performClick()
        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf<Pair<String, LocalDate?>>("보고서" to today, "보고서" to today.plusDays(1)), saved)
    }

    @Test
    fun noDateChipsWhenTheViewHasNoDateColumn() {
        show(hasDate = false)
        compose.onNodeWithText("오늘").assertDoesNotExist()
        compose.onNodeWithText("날짜 없음").assertDoesNotExist()
    }

    private fun showFromDay(day: LocalDate) {
        compose.setContent {
            MaterialTheme { ViewAddContent("일정", true, today, { title, date, _ -> saved += title to date }, { cancelled++ }, initialDate = day) }
        }
    }

    @Test
    fun aDayTappedOnTheCalendarStartsTheSheetOnThatDate() {
        showFromDay(LocalDate.of(2026, 11, 20)) // Phase 12a VR-CAL: 캘린더 날짜 칸 → 새 항목 시트
        compose.onNodeWithText("제목").performTextInput("회의")
        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf<Pair<String, LocalDate?>>("회의" to LocalDate.of(2026, 11, 20)), saved)
    }

    @Test
    fun theTappedDayBeatsADateReadFromTheTitleAndTheChipsCanStillChangeIt() {
        showFromDay(LocalDate.of(2026, 11, 20))
        compose.onNodeWithText("제목").performTextInput("내일 회의")
        compose.onNodeWithText("추가").performClick()
        compose.onNodeWithText("오늘").performClick()
        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf<Pair<String, LocalDate?>>("내일 회의" to LocalDate.of(2026, 11, 20), "내일 회의" to today), saved) // 직접 고른 날짜가 글의 '내일'을 이긴다
    }

    @Test
    fun theDatePickerOpensWithTheTappedDaySelected() {
        // 날짜 선택창은 UTC 자정 밀리초를 쓴다. 칩에 보이는 날짜를 다시 열었을 때 이미 골라져 있어야 한다.
        val millis = pickerMillis("2026-11-20")!!
        assertEquals(LocalDate.of(2026, 11, 20), java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneOffset.UTC).toLocalDate())
        assertEquals(null, pickerMillis(null))
    }

    @Test
    fun cancelClosesWithoutSaving() {
        show()
        compose.onNodeWithText("취소").performClick()
        assertEquals(1, cancelled)
        assertEquals(emptyList<Pair<String, LocalDate?>>(), saved)
    }

    private fun showWith(canAdd: Boolean = true, saving: Boolean = false, failed: Boolean = false) {
        compose.setContent {
            MaterialTheme {
                ViewAddContent("할 일", true, today, { title, date, _ -> saved += title to date }, { cancelled++ }, canAdd = canAdd, saving = saving, failed = failed)
            }
        }
    }

    @Test
    fun explainsAndBlocksWhenTheViewCannotCreateItems() {
        showWith(canAdd = false)
        compose.onNodeWithText("이 보기에서는 새 항목을 만들 수 없습니다. 위젯을 새로고침한 뒤 다시 시도하세요").assertExists()
        compose.onNodeWithText("제목").performTextInput("장보기")
        compose.onNodeWithText("추가").assertIsNotEnabled()
    }

    @Test
    fun addIsDisabledWhileSavingSoADoubleTapMakesOneItem() {
        showWith(saving = true)
        compose.onNodeWithText("제목").performTextInput("장보기")
        compose.onNodeWithText("추가").assertIsNotEnabled()
        assertEquals(emptyList<Pair<String, LocalDate?>>(), saved)
    }

    @Test
    fun saysWhenSavingFailed() {
        showWith(failed = true)
        compose.onNodeWithText("추가하지 못했습니다. 보기가 지워졌거나 아직 받지 못했습니다").assertExists()
    }
}
