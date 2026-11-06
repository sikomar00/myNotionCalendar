package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 12e VR-TML 타임라인(순수): 확대 단계 읽기(노션 값은 실측 전이라 느슨히), 주·월 창, 창과 겹치는 일정의 막대(시작 칸·폭·이어짐).
 * 오늘은 2026-11-04(수)이고 주는 월요일에 시작한다(11/2~11/8).
 */
class TimelineRowsTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val date = ViewColumn("d", "기간", "date")
    private val title = ViewColumn("t", "이름", "title")

    private fun row(id: String, start: String, end: String? = null): ViewRow {
        val value = JsonObject(buildMap { put("start", JsonPrimitive(start)); end?.let { put("end", JsonPrimitive(it)) } })
        return ViewRow(id, "", listOf(id), typed = mapOf<String, JsonElement>("d" to value, "t" to JsonPrimitive("일 $id")))
    }

    private fun board(vararg rows: ViewRow) = ViewBoard("v1", "계획", "timeline", listOf("이름"), rows.toList(), 1L, null, listOf(title, date))

    // --- 확대 단계

    @Test
    fun zoomLevelsAreReadLooselyAndUnknownValuesAreAuto() {
        fun zoom(level: String) = TimelineConfig.zoomOf("""{"type":"timeline","preference":{"zoom_level":"$level"}}""")
        assertEquals(TimelineZoom.WEEK, zoom("week"))
        assertEquals(TimelineZoom.WEEK, zoom("Day"))
        assertEquals(TimelineZoom.WEEK, zoom("bi_week"))
        assertEquals(TimelineZoom.MONTH, zoom("month"))
        assertEquals(TimelineZoom.MONTH, zoom("quarter"))
        assertEquals(TimelineZoom.AUTO, zoom("S")) // 가려진 실측 값
        listOf("", "  ", "not json", "{}", """{"preference":"x"}""", "[1]").forEach { assertEquals(it, TimelineZoom.AUTO, TimelineConfig.zoomOf(it)) }
    }

    // --- 창

    @Test
    fun theWeekWindowStartsOnTheWeekStartDayAndMovesByWeeks() {
        val monday = TimelineRows.window(TimelineZoom.WEEK, today, DayOfWeek.MONDAY, 0)
        assertEquals(TimelineWindow(LocalDate.of(2026, 11, 2), 7), monday)
        assertEquals(LocalDate.of(2026, 11, 8), monday.last)
        assertEquals(LocalDate.of(2026, 11, 1), TimelineRows.window(TimelineZoom.AUTO, today, DayOfWeek.SUNDAY, 0).start) // 일요일 시작
        assertEquals(LocalDate.of(2026, 11, 16), TimelineRows.window(TimelineZoom.WEEK, today, DayOfWeek.MONDAY, 2).start)
        assertEquals(LocalDate.of(2026, 10, 26), TimelineRows.window(TimelineZoom.WEEK, today, DayOfWeek.MONDAY, -1).start)
    }

    @Test
    fun theMonthWindowIsTheWholeMonthAndMovesByMonthsAcrossYearsAndLeapDays() {
        assertEquals(TimelineWindow(LocalDate.of(2026, 11, 1), 30), TimelineRows.window(TimelineZoom.MONTH, today, DayOfWeek.MONDAY, 0))
        assertEquals(TimelineWindow(LocalDate.of(2027, 1, 1), 31), TimelineRows.window(TimelineZoom.MONTH, today, DayOfWeek.MONDAY, 2))
        assertEquals(TimelineWindow(LocalDate.of(2028, 2, 1), 29), TimelineRows.window(TimelineZoom.MONTH, LocalDate.of(2028, 2, 10), DayOfWeek.MONDAY, 0)) // 윤년
        assertEquals(LocalDate.of(2026, 10, 31), TimelineRows.window(TimelineZoom.MONTH, today, DayOfWeek.MONDAY, -1).last)
    }

    // --- 막대

    private val week = TimelineWindow(LocalDate.of(2026, 11, 2), 7)

    @Test
    fun aBarSitsAtItsStartColumnWithItsLengthInDays() {
        val bars = TimelineRows.bars(board(row("a", "2026-11-03", "2026-11-05"), row("b", "2026-11-08")), week)
        assertEquals(listOf(Triple(1, 3, false), Triple(6, 1, false)), bars.map { Triple(it.startCol, it.span, it.continuesBefore || it.continuesAfter) })
    }

    @Test
    fun barsAreClippedToTheWindowAndMarkWhereTheyContinue() {
        val bars = TimelineRows.bars(board(row("before", "2026-10-30", "2026-11-03"), row("after", "2026-11-06", "2026-11-12"), row("both", "2026-10-20", "2026-11-30")), week)
        val byId = bars.associateBy { it.event.pageId } // 시작일 순으로 나온다(both → before → after)
        val before = byId.getValue("before")
        val after = byId.getValue("after")
        val both = byId.getValue("both")
        assertEquals(listOf(0, 2, true, false), listOf(before.startCol, before.span, before.continuesBefore, before.continuesAfter))
        assertEquals(listOf(4, 3, false, true), listOf(after.startCol, after.span, after.continuesBefore, after.continuesAfter))
        assertEquals(listOf(0, 7, true, true), listOf(both.startCol, both.span, both.continuesBefore, both.continuesAfter))
    }

    @Test
    fun eventsOutsideTheWindowAndRowsWithoutADateAreLeftOut() {
        val noDate = ViewRow("n", "", listOf("n"), typed = mapOf<String, JsonElement>("t" to JsonPrimitive("날짜 없음")))
        val bars = TimelineRows.bars(board(row("old", "2026-10-01", "2026-10-31"), row("late", "2026-11-09"), noDate, row("in", "2026-11-02")), week)
        assertEquals(listOf("in"), bars.map { it.event.pageId })
    }

    @Test
    fun aTimedEventIsAOneDayBarOnItsSeoulDay() {
        val bars = TimelineRows.bars(board(row("t", "2026-11-04T09:00:00.000+09:00", "2026-11-04T10:00:00.000+09:00")), week)
        assertEquals(listOf(2, 1), listOf(bars.single().startCol, bars.single().span))
    }

    @Test
    fun theMonthWindowFitsLongBarsAndKeepsViewOrder() {
        val month = TimelineRows.window(TimelineZoom.MONTH, today, DayOfWeek.MONDAY, 0)
        val bars = TimelineRows.bars(board(row("b", "2026-11-10", "2026-11-20"), row("a", "2026-11-01")), month)
        assertEquals(listOf("a", "b"), bars.map { it.event.pageId }) // 시작일 순(캘린더와 같은 정렬)
        assertEquals(listOf(9, 11), listOf(bars[1].startCol, bars[1].span))
    }
}
