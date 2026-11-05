package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.chart.ChartConfig
import com.ysdoing.notioncal.core.data.view.chart.ChartParse
import com.ysdoing.notioncal.core.data.view.chart.ChartPoint
import com.ysdoing.notioncal.core.data.view.chart.ChartResult
import com.ysdoing.notioncal.core.data.view.chart.ChartSummary
import kotlin.math.roundToInt

// Phase 12b VR-CHT: 노션 차트 보기를 그린다. 숫자·가로 막대·세로 막대·값 표는 비트맵 없이 Glance 상자로 그려 글자와 눌림·낭독이 정확하고 가볍다.
// 차트 전체를 누르면 뷰어(VR-02)가 열리고, 낭독은 이름과 값을 함께 읽는 한 문장이다(A11Y-04).

/**
 * 차트 보기면 그릴 수 있을 때 차트를 그리고 true를 돌려준다. 이 노션 차트 설정을 못 그리거나 그릴 높이가 없으면 false이고,
 * 못 그리는 설정이면 한 줄 안내를 먼저 그린다(호출한 쪽이 표·목록으로 이어 그린다).
 */
@Composable
internal fun ChartOrNote(board: ViewBoard, size: DpSize, pane: PaneSpec, look: ViewLook, aboveHeight: Dp): Boolean {
    val parse = ChartConfig.parse(board.viewConfig, board.columnInfo)
    if (parse is ChartParse.Unsupported) {
        ChartNote(R.string.view_chart_unsupported, look)
        return false
    }
    val plan = chartPlanFor(board, (parse as ChartParse.Ok).spec, size, pane, look.m, aboveHeight) ?: return false
    ChartBody(board, plan, look)
    return true
}

@Composable
private fun ChartNote(message: Int, look: ViewLook) {
    Text(
        LocalContext.current.getString(message), modifier = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp).semantics { testTag = "chartNote" },
        style = look.sub, maxLines = 2,
    )
}

@Composable
private fun ChartBody(board: ViewBoard, plan: ChartPlan, look: ViewLook) {
    val context = LocalContext.current
    val result = plan.result
    Column(
        GlanceModifier.fillMaxWidth().clickable(actionStartActivity(viewerIntent(context, board.viewId)))
            .semantics { contentDescription = ChartSummary.sentence(result); testTag = "chart" },
    ) {
        when (plan.form) {
            ChartForm.NUMBER -> NumberTile(result, plan.sizing.chartHeight, look)
            ChartForm.COLUMNS, ChartForm.LINE -> ColumnBars(result, plan, look)
            ChartForm.ROWS, ChartForm.DONUT -> RowBars(result, plan, look)
        }
        if (plan.sizing.tableRows > 0) ValueTable(result, plan.sizing.tableRows, look)
        if (result.partial) Text(context.getString(R.string.view_chart_partial), modifier = GlanceModifier.fillMaxWidth().semantics { testTag = "chartPartial" }, style = look.sub, maxLines = 1)
    }
}

private fun barColor(point: ChartPoint) = ColorProvider(Color(NotionColors.argb(point.colorHex)))

