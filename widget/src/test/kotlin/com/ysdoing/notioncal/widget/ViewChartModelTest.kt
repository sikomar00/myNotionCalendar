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
        assertEquals(2, chartPointCap(ChartForm.LINE, 40.dp, 200.dp, m)) // 아무리 좁아도 둘은 그린다
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
    fun onlyTheFullSizeAddsAValueTableAndNotForHorizontalBarsOrNumbers() {
        val row = chartTableRowHeight(m)
        assertEquals(ChartSizing(400.dp - row * 5, 5), chartSizing(ChartForm.COLUMNS, SizeStep.FULL, 400.dp, 5, partial = false, m = m))
        assertEquals(8, chartSizing(ChartForm.DONUT, SizeStep.FULL, 400.dp, 12, partial = false, m = m)!!.tableRows) // 표는 8줄까지
        assertEquals(3, chartSizing(ChartForm.LINE, SizeStep.FULL, 200.dp, 6, partial = false, m = m)!!.tableRows) // 그림에 최소 120dp를 남긴다
        assertEquals(ChartSizing(400.dp, 0), chartSizing(ChartForm.ROWS, SizeStep.FULL, 400.dp, 5, partial = false, m = m))
        assertEquals(ChartSizing(400.dp, 0), chartSizing(ChartForm.NUMBER, SizeStep.FULL, 400.dp, 1, partial = false, m = m))
        assertEquals(ChartSizing(400.dp, 0), chartSizing(ChartForm.COLUMNS, SizeStep.LARGE, 400.dp, 5, partial = false, m = m))
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
    fun aPaneTooShortForTheChartHasNoPlanSoTheListTakesOver() {
        assertNull(chartPlanFor(board, spec, DpSize(501.dp, 458.dp), PaneSpec(height = 70.dp), m, 0.dp))
        assertNotNull(chartPlanFor(board, spec, DpSize(501.dp, 458.dp), PaneSpec(height = 300.dp), m, 0.dp))
        // 위에 상태 줄이 높이를 먹어도 마찬가지
        assertNull(chartPlanFor(board, spec, DpSize(234.dp, 100.dp), PaneSpec(), m, 24.dp))
    }
}
