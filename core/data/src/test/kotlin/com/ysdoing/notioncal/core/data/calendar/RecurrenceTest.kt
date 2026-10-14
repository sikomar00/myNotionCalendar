package com.ysdoing.notioncal.core.data.calendar

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/** NS-10 반복 펼치기: 매일·매주(여러 요일)·매월(없는 날 건너뜀)·매년(윤년), 간격, 끝 날짜·횟수, 100개 상한. */
class RecurrenceTest {
    private fun d(text: String) = LocalDate.parse(text)
    private fun expand(start: String, rule: RepeatRule) = Recurrence.expand(d(start), rule).map { it.toString() }

    @Test
    fun dailyWithIntervalAndCount() {
        assertEquals(listOf("2026-10-14", "2026-10-16", "2026-10-18"), expand("2026-10-14", RepeatRule(RepeatFreq.DAILY, interval = 2, count = 3)))
    }

    @Test
    fun weeklyMultipleWeekdaysUntil() {
        // 10/14는 수요일. 월·수만, 시작일 전(10/12 월)은 빼고 10/28까지.
        val rule = RepeatRule(RepeatFreq.WEEKLY, weekdays = setOf(DayOfWeek.MONDAY, DayOfWeek.WEDNESDAY), until = d("2026-10-28"))
        assertEquals(listOf("2026-10-14", "2026-10-19", "2026-10-21", "2026-10-26", "2026-10-28"), expand("2026-10-14", rule))
        // 요일을 고르지 않으면 시작일의 요일, 2주마다.
        assertEquals(
            listOf("2026-10-14", "2026-10-28", "2026-11-11"),
            expand("2026-10-14", RepeatRule(RepeatFreq.WEEKLY, interval = 2, count = 3)),
        )
    }

    @Test
    fun monthlySkipsMonthsWithoutTheDay() {
        assertEquals(
            listOf("2026-10-31", "2026-12-31", "2027-01-31", "2027-03-31"),
            expand("2026-10-31", RepeatRule(RepeatFreq.MONTHLY, count = 4)),
        )
    }

    @Test
    fun yearlyLeapDay() {
        assertEquals(listOf("2028-02-29", "2032-02-29"), expand("2028-02-29", RepeatRule(RepeatFreq.YEARLY, count = 2)))
    }

    @Test
    fun capsAtHundred() {
        assertEquals(Recurrence.MAX, Recurrence.expand(d("2026-01-01"), RepeatRule(RepeatFreq.DAILY, count = 500)).size)
        assertEquals(Recurrence.MAX, Recurrence.expand(d("2026-01-01"), RepeatRule(RepeatFreq.DAILY)).size)
    }

    @Test
    fun encodeRoundTrip() {
        val rule = RepeatRule(RepeatFreq.WEEKLY, 2, setOf(DayOfWeek.WEDNESDAY, DayOfWeek.MONDAY), until = d("2026-12-31"))
        assertEquals(rule, RepeatRule.decode(rule.encode()))
        assertEquals(RepeatRule(RepeatFreq.DAILY, count = 5), RepeatRule.decode(RepeatRule(RepeatFreq.DAILY, count = 5).encode()))
        assertNull(RepeatRule.decode("깨진값"))
    }
}
