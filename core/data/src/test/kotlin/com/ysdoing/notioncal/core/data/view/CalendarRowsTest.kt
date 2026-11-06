package com.ysdoing.notioncal.core.data.view

import com.ysdoing.notioncal.core.data.CalendarEvent
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/**
 * Phase 12a VR-CAL 캘린더 보기의 행 → 월 격자가 그리는 일정. 날짜는 보기의 날짜 열(숨긴 `date_property_id` 포함)을 읽고,
 * 날짜만 있으면 종일(끝 날짜 포함), 시각이 있으면 시간 일정이며 시각의 날짜는 Asia/Seoul 기준이다.
 */
class CalendarRowsTest {
    private val title = ViewColumn("t", "이름", "title")
    private val date = ViewColumn("d", "날짜", "date")
    private val status = ViewColumn(
        "s", "상태", "status",
        listOf(ViewChoice("o1", "할 일", "To-do", "gray"), ViewChoice("o2", "진행 중", "In progress", "blue"), ViewChoice("o3", "완료", "Complete", "green"), ViewChoice("o4", "기본", "To-do", "default")),
    )
    private val category = ViewColumn("k", "분류", "select", listOf(ViewChoice("c1", "일", color = "red")))
    private val done = ViewColumn("c", "완료", "checkbox")

    private fun day(start: String?, end: String? = null, zone: String? = null): JsonElement = JsonObject(
        buildMap {
            if (start != null) put("start", JsonPrimitive(start))
            if (end != null) put("end", JsonPrimitive(end))
            if (zone != null) put("time_zone", JsonPrimitive(zone))
        },
    )

    private fun row(id: String, name: String? = id, on: JsonElement? = null, extra: Map<String, JsonElement> = emptyMap()): ViewRow {
        val typed = buildMap<String, JsonElement> {
            if (name != null) put("t", JsonPrimitive(name))
            if (on != null) put("d", on)
            putAll(extra)
        }
        return ViewRow(id, "https://n/$id", listOf(name.orEmpty()), typed = typed)
    }

    private fun board(rows: List<ViewRow>, columns: List<ViewColumn> = listOf(title, date, status, done), pending: Set<String> = emptySet()) =
        ViewBoard("v1", "일정", "calendar", listOf("이름"), rows, 1L, null, columnInfo = columns, pendingPageIds = pending)

    private fun events(rows: List<ViewRow>, columns: List<ViewColumn> = listOf(title, date, status, done), pending: Set<String> = emptySet()) =
        CalendarRows.events(board(rows, columns, pending))

    // --- 날짜 해석

    @Test
    fun aDateOnlyValueIsAnAllDayEventAndItsEndDateIsInclusive() {
        val e = events(listOf(row("a", on = day("2026-11-04")), row("b", on = day("2026-11-04", "2026-11-06")))).associateBy { it.pageId }
        assertEquals(LocalDate.of(2026, 11, 4), e.getValue("a").startDate)
        assertNull(e.getValue("a").endDate)
        assertTrue(e.getValue("a").isAllDay)
        assertEquals(LocalDate.of(2026, 11, 6), e.getValue("b").endDate)
        assertTrue(e.getValue("b").isMultiDay)
        assertNull(e.getValue("b").startTime)
    }

    @Test
    fun anEndDateThatIsNotAfterTheStartIsDropped() {
        val e = events(listOf(row("a", on = day("2026-11-04", "2026-11-04")))).single()
        assertNull(e.endDate)
    }

    @Test
    fun aTimedValueIsATimedEventWhoseDayIsReadInSeoul() {
        val e = events(listOf(row("a", on = day("2026-11-04T09:30:00.000+09:00", "2026-11-04T11:00:00.000+09:00")))).single()
        assertFalse(e.isAllDay)
        assertEquals(Instant.parse("2026-11-04T00:30:00Z"), e.startTime)
        assertEquals(Instant.parse("2026-11-04T02:00:00Z"), e.endTime)
        assertEquals(LocalDate.of(2026, 11, 4), e.startDate)
        assertNull(e.endDate) // 같은 날이면 끝 날짜는 두지 않는다
    }

    @Test
    fun aLateEveningUtcTimeBelongsToTheNextSeoulDay() {
        val e = events(listOf(row("a", on = day("2026-11-04T20:00:00.000Z")))).single()
        assertEquals(LocalDate.of(2026, 11, 5), e.startDate) // 11/4 20:00Z = 11/5 05:00 서울
    }

    @Test
    fun aTimeWithoutAnOffsetUsesTheTimeZoneThenSeoul() {
        val withZone = events(listOf(row("a", on = day("2026-11-04T09:00:00", zone = "America/New_York")))).single()
        assertEquals(Instant.parse("2026-11-04T14:00:00Z"), withZone.startTime)
        val without = events(listOf(row("b", on = day("2026-11-04T09:00:00")))).single()
        assertEquals(Instant.parse("2026-11-04T00:00:00Z"), without.startTime)
        val badZone = events(listOf(row("c", on = day("2026-11-04T09:00:00", zone = "Nowhere/Land")))).single()
        assertEquals(Instant.parse("2026-11-04T00:00:00Z"), badZone.startTime)
    }

