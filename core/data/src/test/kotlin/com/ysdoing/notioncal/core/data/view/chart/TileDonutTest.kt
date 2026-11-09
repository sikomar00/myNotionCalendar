package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.TileKind
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.WidgetLayout
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 12c RC-04 분류별 도넛 타일: 타일의 기간에 드는 행만 분류 속성으로 묶어 개수·합계로 센다(차트 보기와 같은 계산).
 * 오늘은 2026-11-04. 이번 달은 11월이다.
 */
class TileDonutTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val options = listOf(ViewChoice("o1", "식비", color = "red"), ViewChoice("o2", "교통", color = "blue"), ViewChoice("o3", "취미", color = "green"))
    private val category = ViewColumn("cat", "분류", "select", options)
    private val amount = ViewColumn("amt", "금액", "number")
    private val date = ViewColumn("d", "날짜", "date")
    private val memo = ViewColumn("m", "메모", "rich_text")

    private fun optionId(name: String?) = options.firstOrNull { it.name == name }?.id

    private fun row(id: String, on: String, cat: String?, money: Double): ViewRow {
        val typed = buildMap<String, JsonElement> {
            put("d", JsonObject(mapOf("start" to JsonPrimitive(on))))
            put("amt", JsonPrimitive(money))
            optionId(cat)?.let { put("cat", JsonPrimitive(it)) }
        }
        return ViewRow(id, "", emptyList(), typed = typed)
    }

    private val rows = listOf(
        row("a", "2026-11-01", "식비", 10000.0), row("b", "2026-11-02", "식비", 5000.0), row("c", "2026-11-03", "교통", 3000.0),
        row("d", "2026-10-31", "취미", 99999.0), // 지난달: 이번 달 도넛에 없다
    )

    private fun board(rows: List<ViewRow> = this.rows) =
        ViewBoard("v1", "가계부", "table", emptyList(), rows, 1L, null, columnInfo = listOf(category, amount, date, memo))

    private fun donut(group: String = "cat", fn: AggFn = AggFn.SUM, period: AggPeriod = AggPeriod.THIS_MONTH) =
        AggTile("t", "v1", fn, if (fn == AggFn.SUM) "amt" else "", "d", period, kind = TileKind.DONUT, groupProp = group)

    @Test
    fun onlyRowsInTheTilesPeriodAreGroupedAndSummed() {
        val result = TileDonut.compute(board(), donut(), today, DayOfWeek.MONDAY)!!
        assertEquals(listOf("식비", "교통"), result.points.map { it.label }) // 취미는 이번 달 행이 없어 숨긴다
        assertEquals(listOf(15000.0, 3000.0), result.points.map { it.value })
        assertEquals(ChartType.DONUT, result.spec.type)
    }

    @Test
    fun theWholePeriodCountsEveryRow() {
        val all = TileDonut.compute(board(), donut(fn = AggFn.COUNT, period = AggPeriod.ALL), today, DayOfWeek.MONDAY)!!
        assertEquals(listOf("식비" to 2.0, "교통" to 1.0, "취미" to 1.0), all.points.map { it.label to it.value })
    }

    @Test
    fun theSliceValuesMatchWhatAChartViewOfTheSameRowsWouldShow() {
        val tile = TileDonut.compute(board(), donut(), today, DayOfWeek.MONDAY)!!
        val inMonth = rows.filter { it.pageId != "d" }
        val chart = ChartCompute.compute(board(inMonth), ChartSpec(ChartType.DONUT, ChartGroup("cat", GroupKind.OPTION), ChartValue(AggFn.SUM, "amt"), hideEmpty = true))
        assertEquals(chart.points, tile.points)
        assertEquals(chart.totalText, tile.totalText)
    }

    @Test
    fun manyCategoriesFoldTheRestIntoOther() {
        val many = (1..8).map { ViewChoice("x$it", "분류$it") }
        val column = category.copy(options = many)
        val manyRows = many.mapIndexed { i, o ->
            ViewRow("r$i", "", emptyList(), typed = mapOf("cat" to JsonPrimitive(o.id), "amt" to JsonPrimitive(100.0 * (i + 1)), "d" to JsonObject(mapOf("start" to JsonPrimitive("2026-11-02")))))
        }
        val b = board(manyRows).copy(columnInfo = listOf(column, amount, date))
        val result = TileDonut.compute(b, donut(), today, DayOfWeek.MONDAY, maxPoints = 5)!!
        assertEquals(5, result.points.size)
        assertEquals(ChartCompute.OTHER_LABEL, result.points.last().label)
    }

    @Test
    fun aMultiSelectGroupCountsARowInEveryOptionItHas() {
        val tags = ViewColumn("tg", "태그", "multi_select", options)
        val r = ViewRow("m", "", emptyList(), typed = mapOf("tg" to JsonArray(listOf(JsonPrimitive("o1"), JsonPrimitive("o2")))))
        val b = board(listOf(r)).copy(columnInfo = listOf(tags, amount, date))
        val result = TileDonut.compute(b, donut(group = "tg", fn = AggFn.COUNT, period = AggPeriod.ALL), today, DayOfWeek.MONDAY)!!
        assertEquals(listOf("식비" to 1.0, "교통" to 1.0), result.points.map { it.label to it.value })
    }

    @Test
    fun aGroupThatIsGoneOrNotAnOptionPropertyOrAValueTileGivesNoDonut() {
        assertNull(TileDonut.compute(board(), donut(group = "zzz"), today, DayOfWeek.MONDAY)) // 속성이 사라짐
        assertNull(TileDonut.compute(board(), donut(group = "m"), today, DayOfWeek.MONDAY)) // 글자 속성
        assertNull(TileDonut.compute(board(), donut().copy(kind = TileKind.AGG), today, DayOfWeek.MONDAY))
    }

    @Test
    fun aBoardWithNoRowsInThePeriodHasNoPoints() {
        val result = TileDonut.compute(board(listOf(rows.last())), donut(), today, DayOfWeek.MONDAY)!!
        assertEquals(emptyList<ChartPoint>(), result.points)
    }

    // --- 정규화·JSON

    @Test
    fun aDonutTileNeedsAGroupPropertyAndOnlyCountOrSumAndLosesGoalAndColor() {
        val ok = donut().copy(goal = 100.0, color = "red").normalized()!!
        assertEquals(listOf(null, ""), listOf(ok.goal, ok.color))
        assertNull(donut(group = " ").normalized())
        assertNull(donut().copy(fn = AggFn.AVG).normalized())
        assertNull(donut().copy(fn = AggFn.RATIO, propId = "c").normalized())
        assertNotNull(donut(fn = AggFn.COUNT).normalized())
    }

    @Test
    fun aValueTileIgnoresAStrayGroupProperty() {
        val tile = AggTile("t", "v1", AggFn.COUNT, groupProp = "cat").normalized()!!
        assertEquals("", tile.groupProp)
        assertEquals(TileKind.AGG, tile.kind)
    }

    @Test
    fun aTileJsonWithoutKindIsAValueTileAndADonutRoundTrips() {
        val old = WidgetLayout.parse("""{"mode":"SECTIONS","tiles":[{"id":"t1","viewId":"v1","fn":"COUNT"}]}""")
        assertEquals(TileKind.AGG, old.tiles.single().kind)
        val layout = WidgetLayout.parse("""{"mode":"SECTIONS"}""").addTile(donut())!!
        val back = WidgetLayout.parse(WidgetLayout.encode(layout)).tiles.single()
        assertEquals(listOf(TileKind.DONUT, "cat", AggFn.SUM), listOf(back.kind, back.groupProp, back.fn))
    }
}
