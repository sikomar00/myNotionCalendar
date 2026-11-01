package com.ysdoing.notioncal.core.data.dday

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** DD-02 디데이 표기: 앞날 D-N, 당일 D-Day, 지난 날 D+N, 기간 일정 진행 중, 계속 세기, 숨김, 윤년·월말. */
class DdayMathTest {
    private val today = LocalDate.of(2026, 10, 19)
    private fun d(y: Int, m: Int, day: Int) = LocalDate.of(y, m, day)

    @Test
    fun countDown() {
        assertEquals("D-12", DdayMath.label(d(2026, 10, 31), null, today).text)
        assertEquals("D-1", DdayMath.label(d(2026, 10, 20), null, today).text)
        assertEquals("D-Day", DdayMath.label(today, null, today).text)
        assertEquals("D+3", DdayMath.label(d(2026, 10, 16), null, today).text)
    }

    /** 자정을 넘기면 오늘이 하루 밀려 D-1 → D-Day(날짜만 있는 일정은 Asia/Seoul LocalDate로 센다). */
    @Test
    fun midnightMovesOneDay() {
        val target = d(2026, 10, 20)
        assertEquals("D-1", DdayMath.label(target, null, today).text)
        assertEquals("D-Day", DdayMath.label(target, null, today.plusDays(1)).text)
    }

    @Test
    fun leapYearAndMonthEnd() {
        assertEquals("D-1", DdayMath.label(d(2028, 2, 29), null, d(2028, 2, 28)).text)
        assertEquals("D-2", DdayMath.label(d(2028, 3, 1), null, d(2028, 2, 28)).text) // 윤년 2/29를 센다
        assertEquals("D-1", DdayMath.label(d(2027, 3, 1), null, d(2027, 2, 28)).text) // 평년
        assertEquals("D-1", DdayMath.label(d(2026, 11, 1), null, d(2026, 10, 31)).text)
        assertEquals("D-366", DdayMath.label(d(2028, 3, 1), null, d(2027, 3, 1)).text)
    }

    /** 기간 일정은 시작일 기준. 진행 중이면 'D-Day · 2/3일째', 끝나면 끝난 날부터 D+N. */
    @Test
    fun periodEvent() {
        val start = d(2026, 10, 18)
        val end = d(2026, 10, 20)
        assertEquals("D-1", DdayMath.label(start, end, d(2026, 10, 17)).text)
        assertEquals("D-Day · 1/3일째", DdayMath.label(start, end, start).text)
        assertEquals("D-Day · 2/3일째", DdayMath.label(start, end, today).text)
        assertEquals("D-Day · 3/3일째", DdayMath.label(start, end, end).text)
        assertEquals("D+4", DdayMath.label(start, end, d(2026, 10, 22)).text)
        assertTrue(DdayMath.label(start, end, today).inProgress)
    }

    /** 지난 디데이는 hideAfterDays(기본 7일) 뒤 숨긴다. 계속 세기는 숨기지 않는다. */
    @Test
    fun hideAfterDays() {
        assertFalse(DdayMath.label(today.minusDays(7), null, today).hidden)
        assertTrue(DdayMath.label(today.minusDays(8), null, today).hidden)
        assertFalse(DdayMath.label(today.minusDays(8), null, today, hideAfterDays = 30).hidden)
        // 기간 일정은 끝난 날부터 센다.
        assertFalse(DdayMath.label(today.minusDays(20), today.minusDays(5), today).hidden)
        assertFalse(DdayMath.label(today.minusDays(400), null, today, DdayMode.UP).hidden)
    }

    /** 기념일처럼 계속 세기: D+100 또는 100일째(시작일이 1일째). */
    @Test
    fun countUpModes() {
        val start = today.minusDays(99)
        assertEquals("D+99", DdayMath.label(start, null, today, DdayMode.UP).text)
        assertEquals("100일째", DdayMath.label(start, null, today, DdayMode.DAYS).text)
        assertEquals("1일째", DdayMath.label(today, null, today, DdayMode.DAYS).text)
        assertEquals("D-3", DdayMath.label(today.plusDays(3), null, today, DdayMode.DAYS).text)
    }

    /** 네 자리(D-1234)는 위젯이 한 단계 작은 글자를 쓰도록 자릿수를 알려 준다. */
    @Test
    fun fourDigits() {
        val label = DdayMath.label(today.plusDays(1234), null, today)
        assertEquals("D-1234", label.text)
        assertEquals(4, label.digits)
        assertEquals(2, DdayMath.label(today.plusDays(12), null, today).digits)
    }

    /** 목록 순서: 진행 중·오늘 → 가까운 앞날 → 최근 지난 날. */
    @Test
    fun nearestFirstOrder() {
        val dates = listOf(today.minusDays(2), today.plusDays(30), today, today.plusDays(3))
        val sorted = dates.sortedBy { DdayMath.label(it, null, today).sortKey }
        assertEquals(listOf(today, today.plusDays(3), today.plusDays(30), today.minusDays(2)), sorted)
    }
}
