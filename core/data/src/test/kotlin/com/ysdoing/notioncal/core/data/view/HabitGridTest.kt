package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Phase 12e RC-03 해빗 7일 격자(순수): 오늘까지 최근 7일 칸, 같은 날 여러 행, 대기 중인 새 항목, 사라진 열. 오늘은 2026-11-06. */
class HabitGridTest {
    private val today = LocalDate.of(2026, 11, 6)
    private val date = ViewColumn("d", "날짜", "date")
    private val run = ViewColumn("c1", "운동", "checkbox")
    private val read = ViewColumn("c2", "독서", "checkbox")
    private val memo = ViewColumn("m", "메모", "rich_text")

    private fun row(id: String, on: String, vararg checks: Pair<String, Boolean>): ViewRow {
        val typed = mutableMapOf<String, JsonElement>("d" to JsonObject(mapOf("start" to JsonPrimitive(on))))
        checks.forEach { (prop, value) -> typed[prop] = JsonPrimitive(value) }
        return ViewRow(id, "", listOf(id), typed = typed)
    }

    private fun board(rows: List<ViewRow>, pending: Map<LocalDate, Set<String>> = emptyMap(), complete: Boolean = true) =
        ViewBoard("v1", "습관", "table", listOf("이름"), rows, 1L, null, listOf(date, run, read, memo), rowsComplete = complete, pendingCreateChecks = pending)

    @Test
    fun theWindowIsTheLastSevenDaysEndingToday() {
        val result = HabitGrid.compute(board(emptyList()), "d", listOf("c1"), today)!!
        assertEquals((0..6).map { LocalDate.of(2026, 10, 31).plusDays(it.toLong()) }, result.days)
        assertEquals(result.days, result.lines.single().cells.map { it.date })
        assertEquals(0, result.lines.single().count)
    }

    @Test
    fun aDayIsDoneWhenItsRowHasTheCheckboxCheckedAndOtherDaysOrBoxesDoNotCount() {
        val rows = listOf(
            row("a", "2026-11-06", "c1" to true, "c2" to false), row("b", "2026-11-05", "c1" to false, "c2" to true),
            row("old", "2026-10-30", "c1" to true), // 창 밖
            row("future", "2026-11-07", "c1" to true), // 오늘 이후
        )
        val result = HabitGrid.compute(board(rows), "d", listOf("c1", "c2"), today)!!
        val (runLine, readLine) = result.lines
        assertEquals(listOf("운동", "독서"), result.lines.map { it.name })
        assertEquals(listOf(LocalDate.of(2026, 11, 6)), runLine.cells.filter { it.done }.map { it.date })
        assertEquals(listOf(LocalDate.of(2026, 11, 5)), readLine.cells.filter { it.done }.map { it.date })
        assertEquals(1, runLine.count)
    }

    @Test
    fun severalRowsOnTheSameDayCountAsDoneIfAnyIsChecked() {
        val rows = listOf(row("a", "2026-11-06", "c1" to false), row("b", "2026-11-06", "c1" to true))
        assertEquals(1, HabitGrid.compute(board(rows), "d", listOf("c1"), today)!!.lines.single().count)
    }

    @Test
    fun aQueuedNewItemShowsItsCheckedHabitsAsDoneBeforeItIsSent() {
        val pending = mapOf(today to setOf("c2"), today.minusDays(2) to setOf("c1", "c2"))
        val result = HabitGrid.compute(board(emptyList(), pending), "d", listOf("c1", "c2"), today)!!
        assertEquals(listOf(today.minusDays(2)), result.lines[0].cells.filter { it.done }.map { it.date })
        assertEquals(listOf(today.minusDays(2), today), result.lines[1].cells.filter { it.done }.map { it.date })
    }

    @Test
    fun missingOrWrongTypedColumnsAreLeftOutAndNoHabitMeansNoGrid() {
        val result = HabitGrid.compute(board(emptyList()), "d", listOf("gone", "m", "c1"), today)!!
        assertEquals(listOf("c1"), result.lines.map { it.propId }) // 사라진 열·체크박스가 아닌 열은 뺀다
        assertNull(HabitGrid.compute(board(emptyList()), "d", listOf("gone", "m"), today))
        assertNull(HabitGrid.compute(board(emptyList()), "gone", listOf("c1"), today)) // 날짜 열이 사라졌다
        assertNull(HabitGrid.compute(board(emptyList()), "m", listOf("c1"), today)) // 날짜 열이 아니다
    }

    @Test
    fun aPartialBoardIsMarkedPartial() {
        assertEquals(true, HabitGrid.compute(board(emptyList(), complete = false), "d", listOf("c1"), today)!!.partial)
    }

    @Test
    fun theTitleIsTheMonthAndDayInKorean() {
        assertEquals("11월 6일", HabitTitle.of(LocalDate.of(2026, 11, 6)))
        assertEquals("1월 1일", HabitTitle.of(LocalDate.of(2027, 1, 1)))
        assertEquals("12월 31일", HabitTitle.of(LocalDate.of(2026, 12, 31)))
    }
}
