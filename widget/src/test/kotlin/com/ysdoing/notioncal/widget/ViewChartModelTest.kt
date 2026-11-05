package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import com.ysdoing.notioncal.core.data.view.chart.ChartGroup
import com.ysdoing.notioncal.core.data.view.chart.ChartSpec
import com.ysdoing.notioncal.core.data.view.chart.ChartType
import com.ysdoing.notioncal.core.data.view.chart.ChartValue
import com.ysdoing.notioncal.core.data.view.chart.GroupKind
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 12b VR-CHT 차트의 크기 단계별 모양·점 수·높이 나누기(순수). */
class ViewChartModelTest {
    private val m = viewMetrics(1f, 1f)

    @Test
    fun theFormFollowsTheChartTypeAndTheSizeStep() {
        fun form(step: SizeStep, type: ChartType) = chartFormFor(step, type)
        // 2×2: 숫자 또는 도넛, 꺾은선은 그릴 폭이 없어 숫자
        assertEquals(listOf(ChartForm.DONUT, ChartForm.DONUT, ChartForm.NUMBER, ChartForm.DONUT, ChartForm.NUMBER), ChartType.entries.map { form(SizeStep.COMPACT, it) }) // 열거 순서: column·bar·line·donut·number
        // 4×2 이상은 차트 그대로
        for (step in listOf(SizeStep.WIDE, SizeStep.LARGE, SizeStep.FULL)) {
            assertEquals(
                listOf(ChartForm.COLUMNS, ChartForm.ROWS, ChartForm.LINE, ChartForm.DONUT, ChartForm.NUMBER),
                listOf(ChartType.COLUMN, ChartType.BAR, ChartType.LINE, ChartType.DONUT, ChartType.NUMBER).map { form(step, it) },
            )
        }
    }

    @Test
    fun thePointCapFollowsTheWidthTheHeightAndGlancesTenChildLimit() {
        assertEquals(10, chartPointCap(ChartForm.COLUMNS, 480.dp, 200.dp, m)) // 폭은 13칸이지만 한 줄 자식은 10개까지
        assertEquals(8, chartPointCap(ChartForm.COLUMNS, 300.dp, 200.dp, m))
        assertEquals(2, chartPointCap(ChartForm.COLUMNS, 40.dp, 200.dp, m)) // 아무리 좁아도 둘은 그린다
        assertEquals(120, chartPointCap(ChartForm.LINE, 40.dp, 200.dp, m)) // 꺾은선은 그림 한 장이라 폭·자식 한도와 무관하다
        val rows = chartRowHeight(m)
        assertEquals(3, chartPointCap(ChartForm.ROWS, 300.dp, rows * 3 + 1.dp, m))
        assertEquals(10, chartPointCap(ChartForm.ROWS, 300.dp, 900.dp, m))
        assertEquals(1, chartPointCap(ChartForm.ROWS, 300.dp, 5.dp, m))
        assertEquals(8, chartPointCap(ChartForm.DONUT, 300.dp, 200.dp, m))
        assertEquals(1, chartPointCap(ChartForm.NUMBER, 300.dp, 200.dp, m))
    }

    @Test
    fun theWholeBodyGoesToTheChartExceptThePartialLine() {
        assertEquals(ChartSizing(100.dp, 0), chartSizing(ChartForm.COLUMNS, SizeStep.WIDE, 100.dp, 4, partial = false, m = m))
        assertEquals(ChartSizing(100.dp - m.tableHeader, 0), chartSizing(ChartForm.COLUMNS, SizeStep.WIDE, 100.dp, 4, partial = true, m = m))
    }

