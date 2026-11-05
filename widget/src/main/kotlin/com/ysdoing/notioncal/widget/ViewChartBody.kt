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
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.chart.ChartCompute
import com.ysdoing.notioncal.core.data.view.chart.ChartConfig
import com.ysdoing.notioncal.core.data.view.chart.ChartParse
import com.ysdoing.notioncal.core.data.view.chart.ChartPoint
import com.ysdoing.notioncal.core.data.view.chart.ChartResult
import com.ysdoing.notioncal.core.data.view.chart.ChartSummary
import com.ysdoing.notioncal.core.data.view.chart.sharePercent

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
            ChartForm.NUMBER -> NumberTile(result, plan.width, plan.sizing.chartHeight, look)
            ChartForm.COLUMNS -> ColumnBars(result, plan, look)
            ChartForm.ROWS -> RowBars(result, plan, look)
            ChartForm.DONUT -> DonutChart(result, plan, look)
            ChartForm.LINE -> LineChart(result, plan, look)
        }
        if (plan.sizing.tableRows > 0) ValueTable(result, plan.sizing.tableRows, look)
        if (result.partial) Text(context.getString(R.string.view_chart_partial), modifier = GlanceModifier.fillMaxWidth().semantics { testTag = "chartPartial" }, style = look.sub, maxLines = 1)
    }
}

private fun barColor(point: ChartPoint) = ColorProvider(Color(NotionColors.argb(point.colorHex)))

/** 값 하나: 큰 글자 + 무엇을 센 값인지. 글자는 칸 높이에 들어가도록 줄인다. */
@Composable
private fun NumberTile(result: ChartResult, width: Dp, height: Dp, look: ViewLook) {
    val m = look.m
    val textScale = minOf(m.systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
    // 실제로 그려지는 글자 크기는 sp × 글꼴 배율이다: 높이와 폭(글자 수) 중 좁은 쪽에 맞춰 칸을 넘치지 않게 한다.
    val byHeight = (height - m.tableHeader - 8.dp).value / (WidgetMetrics.TEXT_BOX * textScale)
    val byWidth = (width - 16.dp).value / (result.totalText.length.coerceAtLeast(MIN_CHARS) * CHAR_WIDTH * textScale)
    val valueSp = minOf(m.valueSp * NUMBER_SCALE, byHeight, byWidth).coerceAtLeast(MIN_NUMBER_SP) * m.spFactor
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
    val plot = (plan.sizing.chartHeight - line * 2).coerceAtLeast(CHART_MIN_PLOT)
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

/**
 * 도넛: 고리 그림([DonutPicture])의 한가운데에 전체 값을 글자로 얹고, 범례 줄이 있으면 오른쪽에 색 점 · 이름 · 값 · 비율을 세운다.
 * 범례가 없으면(2×2, 노션이 범례를 껐을 때) 도넛이 가운데에 선다.
 */
@Composable
private fun DonutChart(result: ChartResult, plan: ChartPlan, look: ViewLook) {
    val height = plan.sizing.chartHeight
    val legendRows = plan.sizing.legendRows
    val side = if (legendRows == 0) minOf(plan.width, height) else minOf(height, plan.width * DONUT_SHARE)
    val m = look.m
    val hole = side * (1 - 2 * DONUT_RING)
    val textScale = minOf(m.systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
    // 구멍에 글자가 들어가도록: 높이와 폭(글자 수) 중 좁은 쪽에 맞춘다.
    val sp = minOf(hole.value / (WidgetMetrics.TEXT_BOX * textScale), hole.value / (result.totalText.length.coerceAtLeast(MIN_CHARS) * CHAR_WIDTH * textScale), m.valueSp * 1.2f).coerceAtLeast(8f) * m.spFactor
    Row(
        GlanceModifier.fillMaxWidth().height(height), verticalAlignment = Alignment.CenterVertically,
        horizontalAlignment = if (legendRows == 0) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Box(GlanceModifier.size(side), contentAlignment = Alignment.Center) {
            DonutPicture(result.points, side)
            Text(result.totalText, style = TextStyle(color = look.palette.text, fontSize = sp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
        }
        if (legendRows > 0) Box(GlanceModifier.defaultWeight().padding(start = 12.dp)) { ValueTable(result, legendRows, look) }
    }
}

private const val DONUT_SHARE = 0.4f
private const val DONUT_RING = 0.2f // 고리 두께 비율(도넛을 그리는 파일의 RING_RATIO와 같다)
private const val MIN_CHARS = 3
private const val CHAR_WIDTH = 0.62f

/** 꺾은선: 선 그림([LinePicture]) 위 왼쪽에 가장 큰 값, 아래에 첫·가운데·마지막 이름. 값 전부는 낭독 문장에 있다. */
@Composable
private fun LineChart(result: ChartResult, plan: ChartPlan, look: ViewLook) {
    val label = look.m.tableHeader
    val plot = (plan.sizing.chartHeight - label).coerceAtLeast(CHART_MIN_PLOT)
    val points = result.points
    val peak = points.maxByOrNull { it.value }
    val edge = TextStyle(color = look.palette.text, fontSize = look.sub.fontSize)
    Column(GlanceModifier.fillMaxWidth().height(plan.sizing.chartHeight)) {
        Box(GlanceModifier.fillMaxWidth().height(plot)) {
            LinePicture(points.map { if (it.missing) Double.NaN else it.value }, ChartCompute.accent(result.spec), plan.width, plot)
            if (peak != null) Text(peak.text, modifier = GlanceModifier.semantics { testTag = "chartPeak" }, style = look.sub, maxLines = 1)
        }
        Row(GlanceModifier.fillMaxWidth().height(label)) {
            Text(points.firstOrNull()?.label.orEmpty(), modifier = GlanceModifier.defaultWeight(), style = edge, maxLines = 1)
            if (points.size >= MIN_MIDDLE) Text(points[points.size / 2].label, modifier = GlanceModifier.defaultWeight(), style = edge.copy(textAlign = TextAlign.Center), maxLines = 1)
            if (points.size >= 2) Text(points.last().label, modifier = GlanceModifier.defaultWeight(), style = edge.copy(textAlign = TextAlign.End), maxLines = 1)
        }
    }
}

private const val MIN_MIDDLE = 3

/** 가로 막대: 이름 · 막대 · 값 한 줄씩. 막대 길이는 가장 큰 값 기준 비율이다. */
@Composable
private fun RowBars(result: ChartResult, plan: ChartPlan, look: ViewLook) {
    val rowHeight = chartRowHeight(look.m)
    val label = plan.width * LABEL_SHARE
    val track = (plan.width - label - VALUE_WIDTH - 8.dp).coerceAtLeast(CHART_MIN_PLOT)
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
    Column(GlanceModifier.fillMaxWidth()) {
        result.points.take(rows).forEach { point ->
            Row(GlanceModifier.fillMaxWidth().height(chartTableRowHeight(look.m)), verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(8.dp).background(barColor(point)).cornerRadius(4.dp)) {}
                Text(point.label, modifier = GlanceModifier.defaultWeight().padding(start = 6.dp), style = look.cell, maxLines = 1)
                Text(point.text, style = look.cell, maxLines = 1)
                Text(result.sharePercent(point)?.let { "$it%" }.orEmpty(), modifier = GlanceModifier.width(SHARE_WIDTH), style = look.sub.copy(textAlign = TextAlign.End), maxLines = 1)
            }
        }
    }
}

private val SHARE_WIDTH = 40.dp
