package com.ysdoing.notioncal.core.data

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** 켜 둔 화면이 자정을 넘기면 다음 날을 받는다(서울 기준, 기기 시간대와 무관). */
@OptIn(ExperimentalCoroutinesApi::class)
class TodayTest {
    @Test
    fun emitsTheNextDayRightAfterSeoulMidnight() = runTest {
        val start = Instant.parse("2026-11-09T14:59:00Z") // 서울 23:59
        val clock = object : Clock() {
            override fun getZone(): ZoneId = ZoneId.of("UTC")
            override fun withZone(zone: ZoneId?): Clock = this
            override fun instant(): Instant = start.plusMillis(testScheduler.currentTime)
        }
        val days = observeToday(clock).take(3).toList()
        assertEquals(listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 11)), days)
        assertEquals(61_000L + 24 * 3_600_000L, testScheduler.currentTime) // 자정 + 1초, 그다음 자정 + 1초
    }
}