    @Test
    fun onlyTheFullSizeAddsAValueTableAndOnlyForVerticalBars() {
        val row = chartTableRowHeight(m)
        assertEquals(ChartSizing(400.dp - row * 5, 5), chartSizing(ChartForm.COLUMNS, SizeStep.FULL, 400.dp, 5, partial = false, m = m))
        assertEquals(8, chartSizing(ChartForm.COLUMNS, SizeStep.FULL, 400.dp, 12, partial = false, m = m)!!.tableRows) // 표는 8줄까지
        assertEquals(3, chartSizing(ChartForm.COLUMNS, SizeStep.FULL, 200.dp, 6, partial = false, m = m)!!.tableRows) // 그림에 최소 120dp를 남긴다
        // 도넛은 옆 범례가, 가로 막대는 줄마다 이름·값이, 꺾은선은 점이 많아 표가 맞지 않는다
        assertEquals(0, chartSizing(ChartForm.DONUT, SizeStep.FULL, 400.dp, 5, partial = false, m = m)!!.tableRows)
        assertEquals(ChartSizing(400.dp, 0), chartSizing(ChartForm.LINE, SizeStep.FULL, 400.dp, 5, partial = false, m = m))
        assertEquals(ChartSizing(400.dp, 0), chartSizing(ChartForm.ROWS, SizeStep.FULL, 400.dp, 5, partial = false, m = m))
        assertEquals(ChartSizing(400.dp, 0), chartSizing(ChartForm.NUMBER, SizeStep.FULL, 400.dp, 1, partial = false, m = m))
        assertEquals(ChartSizing(400.dp, 0), chartSizing(ChartForm.COLUMNS, SizeStep.LARGE, 400.dp, 5, partial = false, m = m))
    }

    @Test
    fun aDonutGetsALegendBesideItExceptAtTwoByTwoOrWhenNotionTurnedItOff() {
        val row = chartTableRowHeight(m)
        assertEquals(5, chartSizing(ChartForm.DONUT, SizeStep.LARGE, 400.dp, 5, partial = false, m = m)!!.legendRows)
        assertEquals(8, chartSizing(ChartForm.DONUT, SizeStep.LARGE, 400.dp, 12, partial = false, m = m)!!.legendRows) // 조각은 8개까지
        assertEquals(3, chartSizing(ChartForm.DONUT, SizeStep.WIDE, row * 3 + 1.dp, 8, partial = false, m = m)!!.legendRows) // 높이에 들어가는 만큼
        assertEquals(2, chartSizing(ChartForm.DONUT, SizeStep.WIDE, 60.dp, 8, partial = false, m = m)!!.legendRows) // 그림이 읽히는 가장 낮은 높이에서도 2줄
        assertEquals(0, chartSizing(ChartForm.DONUT, SizeStep.COMPACT, 400.dp, 5, partial = false, m = m)!!.legendRows)
        assertEquals(0, chartSizing(ChartForm.DONUT, SizeStep.LARGE, 400.dp, 5, partial = false, m = m, legend = false)!!.legendRows)
        assertEquals(0, chartSizing(ChartForm.COLUMNS, SizeStep.LARGE, 400.dp, 5, partial = false, m = m)!!.legendRows)
    }

    @Test
    fun verticalBarsNeedTheirTextLinesSoABiggerFontNeedsAMoreHeight() {
        val big = viewMetrics(1.5f, 1.3f)
        // 값 글자 줄 + 이름 줄 + 막대 최소 높이: 글꼴이 크면 56dp로는 모자란다
        assertNull(chartSizing(ChartForm.COLUMNS, SizeStep.WIDE, 70.dp, 4, partial = false, m = big))
        assertNotNull(chartSizing(ChartForm.ROWS, SizeStep.WIDE, 70.dp, 4, partial = false, m = big)) // 가로 막대는 줄 높이만 맞으면 된다
        assertNotNull(chartSizing(ChartForm.COLUMNS, SizeStep.WIDE, 120.dp, 4, partial = false, m = big))
    }

    @Test
    fun aBodyTooShortToReadTheChartHasNoSizing() {
        assertNull(chartSizing(ChartForm.COLUMNS, SizeStep.WIDE, 50.dp, 4, partial = false, m = m))
        assertNull(chartSizing(ChartForm.COLUMNS, SizeStep.WIDE, 70.dp, 4, partial = true, m = m)) // 일부 행 줄을 빼면 모자란다
        assertNotNull(chartSizing(ChartForm.COLUMNS, SizeStep.WIDE, 60.dp, 4, partial = false, m = m))
    }

