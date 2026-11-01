package com.ysdoing.notioncal.core.sync

import android.provider.CalendarContract
import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * EX-08 시험: 노션 일정 → 폰 캘린더(로컬 계정) 한 줄 값. 종일은 UTC 자정·끝은 다음 날(기간 일정은 끝 다음 날),
 * 시간 일정은 epoch ms·Asia/Seoul. 읽기 전용 미러라 제목·시각만 쓴다. 끄면 이 앱의 로컬 계정 캘린더만 지운다.
 */
class PhoneCalendarMirrorTest {
    private fun event(start: LocalDate, end: LocalDate? = null, from: Instant? = null, to: Instant? = null) = CalendarEvent(
        pageId = "p1", dataSourceId = "ds", title = "회의", startDate = start, endDate = end, startTime = from, endTime = to,
        isAllDay = from == null, done = false, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    @Test
    fun allDayAndPeriodUseUtcMidnights() {
        val one = MirrorRows.values(event(LocalDate.of(2026, 10, 27)), calendarId = 7)
        assertEquals(1, one[CalendarContract.Events.ALL_DAY])
        assertEquals(Instant.parse("2026-10-27T00:00:00Z").toEpochMilli(), one[CalendarContract.Events.DTSTART])
        assertEquals(Instant.parse("2026-10-28T00:00:00Z").toEpochMilli(), one[CalendarContract.Events.DTEND])
        assertEquals("UTC", one[CalendarContract.Events.EVENT_TIMEZONE])
        assertEquals(7L, one[CalendarContract.Events.CALENDAR_ID])
        assertEquals("회의", one[CalendarContract.Events.TITLE])

        val trip = MirrorRows.values(event(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2)), calendarId = 7)
        assertEquals(Instant.parse("2026-11-03T00:00:00Z").toEpochMilli(), trip[CalendarContract.Events.DTEND])
    }

    @Test
    fun timedUsesInstantsAndSeoul() {
        val from = Instant.parse("2026-10-27T11:30:00Z")
        val row = MirrorRows.values(event(LocalDate.of(2026, 10, 27), from = from, to = null), calendarId = 7)
        assertEquals(0, row[CalendarContract.Events.ALL_DAY])
        assertEquals(from.toEpochMilli(), row[CalendarContract.Events.DTSTART])
        assertEquals(from.plusSeconds(3600).toEpochMilli(), row[CalendarContract.Events.DTEND]) // 끝이 없으면 1시간
        assertEquals("Asia/Seoul", row[CalendarContract.Events.EVENT_TIMEZONE])
    }

    @Test
    fun onlyOwnLocalCalendarIsTouched() {
        assertEquals(CalendarContract.ACCOUNT_TYPE_LOCAL, MirrorRows.ACCOUNT_TYPE)
        assertTrue(MirrorRows.OWN_SELECTION.contains(CalendarContract.Calendars.ACCOUNT_TYPE))
        assertEquals(listOf(MirrorRows.ACCOUNT_NAME, CalendarContract.ACCOUNT_TYPE_LOCAL), MirrorRows.OWN_ARGS.toList())
    }
}
