package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.AggResult
import com.ysdoing.notioncal.core.data.view.AggTile
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import kotlin.math.ceil

internal val AGG_TILE_GAP = 4.dp
internal val AGG_TILE_PADDING = 8.dp
private val BAR_HEIGHT = 6.dp
private val COLOR_DOT = 8.dp
private val TILE_THREE_COLUMNS_MIN_WIDTH = 420.dp

/** 타일 하나가 그려질 값: 타일 설정, 계산 결과, 보기([board]가 null이면 등록이 지워진 보기). */
internal data class TileCell(val tile: AggTile, val result: AggResult, val board: ViewBoard? = null, val nowMillis: Long = 0L) {
    /** 값 아래 줄(막대·글자)이 필요한가: 목표, 일부만 계산, 값 없음, 보기 상태 안내가 있을 때. */
    val needsExtras: Boolean get() = result.progress != null || result.partial || result.number == null || (board != null && viewHasStatus(board, nowMillis))
}

/** 타일 줄의 격자: [visible]개를 [columns]열 × [rows]줄로 그린다. */
internal data class TileGrid(val columns: Int, val rows: Int, val visible: Int)

/** 그릴 타일([cells])과 격자, 줄 전체 [height]. [extras]면 모든 타일이 막대·글자 줄을 둔다(높이를 맞추려고). */
internal data class TileStripPlan(val cells: List<TileCell>, val grid: TileGrid, val height: Dp, val extras: Boolean)

/**
 * 타일 줄 격자(PRD 크기별 밀도: 2×2=1개·4×2=2개·4×4=4개·전체=6개). [rowsFit]은 줄 높이에 들어가는 타일 줄 수다.
 * 열은 좁은 위젯(2×2 단계)이면 1열, 아니면 위젯 폭으로(420dp 미만 2열, 그 이상 3열) 정하고 네 개는 2×2로 모은다.
 */
internal fun tileGrid(width: Dp, step: SizeStep, count: Int, rowsFit: Int): TileGrid {
    val cap = when (step) {
        SizeStep.COMPACT -> 1
        SizeStep.WIDE -> 2
        SizeStep.LARGE -> 4
        SizeStep.FULL -> 6
    }
    val byWidth = when {
        step == SizeStep.COMPACT -> 1
        width < TILE_THREE_COLUMNS_MIN_WIDTH -> 2
        else -> 3
    }
    val visible = minOf(count, cap, byWidth * rowsFit.coerceAtLeast(1))
    val columns = (if (visible == 4) minOf(byWidth, 2) else minOf(byWidth, visible)).coerceAtLeast(1)
    return TileGrid(columns, ceil(visible / columns.toFloat()).toInt(), visible)
}

/** 타일 하나의 높이. [extras]면 막대 + 목표·상태 글자 줄이 더 든다. */
internal fun tileHeight(m: ViewMetrics, extras: Boolean): Dp = m.tile + if (extras) m.tileExtras else 0.dp

/**
 * 타일 줄 계획. 줄에 줄 수 있는 높이 [room]에 맞춰 타일 줄 수를 정하고, 한 줄은 늘 둔다(높이가 모자라도 타일이 아예 없는 것보다 낫다).
 * 막대·글자 줄을 둘지는 실제로 그릴 타일만 보고 정한다(가려진 타일의 목표 때문에 보이는 타일이 커지지 않게, 두 번에 나눠 계산).
 */
internal fun planTileStrip(cells: List<TileCell>, m: ViewMetrics, size: DpSize, room: Dp): TileStripPlan {
    fun gridFor(extras: Boolean): TileGrid {
        val rowsFit = ((room + AGG_TILE_GAP) / (tileHeight(m, extras) + AGG_TILE_GAP)).toInt().coerceAtLeast(1)
        return tileGrid(size.width, sizeStep(size), cells.size, rowsFit)
    }
    val plain = gridFor(extras = false)
    val extras = cells.take(plain.visible).any { it.needsExtras }
    val grid = if (extras) gridFor(extras = true) else plain
    val height = if (grid.rows == 0) 0.dp else tileHeight(m, extras) * grid.rows + AGG_TILE_GAP * (grid.rows - 1)
    return TileStripPlan(cells.take(grid.visible), grid, height, extras)
}

internal fun fnName(fn: AggFn): Int = when (fn) {
    AggFn.COUNT -> R.string.layout_tile_fn_count
    AggFn.SUM -> R.string.layout_tile_fn_sum
    AggFn.AVG -> R.string.layout_tile_fn_avg
    AggFn.MIN -> R.string.layout_tile_fn_min
    AggFn.MAX -> R.string.layout_tile_fn_max
    AggFn.RATIO -> R.string.layout_tile_fn_ratio
    AggFn.STREAK -> R.string.layout_tile_fn_streak
}

private fun periodName(period: AggPeriod): Int? = when (period) {
    AggPeriod.ALL -> null
    AggPeriod.TODAY -> R.string.layout_tile_period_today
    AggPeriod.THIS_WEEK -> R.string.layout_tile_period_week
    AggPeriod.THIS_MONTH -> R.string.layout_tile_period_month
}

/** 타일 이름: 고른 이름, 없으면 '[기간] 속성 함수'(개수는 보기 이름, 연속 달성일은 기간 없이). 사라진 보기·속성은 기본 낱말로 쓴다. */
internal fun tileLabel(context: Context, tile: AggTile, board: ViewBoard?): String = tileLabel(context, tile, board?.name, board?.columnInfo.orEmpty())

