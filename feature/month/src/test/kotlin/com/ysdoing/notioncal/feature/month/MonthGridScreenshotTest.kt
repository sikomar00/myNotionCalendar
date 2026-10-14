package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth

/** PRD §12 화면 스크린샷: 월 화면 5·6주, 다크, 글꼴 최대. S25 화면(411×891dp) 기준. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class MonthGridScreenshotTest {
    @get:Rule
    val compose = createComposeRule()

    private fun event(
        id: String,
        title: String,
        start: String,
        end: String? = null,
        color: String = "#0B6E99",
        done: Boolean = false,
        time: String? = null,
    ) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = title, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
        startTime = time?.let(Instant::parse), endTime = null, isAllDay = time == null, done = done, colorHex = color,
        sourceColorHex = color, url = "",
    )

    // §8.4 두 DB를 본뜬 표본: Ctgr 색(Event 노랑·Learn 파랑·Habit 빨강), 상태 Done 취소선, 기간 일정, 넘침(+N).
    private val events = listOf(
        event("1", "영어 스터디", "2026-10-13", color = "#0B6E99"),
        event("2", "회의", "2026-10-13", time = "2026-10-12T23:30:00Z", color = "#DFAB01"),
        event("3", "바코프로젝트 기획", "2026-10-31", color = "#DFAB01", done = true),
        event("4", "데이터셋 선정", "2026-10-30", "2026-11-02", color = "#6940A5", done = true),
        event("5", "문서정리 및 발표", "2026-10-15", color = "#0F7B6C"),
        event("6", "운동", "2026-10-14", color = "#E03E3E"),
        event("7", "독서", "2026-10-14", color = "#E03E3E"),
        event("8", "장보기", "2026-10-14", color = "#DFAB01"),
        event("9", "치과", "2026-10-14", color = "#DFAB01"),
        event("10", "보고서 초안", "2026-10-14", color = "#0B6E99"),
        event("11", "출장", "2026-10-20", "2026-10-22", color = "#D9730D"),
        event("12", "여름 휴가", "2026-08-03", "2026-08-07", color = "#0F7B6C"),
        event("13", "8월 마감", "2026-08-31", color = "#E03E3E"),
    )

    private fun capture(
        month: YearMonth,
        name: String,
        weekStart: DayOfWeek = DayOfWeek.SUNDAY,
        zoom: Float = 1f,
        holidays: Map<LocalDate, String> = emptyMap(),
    ) {
        compose.setContent {
            MaterialTheme(colorScheme = if (isSystemInDarkTheme()) darkColorScheme() else lightColorScheme()) {
                Surface {
                    TextZoom(zoom) {
                        MonthGrid(MonthGridState(month, weekStart, LocalDate.of(2026, 10, 13), events, holidays), onDayClick = {})
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    @Test
    fun october2026FiveWeeks() = capture(YearMonth.of(2026, 10), "month_2026_10")

    @Test
    fun august2026SixWeeks() = capture(YearMonth.of(2026, 8), "month_2026_08")

    @Test
    fun mondayStart() = capture(YearMonth.of(2026, 10), "month_2026_10_monday", DayOfWeek.MONDAY)

    @Test
    @Config(qualifiers = "+night")
    fun dark() = capture(YearMonth.of(2026, 10), "month_2026_10_dark")

    @Test
    @Config(fontScale = 2.0f)
    fun maxFont() = capture(YearMonth.of(2026, 10), "month_2026_10_font_max")

    // MV-08: 확대하면 줄·글자가 커지고 넘친 일정은 +N, 축소하면 더 많은 줄이 보인다.
    @Test
    fun zoomIn() = capture(YearMonth.of(2026, 10), "month_2026_10_zoom_150", zoom = 1.5f)

    @Test
    fun zoomOut() = capture(YearMonth.of(2026, 10), "month_2026_10_zoom_70", zoom = 0.7f)
    // EX-02: 공휴일 숫자는 빨강, 공휴일이 있는 주에만 이름 줄이 생긴다.
    private val october2026Holidays = mapOf(
        LocalDate.of(2026, 10, 3) to "개천절",
        LocalDate.of(2026, 10, 5) to "쉬는 날 개천절",
        LocalDate.of(2026, 10, 9) to "한글날",
    )

    @Test
    fun holidays() = capture(YearMonth.of(2026, 10), "month_2026_10_holidays", holidays = october2026Holidays)

    @Test
    fun holidayIsReadInCellDescription() {
        compose.setContent {
            MaterialTheme {
                MonthGrid(MonthGridState(YearMonth.of(2026, 10), DayOfWeek.SUNDAY, LocalDate.of(2026, 10, 13), emptyList(), october2026Holidays), onDayClick = {})
            }
        }
        compose.onNodeWithContentDescription("10월 9일, 한글날, 일정 0개").assertExists()
        compose.onNodeWithText("쉬는 날 개천절").assertExists()
    }
}
