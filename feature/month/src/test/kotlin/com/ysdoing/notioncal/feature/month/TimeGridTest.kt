package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZonedDateTime

/** MV-06 주·3일 시간표: 일정 탭 → 편집, 빈 칸 탭 → 그날 날짜 시트, 스크린샷. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class TimeGridTest {
    @get:Rule
    val compose = createComposeRule()

    private val monday = LocalDate.of(2026, 10, 12)

    private fun timed(id: String, title: String, date: String, from: String, to: String, color: String = "#0B6E99", done: Boolean = false) =
        CalendarEvent(
            pageId = id, dataSourceId = "ds", title = title, startDate = LocalDate.parse(date), endDate = null,
            startTime = ZonedDateTime.parse("${date}T$from+09:00[Asia/Seoul]").toInstant(),
            endTime = ZonedDateTime.parse("${date}T$to+09:00[Asia/Seoul]").toInstant(),
            isAllDay = false, done = done, colorHex = color, sourceColorHex = color, url = "",
        )

    private fun allDay(id: String, title: String, start: String, end: String? = null, color: String = "#DFAB01") = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = title, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
        startTime = null, endTime = null, isAllDay = true, done = false, colorHex = color, sourceColorHex = color, url = "",
    )

    private val events = listOf(
        timed("1", "회의", "2026-10-14", "09:00", "10:30", "#DFAB01"),
        timed("2", "치과", "2026-10-14", "09:30", "10:00", "#E03E3E"),
        timed("3", "점심", "2026-10-13", "12:00", "13:00", "#0F7B6C"),
        timed("4", "운동", "2026-10-15", "19:00", "20:00", done = true),
        allDay("5", "출장", "2026-10-12", "2026-10-13"),
        allDay("6", "보고서 마감", "2026-10-16"),
    )

    private val eventClicks = mutableListOf<String>()
    private val dayClicks = mutableListOf<LocalDate>()

    private fun show(days: Int = 7, start: LocalDate = monday) {
        compose.setContent {
            MaterialTheme {
                Surface {
                    TimeGrid(
                        start = start, days = days, today = LocalDate.of(2026, 10, 14), nowMinute = 11 * 60 + 20,
                        events = events, hourHeight = 48.dp,
                        onDayClick = { dayClicks += it }, onEventClick = { eventClicks += it.pageId },
                    )
                }
            }
        }
    }

    @Test
    fun tapEventOpensEditor() {
        show()
        compose.onNodeWithText("회의").performClick()
        compose.onNodeWithText("출장").performClick()
        assertEquals(listOf("1", "5"), eventClicks)
    }

    @Test
    fun tapEmptySlotOpensThatDay() {
        show()
        // 10/16 칸의 빈 곳(새벽 시간대)을 누른다.
        compose.onNodeWithContentDescription("10월 16일 시간표").performTouchInput { click(center.copy(y = 40f)) }
        compose.onNodeWithText("17").performClick() // 머리줄 날짜
        assertEquals(listOf(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17)), dayClicks)
    }

    @Test
    fun screenshotWeek() {
        show()
        compose.onRoot().captureRoboImage("src/test/screenshots/timegrid_week.png")
    }

    @Test
    fun screenshotThreeDays() {
        show(days = 3, start = LocalDate.of(2026, 10, 13))
        compose.onRoot().captureRoboImage("src/test/screenshots/timegrid_3day.png")
    }
}
