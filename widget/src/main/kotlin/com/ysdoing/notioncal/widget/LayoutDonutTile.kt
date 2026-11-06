package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.view.chart.ChartSummary

// Phase 12c RC-04: 분류별 도넛 타일. 값 타일 격자 아래의 전체 폭 한 줄(고리 + 가운데 전체 값 + 범례)이고, 누르면 그 보기를 뷰어로 연다.

/** 도넛 타일의 범례 줄 수 상한(점 수 상한과 같다: 남은 분류는 '기타'로 합친다). */
internal const val DONUT_MAX_LEGEND = 5
private const val DONUT_MIN_LEGEND = 2

/** 그릴 도넛 타일 [cell]과 범례 줄 수, 줄 높이. 값이 없으면 범례 없이 안내 한 줄만 있는 낮은 카드다. */
internal data class DonutPlan(val cell: TileCell, val legendRows: Int, val height: Dp)

/** 카드 높이: 위아래 안쪽 여백 + 이름 줄 + 범례 줄들. */
internal fun donutHeight(m: ViewMetrics, legendRows: Int): Dp = AGG_TILE_PADDING * 2 + m.tableHeader + chartTableRowHeight(m) * legendRows

/** 가장 낮은 도넛 줄 높이(범례 두 줄). 값 타일 줄이 이만큼은 남겨 줘야 도넛이 들어간다. */
internal fun donutMinHeight(m: ViewMetrics): Dp = donutHeight(m, DONUT_MIN_LEGEND)

/** [room]에 들어가는 만큼 범례를 줄여 계획한다. 2×2이거나 범례 두 줄도 안 들어가면 null(도넛은 건너뛴다). */
internal fun planDonut(cell: TileCell, m: ViewMetrics, size: DpSize, room: Dp): DonutPlan? {
    if (sizeStep(size) == SizeStep.COMPACT) return null
    val points = cell.donut?.points?.size ?: 0
    if (points == 0) return DonutPlan(cell, 0, m.tile).takeIf { room >= m.tile }
    val fit = ((room - AGG_TILE_PADDING * 2 - m.tableHeader) / chartTableRowHeight(m)).toInt()
    val rows = minOf(points, DONUT_MAX_LEGEND, fit)
    return DonutPlan(cell, rows, donutHeight(m, rows)).takeIf { rows >= minOf(points, DONUT_MIN_LEGEND) }
}

@Composable
internal fun DonutTile(plan: DonutPlan, look: LayoutLook, width: Dp) {
    val context = LocalContext.current
    val m = look.metrics
    val cell = plan.cell
    val result = cell.donut
    val label = tileLabel(context, cell.tile, cell.board)
    val status = cell.board?.let { viewStatusText(context, it, cell.nowMillis) }
    val partial = context.getString(R.string.layout_tile_partial).takeIf { result?.partial == true }
    val note = when {
        cell.board == null -> context.getString(R.string.layout_tile_missing)
        result == null -> context.getString(R.string.layout_donut_unavailable)
        result.points.isEmpty() -> context.getString(R.string.layout_tile_empty)
        else -> null
    }
    val head = listOfNotNull(label, status, partial).joinToString(" · ")
    val spoken = result?.takeIf { note == null }?.let(ChartSummary::sentence) ?: note.orEmpty()
    val subtle = TextStyle(color = look.palette.subtle, fontSize = (m.subSp * m.spFactor).sp)
    Column(
        GlanceModifier.fillMaxWidth().height(plan.height).tile(look.palette).padding(AGG_TILE_PADDING)
            .clickable(actionStartActivity(viewerIntent(context, cell.tile.viewId)))
            .semantics {
                contentDescription = context.getString(R.string.layout_tile_description, label, spoken)
                testTag = "layoutDonutTile"
            },
    ) {
        Text(head, modifier = GlanceModifier.fillMaxWidth(), style = subtle, maxLines = 1)
        if (note != null) {
            Text(note, modifier = GlanceModifier.fillMaxWidth().semantics { testTag = "layoutDonutNote" }, style = subtle, maxLines = 1)
        } else {
            DonutWithLegend(result!!, width - AGG_TILE_PADDING * 2, plan.height - AGG_TILE_PADDING * 2 - m.tableHeader, plan.legendRows, ViewLook(m, look.palette))
        }
    }
}
