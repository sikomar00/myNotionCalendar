package com.ysdoing.notioncal.core.data.calendar

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/** MV-06 보기 종류. 마지막 보기를 설정에 기억한다. */
enum class CalendarView(val days: Int) {
    MONTH(0),
    WEEK(7),
    THREE_DAY(3),
    AGENDA(0),
}

/**
 * MV-06 '보기를 바꿔도 같은 날짜 유지' 규칙. 모든 보기는 기준 날짜(focus) 하나를 공유한다.
 * - 화면에 보이는 기간(달·주·3일)에 오늘이 있으면 오늘, 없으면 그 기간의 첫날을 기준 날짜로 삼는다.
 * - 보기를 바꾸면 새 보기는 기준 날짜가 들어 있는 기간을 연다.
 */
object CalendarFocus {
    /** 기간 [start]부터 [days]일(달이면 그 달)을 보고 있을 때의 기준 날짜. */
    fun focusFor(start: LocalDate, endInclusive: LocalDate, today: LocalDate): LocalDate =
        if (today in start..endInclusive) today else start

    fun focusForMonth(month: YearMonth, today: LocalDate): LocalDate =
        focusFor(month.atDay(1), month.atEndOfMonth(), today)

    /** 주 보기는 주 시작 요일([weekStart])부터, 3일 보기는 기준 날짜부터 연다. */
    fun periodStart(view: CalendarView, focus: LocalDate, weekStart: DayOfWeek): LocalDate = when (view) {
        CalendarView.WEEK -> focus.with(TemporalAdjusters.previousOrSame(weekStart))
        else -> focus
    }
}