    @Test
    fun rowsWithoutAUsableDateAreLeftOut() {
        val rows = listOf(
            row("none"), row("empty", on = day(null)), row("blank", on = day("  ")), row("garbage", on = day("내일")), row("notObject", on = JsonPrimitive("2026-11-04")),
            row("ok", on = day("2026-11-04")),
        )
        assertEquals(listOf("ok"), events(rows).map { it.pageId })
    }

    // --- 날짜 열 고르기

    @Test
    fun theDateColumnIsTheCalendarsHiddenDatePropertyWhenThereIsOne() {
        val hiddenDate = ViewColumn("d2", "예정일", "date", role = ViewColumn.ROLE_DATE)
        val columns = listOf(title, date, hiddenDate)
        val rows = listOf(row("a", on = day("2026-11-01"), extra = mapOf("d2" to day("2026-12-25"))))
        assertEquals(LocalDate.of(2026, 12, 25), events(rows, columns).single().startDate) // 보이는 첫 날짜 열이 아니라 date_property_id의 열
        assertEquals(LocalDate.of(2026, 11, 1), events(rows, listOf(title, date)).single().startDate) // 역할이 없으면 첫 날짜 열
    }

    @Test
    fun withoutAnyDateColumnThereAreNoEvents() {
        assertEquals(emptyList<CalendarEvent>(), events(listOf(row("a", on = day("2026-11-04"))), listOf(title, status)))
    }

    // --- 제목·색·완료·진행

    @Test
    fun theTitleComesFromTheTitleColumnWithAFallbackForBlankOnes() {
        val e = events(listOf(row("a", name = "  장보기 ", on = day("2026-11-04")), row("b", name = "", on = day("2026-11-04")), row("c", name = null, on = day("2026-11-04")))).associateBy { it.pageId }
        assertEquals("장보기", e.getValue("a").title)
        assertEquals("제목 없음", e.getValue("b").title)
        assertEquals("제목 없음", e.getValue("c").title)
    }

    @Test
    fun theColorFollowsTheFirstSelectOrStatusColumnsOptionAndFallsBackToGray() {
        val e = events(
            listOf(
                row("a", on = day("2026-11-04"), extra = mapOf("s" to JsonPrimitive("o2"))),
                row("b", on = day("2026-11-04"), extra = mapOf("s" to JsonPrimitive("o4"))), // default 색은 색이 아니다
                row("c", on = day("2026-11-04")),
                row("d", on = day("2026-11-04"), extra = mapOf("s" to JsonPrimitive("zzz"))),
            ),
        ).associateBy { it.pageId }
        assertEquals(com.ysdoing.notioncal.core.data.NotionColors.hexFor("blue"), e.getValue("a").colorHex)
        assertEquals("o2", e.getValue("a").colorOptionId)
        assertEquals(CalendarRows.FALLBACK_HEX, e.getValue("b").colorHex)
        assertEquals(CalendarRows.FALLBACK_HEX, e.getValue("c").colorHex)
        assertEquals(CalendarRows.FALLBACK_HEX, e.getValue("d").colorHex)
        assertNull(e.getValue("c").colorOptionId)
    }

    @Test
    fun aSelectColumnColorsTheEventWhenThereIsNoStatus() {
        val columns = listOf(title, date, category)
        val e = events(listOf(row("a", on = day("2026-11-04"), extra = mapOf("k" to JsonPrimitive("c1")))), columns).single()
        assertEquals(com.ysdoing.notioncal.core.data.NotionColors.hexFor("red"), e.colorHex)
    }

    @Test
    fun doneIsACheckedBoxOrAStatusInTheCompleteGroup() {
        val e = events(
            listOf(
                row("box", on = day("2026-11-04"), extra = mapOf("c" to JsonPrimitive(true))),
                row("unchecked", on = day("2026-11-04"), extra = mapOf("c" to JsonPrimitive(false))),
                row("complete", on = day("2026-11-04"), extra = mapOf("s" to JsonPrimitive("o3"))),
                row("active", on = day("2026-11-04"), extra = mapOf("s" to JsonPrimitive("o2"))),
                row("plain", on = day("2026-11-04")),
            ),
        ).associateBy { it.pageId }
        assertTrue(e.getValue("box").done)
        assertFalse(e.getValue("unchecked").done)
        assertTrue(e.getValue("complete").done)
        assertFalse(e.getValue("active").done)
        assertFalse(e.getValue("plain").done)
    }

    @Test
    fun anItemWaitingToBeSentIsPendingAndCarriesItsAddress() {
        val e = events(listOf(row("a", on = day("2026-11-04")), row("b", on = day("2026-11-04"))), pending = setOf("b")).associateBy { it.pageId }
        assertFalse(e.getValue("a").pending)
        assertTrue(e.getValue("b").pending)
        assertEquals("https://n/a", e.getValue("a").url)
    }

