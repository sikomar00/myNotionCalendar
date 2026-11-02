package com.ysdoing.notioncal.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** 월 그리드 주차 열이 쓰는 규칙: 주 시작 요일을 따르고 1월 1일이 든 주가 1주차. */
class WeekNumberTest {
    @Test
    fun googleCalendarWeeksForNovember2026() {
        // 구글 캘린더 참고 화면(2026-11-01 일요일 시작 주 = 45주, 이후 한 주씩 늘어난다).
        val first = LocalDate.of(2026, 11, 1)
        assertEquals(listOf(45, 46, 47, 48, 49, 50), (0 until 6).map { weekNumber(first.plusWeeks(it.toLong()), DayOfWeek.SUNDAY) })
    }

    @Test
    fun weekContainingJanuaryFirstIsWeekOne() {
        assertEquals(1, weekNumber(LocalDate.of(2025, 12, 28), DayOfWeek.SUNDAY))
        // 연말 경계 양쪽: 12/20 주=52, 1월 1일(2027, 금)이 든 12/27 주=1, 그 다음 주=2.
        assertEquals(52, weekNumber(LocalDate.of(2026, 12, 20), DayOfWeek.SUNDAY))
        assertEquals(1, weekNumber(LocalDate.of(2026, 12, 27), DayOfWeek.SUNDAY))
        assertEquals(2, weekNumber(LocalDate.of(2027, 1, 3), DayOfWeek.SUNDAY))
    }

    @Test
    fun mondayStartCountsFromItsOwnWeeks() {
        // 월요일 시작이면 2026-10-26(월) 주가 2026-11-01(일)을 포함해 44주다(일요일 시작 45주와 다르다).
        assertEquals(44, weekNumber(LocalDate.of(2026, 10, 26), DayOfWeek.MONDAY))
    }
}
