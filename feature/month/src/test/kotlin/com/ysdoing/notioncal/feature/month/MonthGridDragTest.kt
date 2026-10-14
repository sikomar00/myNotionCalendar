package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** MV-07: 일정을 길게 눌러 다른 칸에 놓으면 옮길 날 수가 전달되고, 짧게 누르면 날짜 시트가 열린다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class MonthGridDragTest {
    @get:Rule
    val compose = createComposeRule()

    private fun event(id: String, title: String, start: String, end: String? = null) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = title, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
        startTime = null, endTime = null, isAllDay = true, done = false, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    private val study = event("1", "영어 스터디", "2026-10-13")
    private val trip = event("2", "출장", "2026-10-20", "2026-10-22")
    private val moves = mutableListOf<Pair<String, Long>>()
    private val clicks = mutableListOf<LocalDate>()

    private fun show() {
        compose.setContent {
            MaterialTheme {
                MonthGrid(
                    MonthGridState(YearMonth.of(2026, 10), DayOfWeek.SUNDAY, LocalDate.of(2026, 10, 13), listOf(study, trip)),
                    onDayClick = { clicks += it },
                    onMove = { e, days -> moves += e.pageId to days },
                )
            }
        }
    }

    private fun centerOfText(text: String): Offset = compose.onNodeWithText(text).fetchSemanticsNode().boundsInRoot.center

    private fun centerOfDay(month: Int, day: Int): Offset =
        compose.onNodeWithContentDescription("${month}월 ${day}일", substring = true).fetchSemanticsNode().boundsInRoot.center

    private fun dragFrom(from: Offset, to: Offset) {
        compose.onRoot().performTouchInput {
            down(from)
            advanceEventTime(viewConfiguration.longPressTimeoutMillis + 100)
            moveTo(Offset((from.x + to.x) / 2, (from.y + to.y) / 2))
            moveTo(to)
            up()
        }
    }

    @Test
    fun dragMovesEventByDays() {
        show()
        // 10/13(화)의 일정을 10/16(금) 칸으로: +3일
        dragFrom(centerOfText("영어 스터디"), centerOfDay(10, 16))
        assertEquals(listOf("1" to 3L), moves)
        assertEquals(emptyList<LocalDate>(), clicks) // 끌기는 날짜 시트를 열지 않는다
    }

    @Test
    fun dragAcrossWeeksAndBackwards() {
        show()
        // 다음 주 10/20 → 이번 주 10/14: -6일
        dragFrom(centerOfText("출장"), centerOfDay(10, 14))
        assertEquals(listOf("2" to -6L), moves)
    }

    @Test
    fun droppingOnSameDayDoesNothing() {
        show()
        val from = centerOfText("영어 스터디")
        dragFrom(from, from + Offset(4f, 0f))
        assertEquals(emptyList<Pair<String, Long>>(), moves)
    }

    @Test
    fun tapOpensDaySheet() {
        show()
        compose.onRoot().performTouchInput { click(centerOfDay(10, 15)) }
        assertEquals(listOf(LocalDate.of(2026, 10, 15)), clicks)
        // TalkBack 동작(semantics onClick)도 같은 날짜를 연다.
        compose.onNodeWithContentDescription("10월 16일", substring = true).performClick()
        assertEquals(LocalDate.of(2026, 10, 16), clicks.last())
    }

    @Test
    fun hitTestFindsChipByLaneAndColumn() {
        val layout = WeekLayout.layout(LocalDate.of(2026, 10, 18), listOf(trip), 3)
        val hit = ChipHitTest(layout, cellWidthPx = 100f, labelHeightPx = 40f, laneHeightPx = 30f)
        assertEquals(trip, hit.eventAt(Offset(250f, 50f))) // 10/20(화) = 2번째 칸, 첫 줄
        assertEquals(trip, hit.eventAt(Offset(450f, 50f))) // 10/22까지 이어진다
        assertNull(hit.eventAt(Offset(550f, 50f))) // 10/23은 빈칸
        assertNull(hit.eventAt(Offset(250f, 20f))) // 날짜 숫자 줄
        assertNull(hit.eventAt(Offset(250f, 80f))) // 두 번째 줄은 비었다
    }
}