/** 값 하나: 큰 글자 + 무엇을 센 값인지. 글자는 칸 높이에 들어가도록 줄인다. */
@Composable
private fun NumberTile(result: ChartResult, height: Dp, look: ViewLook) {
    val m = look.m
    val textScale = minOf(m.systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
    val maxSp = (height - m.tableHeader - 8.dp).value / (WidgetMetrics.TEXT_BOX * textScale)
    val valueSp = minOf(m.valueSp * NUMBER_SCALE, maxSp).coerceAtLeast(MIN_NUMBER_SP) * m.spFactor
    Box(GlanceModifier.fillMaxWidth().height(height).tile(look.palette), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(result.totalText, style = TextStyle(color = look.palette.text, fontSize = valueSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            Text(result.valueLabel, style = look.sub, maxLines = 1)
        }
    }
}

private const val NUMBER_SCALE = 1.8f
private const val MIN_NUMBER_SP = 10f

/** 세로 막대: 칸마다 위에 값, 아래에 이름. 막대 높이는 가장 큰 값을 그림 영역에 맞춘 비율이다(0이면 1dp 선). */
@Composable
private fun ColumnBars(result: ChartResult, plan: ChartPlan, look: ViewLook) {
    val points = result.points
    val cell = plan.width / points.size.coerceAtLeast(1)
    val line = look.m.tableHeader
    val plot = (plan.sizing.chartHeight - line * 2).coerceAtLeast(MIN_PLOT)
    val barWidth = (cell - 8.dp).coerceIn(6.dp, 32.dp)
    val max = points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    val center = TextStyle(color = look.palette.subtle, fontSize = look.sub.fontSize, textAlign = TextAlign.Center)
    Row(GlanceModifier.fillMaxWidth().height(plan.sizing.chartHeight), verticalAlignment = Alignment.Bottom) {
        points.forEach { point ->
            Column(GlanceModifier.width(cell).height(plan.sizing.chartHeight), verticalAlignment = Alignment.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                Text(point.text, modifier = GlanceModifier.width(cell), style = center, maxLines = 1)
                val barHeight = if (point.value > 0) (plot * (point.value / max).toFloat()).coerceAtLeast(2.dp) else 1.dp
                Box(GlanceModifier.width(barWidth).height(barHeight).background(barColor(point)).cornerRadius(3.dp).semantics { testTag = "chartBar" }) {}
                Text(point.label, modifier = GlanceModifier.width(cell), style = center.copy(color = look.palette.text), maxLines = 1)
            }
        }
    }
}

private val MIN_PLOT = 8.dp

/** 가로 막대: 이름 · 막대 · 값 한 줄씩. 막대 길이는 가장 큰 값 기준 비율이다. */
@Composable
private fun RowBars(result: ChartResult, plan: ChartPlan, look: ViewLook) {
    val rowHeight = chartRowHeight(look.m)
    val label = plan.width * LABEL_SHARE
    val track = (plan.width - label - VALUE_WIDTH - 8.dp).coerceAtLeast(MIN_PLOT)
    val max = result.points.maxOfOrNull { it.value }?.takeIf { it > 0 } ?: 1.0
    Column(GlanceModifier.fillMaxWidth().height(plan.sizing.chartHeight)) {
        result.points.forEach { point ->
            Row(GlanceModifier.fillMaxWidth().height(rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(point.label, modifier = GlanceModifier.width(label), style = look.cell, maxLines = 1)
                Box(GlanceModifier.width(track).height(rowHeight), contentAlignment = Alignment.CenterStart) {
                    val length = if (point.value > 0) (track * (point.value / max).toFloat()).coerceAtLeast(2.dp) else 1.dp
                    Box(GlanceModifier.width(length).height(BAR_THICKNESS).background(barColor(point)).cornerRadius(3.dp).semantics { testTag = "chartBar" }) {}
                }
                Text(point.text, modifier = GlanceModifier.width(VALUE_WIDTH).padding(start = 6.dp), style = look.sub, maxLines = 1)
            }
        }
    }
}

private const val LABEL_SHARE = 0.28f
private val VALUE_WIDTH = 64.dp
private val BAR_THICKNESS = 10.dp

/** 전체 크기의 값 표: 색 점 · 이름 · 값 · 비율(개수·합계일 때만, 점들의 합에 대한 몫). */
@Composable
private fun ValueTable(result: ChartResult, rows: Int, look: ViewLook) {
    val sum = result.points.sumOf { it.value }
    val shares = result.spec.value.fn.let { (it == AggFn.COUNT || it == AggFn.SUM) && sum > 0 }
    Column(GlanceModifier.fillMaxWidth().padding(top = 4.dp)) {
        result.points.take(rows).forEach { point ->
            Row(GlanceModifier.fillMaxWidth().height(chartTableRowHeight(look.m)), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(8.dp).background(barColor(point)).cornerRadius(4.dp)) {}
                Text(point.label, modifier = GlanceModifier.defaultWeight().padding(start = 6.dp), style = look.cell, maxLines = 1)
                Text(point.text, style = look.cell, maxLines = 1)
                Text(if (shares) "${(point.value / sum * PERCENT).roundToInt()}%" else "", modifier = GlanceModifier.width(SHARE_WIDTH), style = look.sub.copy(textAlign = TextAlign.End), maxLines = 1)
            }
        }
    }
}

private const val PERCENT = 100
private val SHARE_WIDTH = 40.dp