    // --- 순서

    @Test
    fun eventsAreOrderedByDayThenUntimedBeforeTimedThenTimeThenTheViewsOwnOrder() {
        val rows = listOf(
            row("late", on = day("2026-11-05T15:00:00+09:00")),
            row("dayB", on = day("2026-11-05")),
            row("early", on = day("2026-11-05T08:00:00+09:00")),
            row("dayA", on = day("2026-11-05")),
            row("first", on = day("2026-11-01")),
        )
        assertEquals(listOf("first", "dayB", "dayA", "early", "late"), events(rows).map { it.pageId })
    }

    // --- Phase 12e WL-04 합쳐 보기

    private val deadline = ViewColumn("e", "마감", "date")

    private fun source(viewId: String, rows: List<ViewRow>, columns: List<ViewColumn> = listOf(title, date), dateColumnId: String = "", colorHex: String = "") =
        CalendarRows.Source(ViewBoard(viewId, viewId, "table", listOf("이름"), rows, 1L, null, columnInfo = columns), dateColumnId, colorHex)

    @Test
    fun mergedEventsFromSeveralViewsShareOneDayOrderAndKeepTheirViewIds() {
        val a = source("a", listOf(row("a1", on = day("2026-11-05")), row("a2", on = day("2026-11-02"))))
        val b = source("b", listOf(row("b1", on = day("2026-11-05")), row("b2", on = day("2026-11-05T09:00:00+09:00"))))
        val merged = CalendarRows.merged(listOf(a, b))
        assertEquals(listOf("a2", "a1", "b1", "b2"), merged.map { it.pageId }) // 시작일 → 종일 먼저 → 같은 날은 소스 순서
        assertEquals(listOf("a", "a", "b", "b"), merged.map { it.dataSourceId }) // 탭하면 그 일정의 보기 시트를 열 수 있다
    }

    @Test
    fun theSourceOrderBreaksTiesTheSameWayWhateverTheViewsOwnRowOrder() {
        val a = source("a", listOf(row("a1", on = day("2026-11-05"))))
        val b = source("b", listOf(row("b1", on = day("2026-11-05"))))
        assertEquals(listOf("a1", "b1"), CalendarRows.merged(listOf(a, b)).map { it.pageId })
        assertEquals(listOf("b1", "a1"), CalendarRows.merged(listOf(b, a)).map { it.pageId })
    }

    @Test
    fun aSourceColorOverridesTheOptionColorAndBlankKeepsIt() {
        val colored = row("c", on = day("2026-11-05"), extra = mapOf("s" to JsonPrimitive("o3")))
        val rows = listOf(colored)
        val columns = listOf(title, date, status)
        assertEquals("#E03E3E", CalendarRows.merged(listOf(source("a", rows, columns, colorHex = "#E03E3E"))).single().colorHex)
        assertEquals("#0F7B6C", CalendarRows.merged(listOf(source("a", rows, columns))).single().colorHex) // 소스 색이 없으면 옵션 색
    }

    @Test
    fun aSourceReadsItsChosenDateColumnAndFallsBackToTheDefaultWhenItIsNotADateColumn() {
        val rows = listOf(row("x", on = day("2026-11-05"), extra = mapOf("e" to day("2026-11-20"))))
        val columns = listOf(title, date, deadline)
        assertEquals(LocalDate.of(2026, 11, 20), CalendarRows.merged(listOf(source("a", rows, columns, dateColumnId = "e"))).single().startDate)
        assertEquals(LocalDate.of(2026, 11, 5), CalendarRows.merged(listOf(source("a", rows, columns))).single().startDate)
        assertEquals(LocalDate.of(2026, 11, 5), CalendarRows.merged(listOf(source("a", rows, columns, dateColumnId = "t"))).single().startDate) // 제목은 날짜 열이 아니다
        assertEquals(LocalDate.of(2026, 11, 5), CalendarRows.merged(listOf(source("a", rows, columns, dateColumnId = "gone"))).single().startDate) // 지워진 속성
    }

    @Test
    fun aPageInTwoSourcesOfTheSameDatabaseIsDrawnOnceFromTheFirstSource() {
        val shared = row("same", on = day("2026-11-05"))
        val merged = CalendarRows.merged(listOf(source("a", listOf(shared), colorHex = "#0B6E99"), source("b", listOf(shared, row("only-b", on = day("2026-11-05"))), colorHex = "#E03E3E")))
        assertEquals(listOf("same", "only-b"), merged.map { it.pageId })
        assertEquals(listOf("#0B6E99", "#E03E3E"), merged.map { it.colorHex }) // 겹친 페이지는 앞 소스의 색
    }

    @Test
    fun aViewWithoutAnyDateColumnAddsNothingAndNoSourcesIsEmpty() {
        assertTrue(CalendarRows.merged(listOf(source("a", listOf(row("a1", on = day("2026-11-05"))), listOf(title)))).isEmpty())
        assertTrue(CalendarRows.merged(emptyList()).isEmpty())
    }
}
