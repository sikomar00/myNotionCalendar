package com.ysdoing.notioncal.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test
import java.time.LocalDate
import java.time.ZonedDateTime

/** MV-07: 끌어 옮기면 종일·시간·기간 일정 모두 날짜만 바뀌고 시각·길이는 그대로다. */
class ShiftDaysTest {
    private fun epoch(text: String) = ZonedDateTime.parse(text).toInstant().toEpochMilli()

    @Test
    fun allDayMovesByDays() {
        assertEquals(DateValue("2026-10-17"), DateValue("2026-10-14").shiftedDays(3))
        assertEquals(DateValue("2026-10-12"), DateValue("2026-10-14").shiftedDays(-2))
    }

    @Test
    fun rangeKeepsLength() {
        assertEquals(DateValue("2026-11-01", "2026-11-03"), DateValue("2026-10-30", "2026-11-01").shiftedDays(2))
    }

    @Test
    fun timedKeepsClockTimeAndLength() {
        val moved = DateValue(
            "2026-10-14",
            startTime = epoch("2026-10-14T15:00+09:00[Asia/Seoul]"),
            endTime = epoch("2026-10-14T16:30+09:00[Asia/Seoul]"),
        ).shiftedDays(1)
        assertEquals("2026-10-15", moved.startDate)
        assertEquals(epoch("2026-10-15T15:00+09:00[Asia/Seoul]"), moved.startTime)
        assertEquals(epoch("2026-10-15T16:30+09:00[Asia/Seoul]"), moved.endTime)
    }

    @Test
    fun crossesMonthAndYear() {
        assertEquals(DateValue("2027-01-01"), DateValue("2026-12-31").shiftedDays(1))
        assertEquals(DateValue("2026-02-28"), DateValue("2026-03-01").shiftedDays(-1))
    }

    @Test
    fun zeroIsNoOp() {
        val value = DateValue("2026-10-14")
        assertSame(value, value.shiftedDays(0))
    }

    @Test
    fun calendarEventRoundTrip() {
        val event = CalendarEvent(
            pageId = "p", dataSourceId = "ds", title = "t", startDate = LocalDate.of(2026, 10, 14), endDate = LocalDate.of(2026, 10, 16),
            startTime = null, endTime = null, isAllDay = true, done = false, colorHex = "#000000", sourceColorHex = "#000000", url = "",
        )
        assertEquals(DateValue("2026-10-14", "2026-10-16"), event.dateValue())
    }
}