    private val title = ViewColumn("t", "이름", "title")
    private val category = ViewColumn("cat", "분류", "select", listOf(ViewChoice("o1", "식비", color = "red"), ViewChoice("o2", "교통", color = "blue")))
    private val board = ViewBoard(
        "v", "지출", "chart", listOf("이름"),
        listOf(
            ViewRow("1", "", emptyList(), typed = mapOf("cat" to JsonPrimitive("o1"))), ViewRow("2", "", emptyList(), typed = mapOf("cat" to JsonPrimitive("o1"))),
            ViewRow("3", "", emptyList(), typed = mapOf("cat" to JsonPrimitive("o2"))),
        ),
        1L, null, listOf(title, category),
    )
    private val spec = ChartSpec(ChartType.COLUMN, ChartGroup("cat", GroupKind.OPTION), ChartValue(AggFn.COUNT))

    @Test
    fun thePlanComputesTheValuesAndSizesThemForTheStep() {
        val plan = chartPlanFor(board, spec, DpSize(501.dp, 213.dp), PaneSpec(), m, 0.dp)!!
        assertEquals(ChartForm.COLUMNS, plan.form)
        assertEquals(listOf("식비" to 2.0, "교통" to 1.0), plan.result.points.map { it.label to it.value })
        assertEquals(501.dp - PADDING_H * 2, plan.width)
        // 2×2에서는 같은 세로 막대 차트가 도넛이 된다
        assertEquals(ChartForm.DONUT, chartPlanFor(board, spec, DpSize(234.dp, 213.dp), PaneSpec(), m, 0.dp)!!.form)
    }

    @Test
    fun aLineChartDrawnAsOneNumberAtTwoByTwoIsComputedAsANumberChart() {
        val line = ChartSpec(ChartType.LINE, ChartGroup("cat", GroupKind.OPTION), ChartValue(AggFn.COUNT))
        val plan = chartPlanFor(board, line, DpSize(234.dp, 213.dp), PaneSpec(), m, 0.dp)!!
        assertEquals(ChartForm.NUMBER, plan.form)
        assertEquals(ChartType.NUMBER, plan.result.spec.type) // 읽는 문장이 그리는 값과 같도록
        assertNull(plan.result.spec.group)
        assertEquals(listOf("3"), plan.result.points.map { it.text })
    }

    @Test
    fun aPartialBoardLeavesRoomForItsNoteWhenCountingHorizontalBars() {
        val many = ViewColumn("cat", "분류", "select", (1..8).map { ViewChoice("o$it", "옵션$it") })
        val rows = (1..8).map { ViewRow("$it", "", emptyList(), typed = mapOf("cat" to JsonPrimitive("o$it"))) }
        val partial = board.copy(columnInfo = listOf(title, many), rows = rows, rowsComplete = false)
        val bars = ChartSpec(ChartType.BAR, ChartGroup("cat", GroupKind.OPTION), ChartValue(AggFn.COUNT))
        val plan = chartPlanFor(partial, bars, DpSize(501.dp, 213.dp), PaneSpec(), m, 0.dp)!!
        // 막대 줄이 차트 높이(일부 행 줄을 뺀)에 다 들어간다: 줄 수를 전체 높이로 세면 마지막 줄이 안내 줄과 겹친다
        assertEquals(true, chartRowHeight(m) * plan.result.points.size <= plan.sizing.chartHeight)
        assertEquals(true, plan.result.points.size < 6) // 전체 높이 165dp면 6줄이지만 안내 줄만큼 줄어 5줄(넘치는 것은 '기타')
    }

    @Test
    fun aPaneTooShortForTheChartHasNoPlanSoTheListTakesOver() {
        assertNull(chartPlanFor(board, spec, DpSize(501.dp, 458.dp), PaneSpec(height = 70.dp), m, 0.dp))
        assertNotNull(chartPlanFor(board, spec, DpSize(501.dp, 458.dp), PaneSpec(height = 300.dp), m, 0.dp))
        // 위에 상태 줄이 높이를 먹어도 마찬가지
        assertNull(chartPlanFor(board, spec, DpSize(234.dp, 100.dp), PaneSpec(), m, 24.dp))
    }
}
