package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.CalendarEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZonedDateTime

/** MV-06 목록 보기: 날짜별 표시, 기준 날짜로 스크롤, 오늘 버튼(이동 요청), 일정·날짜 탭. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class AgendaViewTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 14)

    private fun event(id: String, title: String, date: String, time: String? = null, done: Boolean = false) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = title, startDate = LocalDate.parse(date), endDate = null,
        startTime = time?.let { ZonedDateTime.parse("${date}T$it+09:00[Asia/Seoul]").toInstant() },
        endTime = time?.let { ZonedDateTime.parse("${date}T$it+09:00[Asia/Seoul]").toInstant().plusSeconds(3600) },
        isAllDay = time == null, done = done, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    // 9월 초부터 12월까지 일정을 넉넉히 깔아 스크롤 위치를 확인한다.
    private val events = (0 until 90).map { i ->
        val date = LocalDate.of(2026, 9, 1).plusDays(i.toLong())
        event("e$i", "일정 ${date.monthValue}/${date.dayOfMonth}", date.toString(), time = if (i % 2 == 0) "09:30" else null, done = i % 5 == 0)
    }
    private val requested = mutableListOf<Pair<LocalDate, LocalDate>>()
    private fun source(from: LocalDate, to: LocalDate): StateFlow<List<CalendarEvent>?> {
        requested += from to to
        return MutableStateFlow(events.filter { it.startDate in from..to })
    }

    @Test
    fun opensAtFocusAndTapsWork() {
        val dayClicks = mutableListOf<LocalDate>()
        val eventClicks = mutableListOf<String>()
        compose.setContent {
            MaterialTheme {
                AgendaView(
                    initialFocus = LocalDate.of(2026, 10, 20), jump = null, today = today, events = ::source,
                    onDayClick = { dayClicks += it }, onEventClick = { eventClicks += it.title }, onFocusChange = {},
                )
            }
        }
        compose.onNodeWithText("일정 10/20").assertExists() // 기준 날짜가 보인다
        compose.onNodeWithText("일정 10/20").performClick()
        compose.onNodeWithText("10/20").performClick()
        assertEquals(listOf("일정 10/20"), eventClicks)
        assertEquals(listOf(LocalDate.of(2026, 10, 20)), dayClicks)
        assertEquals(LocalDate.of(2026, 9, 1) to LocalDate.of(2027, 1, 20), requested.first()) // 한 달 전 1일 ~ 석 달 뒤
    }

    @Test
    fun jumpScrollsToDate() {
        var jump by mutableStateOf<CalendarJump?>(null)
        compose.setContent {
            MaterialTheme {
                AgendaView(
                    initialFocus = LocalDate.of(2026, 11, 20), jump = jump, today = today, events = ::source,
                    onDayClick = {}, onEventClick = {}, onFocusChange = {},
                )
            }
        }
        compose.onNodeWithText("일정 11/20").assertExists()
        jump = CalendarJump(LocalDate.of(2026, 9, 10), 1)
        compose.waitForIdle()
        compose.onNodeWithText("일정 9/10").assertExists()
        assertTrue(requested.any { it.first == LocalDate.of(2026, 8, 1) }) // 새 기준으로 범위를 다시 읽는다
    }

    @Test
    fun anchorWithoutEventsIsShownAndNotOverwritten() {
        // 기준 날짜 뒤로 일정이 없어도 그 날짜에 머물고, 처음 자리 잡기로 기준 날짜를 바꾸지 않는다(실기기에서 10월로 튀던 문제).
        val focusChanges = mutableListOf<LocalDate>()
        compose.setContent {
            MaterialTheme {
                AgendaView(
                    initialFocus = LocalDate.of(2027, 1, 10), jump = null, today = today, events = ::source,
                    onDayClick = {}, onEventClick = {}, onFocusChange = { focusChanges += it },
                )
            }
        }
        compose.onNodeWithText("1/10").assertExists()
        compose.onNodeWithText("일정 없음").assertExists()
        assertEquals(emptyList<LocalDate>(), focusChanges)
        // 일정이 적어 끝이 바로 보여도 스스로 범위를 늘리지 않는다(늘리다 목록이 비어 맨 앞으로 튀던 문제).
        assertEquals(1, requested.size)
    }

    @Test
    fun screenshotAgenda() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    AgendaView(
                        initialFocus = today, jump = null, today = today, events = ::source,
                        onDayClick = {}, onEventClick = {}, onFocusChange = {},
                    )
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/agenda.png")
    }
}
