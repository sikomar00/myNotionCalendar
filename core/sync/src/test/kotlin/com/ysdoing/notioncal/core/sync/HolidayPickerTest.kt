package com.ysdoing.notioncal.core.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * EX-02 공휴일 캘린더 고르기·거르기. 표본은 S25에서 읽은 구글 '대한민국의 휴일'·삼성 '공휴일'의 2026년 일부다
 * (캘린더 ID·이름만 쓰고 개인 계정은 넣지 않는다).
 */
class HolidayPickerTest {
    private val googleKo = CalendarRow(7, "ko.south_korea#holiday@group.v.calendar.google.com", "대한민국의 휴일", syncEvents = true)
    private val googleKoCopy = CalendarRow(42, "ko.south_korea#holiday@group.v.calendar.google.com", "대한민국의 휴일", syncEvents = false)
    private val googleEn = CalendarRow(44, "en.south_korea#holiday@group.v.calendar.google.com", "Holidays in South Korea", syncEvents = true)
    private val googleUsa = CalendarRow(8, "ko.usa#holiday@group.v.calendar.google.com", "미국의 휴일", syncEvents = true)
    private val samsung = CalendarRow(3, "local.samsungholiday", "공휴일", syncEvents = true)
    private val samsungMemorial = CalendarRow(4, "local.samsungholiday", "법정기념일", syncEvents = true)

    private fun utc(date: String) = LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
    private fun g(title: String, date: String, description: String? = "공휴일", calendar: Long = 7) =
        HolidayInstance(calendar, title, description, utc(date), allDay = true)

    private val from = LocalDate.of(2026, 1, 1)
    private val to = LocalDate.of(2027, 12, 31)

    @Test
    fun prefersSyncedGoogleKoreanCalendar() {
        val choice = HolidayPicker.choose(listOf(samsung, samsungMemorial, googleUsa, googleKoCopy, googleEn, googleKo))!!
        assertEquals(HolidayPicker.GOOGLE, choice.source)
        assertEquals(listOf(7L, 44L), choice.calendarIds) // 동기화 안 된 사본·미국 휴일 제외, 한국어 먼저
    }

    @Test
    fun fallsBackToSamsungThenNothing() {
        assertEquals(HolidayPicker.Choice(HolidayPicker.SAMSUNG, listOf(3L)), HolidayPicker.choose(listOf(samsung, samsungMemorial, googleKoCopy)))
        assertNull(HolidayPicker.choose(listOf(samsungMemorial, googleUsa)))
    }

    @Test
    fun googleKeepsOnlyPublicHolidaysIncludingLongWeekendsAndSubstitutes() {
        val instances = listOf(
            g("섣달 그믐날", "2025-12-31", description = "기념일"),
            g("새해첫날", "2026-01-01"),
            g("설날 연휴", "2026-02-16"),
            g("설날", "2026-02-17"),
            g("설날 연휴", "2026-02-18"),
            g("쉬는 날 삼일절", "2026-03-02"),
            g("어버이날", "2026-05-08", description = "기념일"),
            g("스승의날", "2026-05-15", description = null),
            g("지방선거일", "2026-06-03"),
            g("쉬는 날 개천절", "2026-10-05"),
            g("국군의날", "2026-10-01", description = "기념일"),
            g("Chuseok", "2026-09-25", description = "Public holiday", calendar = 44),
            g("추석", "2026-09-25"),
        )
        val choice = HolidayPicker.Choice(HolidayPicker.GOOGLE, listOf(7L, 44L))
        val holidays = HolidayPicker.holidays(choice, instances, from, to)
        assertEquals(
            listOf(
                "2026-01-01 새해첫날", "2026-02-16 설날 연휴", "2026-02-17 설날", "2026-02-18 설날 연휴", "2026-03-02 쉬는 날 삼일절",
                "2026-06-03 지방선거일", "2026-09-25 추석", "2026-10-05 쉬는 날 개천절",
            ),
            holidays.map { "${it.date} ${it.name}" },
        )
    }

    @Test
    fun samsungUsesAllRowsAndUtcDates() {
        val rows = listOf(
            HolidayInstance(3, "대체공휴일", null, utc("2026-03-02"), allDay = true),
            HolidayInstance(3, "한글날", null, utc("2026-10-09"), allDay = true),
        )
        val holidays = HolidayPicker.holidays(HolidayPicker.Choice(HolidayPicker.SAMSUNG, listOf(3L)), rows, from, to)
        assertEquals(listOf(LocalDate.of(2026, 3, 2), LocalDate.of(2026, 10, 9)), holidays.map { it.date })
        // 종일 일정은 UTC 자정이라 서울 시간으로 바꾸면 하루가 밀리지 않는다(UTC 날짜를 쓴다).
        assertEquals(LocalDate.of(2026, 10, 9), HolidayPicker.dateOf(rows[1]))
    }
}
