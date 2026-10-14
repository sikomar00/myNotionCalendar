package com.ysdoing.notioncal.core.data.calendar

import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** MV-06: 보기를 바꿔도 같은 날짜를 유지하는 규칙과 목록 보기 묶기. 오늘은 2026-10-14(수). */
class CalendarViewTest {
    private val today = LocalDate.of(2026, 10, 14)

    @Test
    fun monthFocusIsTodayOrFirstDay() {
        assertEquals(today, CalendarFocus.focusForMonth(YearMonth.of(2026, 10), today))
        assertEquals(LocalDate.of(2026, 12, 1), CalendarFocus.focusForMonth(YearMonth.of(2026, 12), today))
    }

    @Test
    fun weekOpensAtWeekStartContainingFocus() {
        assertEquals(LocalDate.of(2026, 10, 11), CalendarFocus.periodStart(CalendarView.WEEK, today, DayOfWeek.SUNDAY))
        assertEquals(LocalDate.of(2026, 10, 12), CalendarFocus.periodStart(CalendarView.WEEK, today, DayOfWeek.MONDAY))
        assertEquals(today, CalendarFocus.periodStart(CalendarView.THREE_DAY, today, DayOfWeek.SUNDAY))
    }

    @Test
    fun switchingKeepsTheSameDate() {
        // 월(12월) → 주: 12/1이 든 주. 주(12/20~26) → 3일: 12/20부터. 다시 월: 12월.
        val inDecember = CalendarFocus.focusForMonth(YearMonth.of(2026, 12), today)
        val week = CalendarFocus.periodStart(CalendarView.WEEK, inDecember, DayOfWeek.SUNDAY)
        assertEquals(LocalDate.of(2026, 11, 29), week)
        val weekFocus = CalendarFocus.focusFor(LocalDate.of(2026, 12, 20), LocalDate.of(2026, 12, 26), today)
        assertEquals(LocalDate.of(2026, 12, 20), CalendarFocus.periodStart(CalendarView.THREE_DAY, weekFocus, DayOfWeek.SUNDAY))
        assertEquals(YearMonth.of(2026, 12), YearMonth.from(weekFocus))
        // 보던 기간에 오늘이 있으면 오늘을 기준으로 삼는다.
        assertEquals(today, CalendarFocus.focusFor(LocalDate.of(2026, 10, 11), LocalDate.of(2026, 10, 17), today))
    }

    private fun event(id: String, start: String, end: String? = null) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = id, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
        startTime = null, endTime = null, isAllDay = true, done = false, colorHex = "#000000", sourceColorHex = "#000000", url = "",
    )

    @Test
    fun agendaGroupsByDayAndRepeatsMultiDayEvents() {
        val days = Agenda.build(
            listOf(event("a", "2026-10-12"), event("trip", "2026-10-13", "2026-10-15"), event("b", "2026-10-15"), event("out", "2026-12-01")),
            from = LocalDate.of(2026, 10, 1), to = LocalDate.of(2026, 10, 31), today = today,
        )
        assertEquals(listOf("10-12", "10-13", "10-14", "10-15"), days.map { "%02d-%02d".format(it.date.monthValue, it.date.dayOfMonth) })
        assertEquals(listOf("trip" to true), days[2].items.map { it.first.pageId to it.second }) // 오늘: 이어지는 출장
        assertEquals(listOf("trip" to true, "b" to false), days[3].items.map { it.first.pageId to it.second })
    }

    @Test
    fun agendaAlwaysShowsToday() {
        val days = Agenda.build(emptyList(), LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), today)
        assertEquals(listOf(today), days.map { it.date })
        assertEquals(emptyList<Any>(), days.single().items)
    }

    @Test
    fun agendaShowsAnchorWithoutEvents() {
        // 11월에 일정이 없어도 11/1로 옮겨 오면 그 자리가 있다(맨 앞 10월로 튀지 않게).
        val days = Agenda.build(listOf(event("a", "2026-10-03")), LocalDate.of(2026, 10, 1), LocalDate.of(2027, 2, 1), today, anchor = LocalDate.of(2026, 11, 1))
        assertEquals(listOf(LocalDate.of(2026, 10, 3), today, LocalDate.of(2026, 11, 1)), days.map { it.date })
    }
}