/** [tileLabel]을 보기 이름과 열 정보만으로(설정 화면은 보기 행 없이 등록 정보만 가진다). */
internal fun tileLabel(context: Context, tile: AggTile, viewName: String?, columns: List<ViewColumn>): String {
    if (tile.label.isNotBlank()) return tile.label
    val fn = context.getString(fnName(tile.fn))
    val subject = if (tile.fn == AggFn.COUNT) {
        viewName?.ifBlank { null } ?: context.getString(R.string.layout_tile_view_default)
    } else {
        columns.firstOrNull { it.id == tile.propId }?.name?.ifBlank { null } ?: context.getString(R.string.layout_tile_prop_default)
    }
    val period = if (tile.fn == AggFn.STREAK) null else periodName(tile.period)
    return listOfNotNull(period?.let(context::getString), subject, fn).joinToString(" ")
}

/** 값 아래 한 줄: 보기 상태(미동기화·실패·오래됨·찾을 수 없음), 목표 글자, 일부만 계산, 보기·값 없음 안내를 ' · '로 잇는다. 없으면 빈 글자. */
private fun tileNote(context: Context, cell: TileCell): String {
    val status = cell.board?.let { viewStatusText(context, it, cell.nowMillis) }
    return listOfNotNull(
        status,
        cell.result.goalText,
        context.getString(R.string.layout_tile_partial).takeIf { cell.result.partial },
        context.getString(R.string.layout_tile_missing).takeIf { cell.board == null },
        context.getString(R.string.layout_tile_empty).takeIf { cell.board != null && cell.result.number == null && status == null },
    ).joinToString(" · ")
}

/**
 * 집계 타일 줄(AG-01·02). 타일은 라벨·큰 값·(목표가 있으면) 진행 막대·목표/상태 글자이고, 누르면 그 보기를 뷰어로 연다.
 * 막대는 색이 아니라 글자('목표 80%', '목표 12% 초과')로도 알린다(A11Y-03). [width]는 줄 안쪽 폭이다.
 */
@Composable
internal fun TileStrip(plan: TileStripPlan, look: LayoutLook, width: Dp) {
    val grid = plan.grid
    val cardWidth = (width - AGG_TILE_GAP * (grid.columns - 1)) / grid.columns
    val cardHeight = tileHeight(look.metrics, plan.extras)
    Column(GlanceModifier.fillMaxWidth().height(plan.height)) {
        plan.cells.chunked(grid.columns).forEachIndexed { rowIndex, rowCells ->
            if (rowIndex > 0) Spacer(GlanceModifier.height(AGG_TILE_GAP))
            Row(GlanceModifier.fillMaxWidth().height(cardHeight)) {
                rowCells.forEachIndexed { index, cell ->
                    if (index > 0) Spacer(GlanceModifier.width(AGG_TILE_GAP))
                    TileCard(cell, plan.extras, look, cardWidth, cardHeight)
                }
            }
        }
    }
}

@Composable
private fun TileCard(cell: TileCell, extras: Boolean, look: LayoutLook, width: Dp, height: Dp) {
    val context = LocalContext.current
    val m = look.metrics
    val palette = look.palette
    val label = tileLabel(context, cell.tile, cell.board)
    val value = cell.result.text
    val note = tileNote(context, cell)
    val description = if (note.isEmpty()) context.getString(R.string.layout_tile_description, label, value) else context.getString(R.string.layout_tile_description_note, label, value, note)
    // 타일 색(Phase 12c)은 라벨 앞 점과 진행 막대 채움에만 쓴다: 글자 색은 그대로라 대비가 바뀌지 않고, 색만으로 알리는 것도 없다(A11Y-03).
    val accent = NotionColors.hexFor(cell.tile.color)?.let { ColorProvider(Color(NotionColors.argb(it))) }
    Column(
        GlanceModifier.width(width).height(height).tile(palette).padding(AGG_TILE_PADDING)
            .clickable(actionStartActivity(viewerIntent(context, cell.tile.viewId)))
            .semantics {
                contentDescription = description
                testTag = "layoutTile"
            },
    ) {
        val labelStyle = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp)
        if (accent == null) {
            Text(label, style = labelStyle, maxLines = 1)
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(GlanceModifier.size(COLOR_DOT).background(accent).cornerRadius(COLOR_DOT / 2).semantics { testTag = "tileColor" }) {}
                Text(label, modifier = GlanceModifier.padding(start = 4.dp), style = labelStyle, maxLines = 1)
            }
        }
        Text(value, style = TextStyle(color = palette.text, fontSize = (m.valueSp * m.spFactor).sp, fontWeight = FontWeight.Bold), maxLines = 1)
        if (extras) {
            Spacer(GlanceModifier.height(2.dp))
            TileBar(cell.result.progress, width - AGG_TILE_PADDING * 2, palette, accent ?: palette.accent)
            Spacer(GlanceModifier.height(2.dp))
            Text(note, style = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp), maxLines = 1)
        }
    }
}

/** 목표 진행 막대: 칸 폭의 트랙 위에 채움. 목표가 없으면([progress]가 null) 자리만 비운다(타일 높이를 맞춘다). */
@Composable
private fun TileBar(progress: Float?, width: Dp, palette: WidgetPalette, fill: ColorProvider) {
    if (progress == null) {
        Spacer(GlanceModifier.size(width, BAR_HEIGHT))
        return
    }
    Box(GlanceModifier.size(width, BAR_HEIGHT).background(palette.track).cornerRadius(BAR_HEIGHT / 2).semantics { testTag = "tileProgress" }) {
        if (progress > 0f) Box(GlanceModifier.size(maxOf(width * progress, BAR_HEIGHT), BAR_HEIGHT).background(fill).cornerRadius(BAR_HEIGHT / 2)) {}
    }
}
