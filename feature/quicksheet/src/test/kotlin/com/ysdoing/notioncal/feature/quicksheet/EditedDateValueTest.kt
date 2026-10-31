package com.ysdoing.notioncal.feature.quicksheet

import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime

/** WI-05: 편집 폼에서 날짜·시간을 바꿔 저장할 때 기간 일정의 길이를 지킨다. */
class EditedDateValueTest {
    private fun seoul(text: String): Instant = LocalDateTime.parse(text).atZone(APP_ZONE).toInstant()
    private fun ms(text: String): Long = seoul(text).toEpochMilli()

    private fun timed(start: String, end: String) = CalendarEvent(
        pageId = "p1", dataSourceId = "ds", title = "출장",
        startDate = seoul(start).atZone(APP_ZONE).toLocalDate(),
        endDate = seoul(end).atZone(APP_ZONE).toLocalDate().takeIf { it.isAfter(seoul(start).atZone(APP_ZONE).toLocalDate()) },
        startTime = seoul(start), endTime = seoul(end), isAllDay = false, done = false,
        colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    private fun allDay(start: LocalDate, end: LocalDate?) = CalendarEvent(
        pageId = "p1", dataSourceId = "ds", title = "휴가", startDate = start, endDate = end,
        startTime = null, endTime = null, isAllDay = true, done = false,
        colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    @Test
    fun overnightTimedEventKeepsEndOnNextDay() {
        // 22:00~다음 날 02:00 일정을 다른 날로 옮겨도 4시간짜리 밤샘 일정으로 남는다(1시간으로 줄지 않는다).
        val event = timed("2026-10-13T22:00", "2026-10-14T02:00")
        val v = editedDateValue(event, LocalDate.of(2026, 10, 20), TimeRange(LocalTime.of(22, 0), LocalTime.of(2, 0)))
        assertEquals(ms("2026-10-20T22:00"), v.startTime)
        assertEquals(ms("2026-10-21T02:00"), v.endTime)
        assertEquals("2026-10-21", v.endDate)
    }

    @Test
    fun multiDayTimedEventKeepsDaySpan() {
        val event = timed("2026-10-13T09:00", "2026-10-15T18:00")
        val v = editedDateValue(event, LocalDate.of(2026, 10, 20), TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)))
        assertEquals(ms("2026-10-20T09:00"), v.startTime)
        assertEquals(ms("2026-10-22T18:00"), v.endTime)
        assertEquals("2026-10-22", v.endDate)
    }

    @Test
    fun multiDayAllDayEventKeepsDaySpan() {
        val event = allDay(LocalDate.of(2026, 10, 13), LocalDate.of(2026, 10, 15))
        val v = editedDateValue(event, LocalDate.of(2026, 10, 20), null)
        assertEquals("2026-10-20", v.startDate)
        assertEquals("2026-10-22", v.endDate)
        assertNull(v.startTime)
    }

    @Test
    fun singleDayTimedEventStaysSingleDay() {
        val event = timed("2026-10-13T15:00", "2026-10-13T16:00")
        val v = editedDateValue(event, LocalDate.of(2026, 10, 20), TimeRange(LocalTime.of(15, 0), LocalTime.of(16, 30)))
        assertEquals(ms("2026-10-20T15:00"), v.startTime)
        assertEquals(ms("2026-10-20T16:30"), v.endTime)
        assertNull(v.endDate)
    }

    @Test
    fun timedValueAcrossDaysCarriesLastDay() {
        // 로컬 행(Room)도 동기화 결과(DateCodec.displayDates)처럼 마지막 날을 갖게 해, 저장 직후 달력에 기간으로 보인다.
        val v = dateValueOf(LocalDate.of(2026, 10, 13), TimeRange(LocalTime.of(9, 0), LocalTime.of(18, 0)), LocalDate.of(2026, 10, 15))
        assertEquals("2026-10-15", v.endDate)
        assertEquals(ms("2026-10-15T18:00"), v.endTime)
    }
}
