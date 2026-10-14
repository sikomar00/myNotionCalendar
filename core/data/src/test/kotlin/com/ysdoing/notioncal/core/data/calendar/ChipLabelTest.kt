package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

/** 월 그리드 칩의 시간 표시. 기기 시간대와 무관하게 Asia/Seoul 기준 시각을 쓴다. */
class ChipLabelTest {
    private val originalZone = TimeZone.getDefault()

    @After
    fun restore() = TimeZone.setDefault(originalZone)

    private fun event(start: String?, allDay: Boolean = start == null) = CalendarEvent(
        pageId = "p", dataSourceId = "ds", title = "회의", startDate = LocalDate.of(2026, 10, 13), endDate = null,
        startTime = start?.let(Instant::parse), endTime = null, isAllDay = allDay, done = false,
        colorHex = "#000000", sourceColorHex = "#000000", url = "",
    )

    @Test
    fun timedEventShowsSeoulStartTime() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("8:30 회의", chipLabel(event("2026-10-12T23:30:00Z"), continuesBefore = false))
        assertEquals("15:05 회의", chipLabel(event("2026-10-13T06:05:00Z"), continuesBefore = false))
    }

    @Test
    fun allDayEventShowsTitleOnly() {
        assertEquals("회의", chipLabel(event(null), continuesBefore = false))
    }

    @Test
    fun continuedBarOmitsTime() {
        assertEquals("회의", chipLabel(event("2026-10-12T23:30:00Z"), continuesBefore = true))
    }

    /** A11Y-03: 완료는 체크 표시를 앞에 붙인다(색·흐림만으로 구분하지 않음). */
    @Test
    fun doneGetsCheckMark() {
        assertEquals("✓8:30 회의", chipLabel(event("2026-10-12T23:30:00Z").copy(done = true), continuesBefore = false))
        assertEquals("✓회의", chipLabel(event(null).copy(done = true), continuesBefore = true))
    }
}
