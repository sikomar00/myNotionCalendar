package com.ysdoing.notioncal.core.notion.mapping

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.util.TimeZone

/** NS-07 날짜 처리: 날짜만 있는 값은 시간대 변환 금지, 시간 값은 Asia/Seoul 기준 날짜. */
class DateCodecTest {
    private val originalZone = TimeZone.getDefault()

    @After
    fun restoreZone() = TimeZone.setDefault(originalZone)

    @Test
    fun allDayStaysOnSameDateEvenWhenDeviceZoneIsUtc() {
        // §12 필수 시나리오: 종일 2026-10-31은 기기 시간대를 UTC로 바꿔도 10/31에 남는다.
        for (zone in listOf("Asia/Seoul", "UTC", "America/Los_Angeles", "Pacific/Kiritimati")) {
            TimeZone.setDefault(TimeZone.getTimeZone(zone))
            val date = DateCodec.parse("2026-10-31", null)
            assertEquals(NotionDate.AllDay(LocalDate.of(2026, 10, 31), null), date)
            assertEquals(LocalDate.of(2026, 10, 31) to null, DateCodec.displayDates(date!!))
        }
    }

    @Test
    fun timedUtcValueIsShownOnSeoulDate() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        val date = DateCodec.parse("2026-10-12T23:30:00.000+00:00", null) as NotionDate.Timed
        assertEquals(Instant.parse("2026-10-12T23:30:00Z"), date.start)
        assertEquals(LocalDate.of(2026, 10, 13) to null, DateCodec.displayDates(date))
    }

    @Test
    fun aroundMidnightInSeoul() {
        val before = DateCodec.parse("2026-10-12T23:59:00+09:00", null)!!
        val after = DateCodec.parse("2026-10-13T00:01:00+09:00", null)!!
        assertEquals(LocalDate.of(2026, 10, 12), DateCodec.displayDates(before).first)
        assertEquals(LocalDate.of(2026, 10, 13), DateCodec.displayDates(after).first)
    }

    @Test
    fun timedRangeAcrossDaysKeepsEndDate() {
        val date = DateCodec.parse("2026-10-30T22:00:00+09:00", "2026-11-01T09:00:00+09:00")!!
        assertEquals(LocalDate.of(2026, 10, 30) to LocalDate.of(2026, 11, 1), DateCodec.displayDates(date))
    }

    @Test
    fun allDayRangeIsInclusiveAcrossMonthEnd() {
        val date = DateCodec.parse("2026-10-30", "2026-11-02")!!
        assertEquals(LocalDate.of(2026, 10, 30) to LocalDate.of(2026, 11, 2), DateCodec.displayDates(date))
    }

    @Test
    fun leapDay() {
        assertEquals(NotionDate.AllDay(LocalDate.of(2028, 2, 29), null), DateCodec.parse("2028-02-29", null))
        assertNull(DateCodec.parse("2026-02-29", null))
    }

    @Test
    fun valueWithoutOffsetUsesGivenTimeZone() {
        val date = DateCodec.parse("2026-10-13T09:00:00.000", null, "America/New_York") as NotionDate.Timed
        assertEquals(Instant.parse("2026-10-13T13:00:00Z"), date.start)
    }

    @Test
    fun blankOrMalformedIsNull() {
        assertNull(DateCodec.parse(null, null))
        assertNull(DateCodec.parse("", null))
        assertNull(DateCodec.parse("10/13/2026", null))
    }

    @Test
    fun filterValuesAlwaysCarrySeoulOffset() {
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"))
        assertEquals("2026-10-01T00:00:00+09:00", DateCodec.filterStartOfDay(LocalDate.of(2026, 10, 1)))
        assertEquals("2026-10-31T23:59:59+09:00", DateCodec.filterEndOfDay(LocalDate.of(2026, 10, 31)))
        assertEquals("2026-10-12T00:13:00+09:00", DateCodec.filterInstant(Instant.parse("2026-10-11T15:13:00.123Z")))
    }
}
