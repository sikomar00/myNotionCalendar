package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** MV-06 시간표: 시간 일정 배치(겹침 열 나누기·자정 넘김·짧은 일정)와 3일 폭 종일 줄. */
class TimedLayoutTest {
    private val day = LocalDate.of(2026, 10, 14)

    private fun timed(id: String, from: String, to: String?, date: LocalDate = day, endDate: LocalDate? = null) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = id, startDate = date, endDate = endDate,
        startTime = ZonedDateTime.parse("${date}T$from+09:00[Asia/Seoul]").toInstant(),
        endTime = to?.let { ZonedDateTime.parse("${endDate ?: date}T$it+09:00[Asia/Seoul]").toInstant() },
        isAllDay = false, done = false, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    private fun allDay(id: String, start: LocalDate, end: LocalDate? = null) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = id, startDate = start, endDate = end, startTime = null, endTime = null,
        isAllDay = true, done = false, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    private fun List<TimedBlock>.summary() = map { "${it.event.pageId}:${it.startMinute}-${it.endMinute} ${it.column}/${it.columns}" }

    @Test
    fun separateEventsUseFullWidth() {
        val blocks = TimedLayout.layout(day, listOf(timed("a", "09:00", "10:00"), timed("b", "10:00", "11:30")))
        assertEquals(listOf("a:540-600 0/1", "b:600-690 0/1"), blocks.summary())
    }

    @Test
    fun overlapsShareColumns() {
        val blocks = TimedLayout.layout(
            day,
            listOf(timed("a", "09:00", "11:00"), timed("b", "09:30", "10:00"), timed("c", "10:00", "10:30"), timed("d", "12:00", "13:00")),
        )
        // a·b·c는 한 묶음(2열): b가 끝난 자리에 c가 들어간다. d는 따로라 전체 폭.
        assertEquals(listOf("a:540-660 0/2", "b:570-600 1/2", "c:600-630 1/2", "d:720-780 0/1"), blocks.summary())
    }

    @Test
    fun threeWayOverlap() {
        val blocks = TimedLayout.layout(day, listOf(timed("a", "09:00", "12:00"), timed("b", "09:00", "10:00"), timed("c", "09:30", "11:00")))
        assertEquals(listOf("a:540-720 0/3", "b:540-600 1/3", "c:570-660 2/3"), blocks.summary())
    }

    @Test
    fun shortEventsGetMinimumHeightAndNoEndMeansOneHour() {
        val blocks = TimedLayout.layout(day, listOf(timed("short", "09:00", "09:05"), timed("open", "13:00", null)))
        assertEquals(listOf("short:540-570 0/1", "open:780-840 0/1"), blocks.summary())
    }

    @Test
    fun clippedAtMidnight() {
        // 23:30 시작 1시간 일정: 이 날에는 23:30~24:00만 그린다.
        val late = timed("late", "23:30", null)
        assertEquals(listOf("late:1410-1440 0/1"), TimedLayout.layout(day, listOf(late)).summary())
        assertEquals(emptyList<String>(), TimedLayout.layout(day.minusDays(1), listOf(late)).summary())
    }

    @Test
    fun allDayAndMultiDayStayInTopLane() {
        val events = listOf(allDay("종일", day), timed("밤샘", "22:00", "02:00", endDate = day.plusDays(1)))
        assertEquals(emptyList<String>(), TimedLayout.layout(day, events).summary())
    }

    @Test
    fun spanLayoutWorksForThreeDays() {
        val start = LocalDate.of(2026, 10, 13)
        val trip = allDay("출장", LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 14))
        val later = allDay("발표", LocalDate.of(2026, 10, 15))
        val outside = allDay("밖", LocalDate.of(2026, 10, 16))
        val result = WeekLayout.layout(start, listOf(trip, later, outside), maxLanes = 2, days = 3)
        assertEquals(listOf("출장" to (0 to 2), "발표" to (2 to 1)), result.placed.map { it.event.title to (it.startCol to it.span) })
        assertEquals(true, result.placed.first().continuesBefore)
        assertEquals(3, result.hiddenCount.size)
    }
}
