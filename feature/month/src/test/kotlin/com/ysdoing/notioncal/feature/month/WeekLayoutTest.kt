package com.ysdoing.notioncal.feature.month

import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** MV-02: 같은 날 5개 + 3일짜리 3개에서 누락·겹침 0. */
class WeekLayoutTest {
    private val weekStart = LocalDate.of(2026, 10, 11) // 일요일

    private fun event(id: String, start: String, end: String? = null) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = id, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
        startTime = null, endTime = null, isAllDay = true, done = false, colorHex = "#000000", sourceColorHex = "#000000", url = "",
    )

    private val fixture = (1..5).map { event("day$it", "2026-10-14") } +
        listOf(
            event("spanA", "2026-10-13", "2026-10-15"),
            event("spanB", "2026-10-14", "2026-10-16"),
            event("spanC", "2026-10-12", "2026-10-14"),
        )

    /** 모든 칸에서 (보이는 일정 + 숨긴 수) = 그 칸에 걸친 일정 수, 같은 줄·칸에 두 개 없음, +N 줄과 안 겹침. */
    private fun assertConsistent(events: List<CalendarEvent>, maxLanes: Int) {
        val result = WeekLayout.layout(weekStart, events, maxLanes)
        for (col in 0..6) {
            val date = weekStart.plusDays(col.toLong())
            val total = events.count { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }
            val visibleHere = result.placed.filter { col in it.startCol until it.startCol + it.span }
            assertEquals("칸 $col 누락", total, visibleHere.size + result.hiddenCount[col])
            assertEquals("칸 $col 겹침", visibleHere.size, visibleHere.map { it.lane }.toSet().size)
            assertTrue("칸 $col 줄 초과", visibleHere.all { it.lane < maxLanes })
            if (result.hiddenCount[col] > 0) {
                assertTrue("칸 $col +N 자리", visibleHere.none { it.lane >= result.overflowLane[col] })
                assertTrue(result.overflowLane[col] < maxLanes)
            }
        }
    }

    @Test
    fun noLossOrOverlapForAllLaneLimits() {
        for (maxLanes in 1..10) assertConsistent(fixture, maxLanes)
    }

    @Test
    fun everythingFitsWithEnoughLanes() {
        val result = WeekLayout.layout(weekStart, fixture, maxLanes = 8)
        assertEquals(8, result.placed.size)
        assertTrue(result.hiddenCount.all { it == 0 })
        // 여러 날 일정이 먼저 위쪽 줄을 차지한다.
        val spanLanes = result.placed.filter { it.event.pageId.startsWith("span") }.map { it.lane }
        assertTrue(spanLanes.all { it < 3 })
    }

    @Test
    fun overflowShowsPlusN() {
        val result = WeekLayout.layout(weekStart, fixture, maxLanes = 3)
        // 10/14(수, 칸 3): 막대 3개 + 하루 5개 = 8개 중 2줄만 보이고 나머지는 +N.
        val wednesdayVisible = result.placed.count { 3 in it.startCol until it.startCol + it.span }
        assertEquals(2, wednesdayVisible)
        assertEquals(6, result.hiddenCount[3])
        assertEquals(2, result.overflowLane[3])
    }

    @Test
    fun barsCrossingWeekBoundaryAreClipped() {
        val result = WeekLayout.layout(weekStart, listOf(event("long", "2026-10-08", "2026-10-20")), maxLanes = 3)
        val bar = result.placed.single()
        assertEquals(0, bar.startCol)
        assertEquals(7, bar.span)
        assertTrue(bar.continuesBefore)
        assertTrue(bar.continuesAfter)
    }

    @Test
    fun eventsOutsideWeekAreIgnored() {
        val result = WeekLayout.layout(weekStart, listOf(event("prev", "2026-10-10"), event("next", "2026-10-18")), maxLanes = 3)
        assertTrue(result.placed.isEmpty())
        assertFalse(result.hiddenCount.any { it > 0 })
    }
}
