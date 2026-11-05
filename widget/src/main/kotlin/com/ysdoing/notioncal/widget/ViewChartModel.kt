package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.chart.ChartCompute
import com.ysdoing.notioncal.core.data.view.chart.ChartResult
import com.ysdoing.notioncal.core.data.view.chart.ChartSpec
import com.ysdoing.notioncal.core.data.view.chart.ChartType

// Phase 12b VR-CHT: 차트 보기의 크기 단계별 모양과 높이 나누기(순수). 값 계산은 :core:data의 ChartCompute가, 그림은 ViewChartBody가 맡는다.

/** 위젯이 그리는 차트 모양. [COLUMNS]는 세로 막대, [ROWS]는 가로 막대(이름·막대·값 한 줄씩), [NUMBER]는 값 하나다. */
internal enum class ChartForm { NUMBER, COLUMNS, ROWS, DONUT, LINE }

/**
 * 노션 `chart_type`과 크기 단계로 모양을 고른다(PRD §5.2 밀도표): 2×2는 숫자 또는 도넛, 4×2 이상은 차트 그대로.
 * 2×2의 꺾은선은 그릴 폭이 없어 값 하나(전체 값)로 줄인다.
 */
internal fun chartFormFor(step: SizeStep, type: ChartType): ChartForm = when {
    type == ChartType.NUMBER -> ChartForm.NUMBER
    step == SizeStep.COMPACT -> if (type == ChartType.LINE) ChartForm.NUMBER else ChartForm.DONUT
    type == ChartType.COLUMN -> ChartForm.COLUMNS
    type == ChartType.BAR -> ChartForm.ROWS
    type == ChartType.LINE -> ChartForm.LINE
    else -> ChartForm.DONUT
}

/** 차트 한 장이 쓰는 높이 나누기: 그림 영역 [chartHeight]과 그 아래 값 표 줄 수 [tableRows](전체 크기만). */
internal data class ChartSizing(val chartHeight: Dp, val tableRows: Int)

/** 계산한 값과 모양·크기. [width]는 차트 폭(위젯 안쪽)이다. */
internal data class ChartPlan(val result: ChartResult, val form: ChartForm, val sizing: ChartSizing, val width: Dp)

// Glance 컨테이너 자식은 10개까지라 한 줄·한 열에 점을 그 이상 두지 못한다(PRD §13). 도넛은 조각을 가르기 어려워 더 적게.
private const val MAX_BARS = 10
private const val MAX_SLICES = 8
private const val MAX_TABLE_ROWS = 8
private val BAR_CELL_MIN = 36.dp
private val MIN_CHART = 56.dp
private val FULL_MIN_CHART = 120.dp

internal fun chartRowHeight(m: ViewMetrics): Dp = m.tableHeader + 6.dp

internal fun chartTableRowHeight(m: ViewMetrics): Dp = m.tableHeader + 4.dp

/** 모양별로 그릴 수 있는 점 수. 넘치면 [ChartCompute.compute]가 뒤쪽을 '기타'로 합친다. */
internal fun chartPointCap(form: ChartForm, width: Dp, body: Dp, m: ViewMetrics): Int = when (form) {
    ChartForm.NUMBER -> 1
    ChartForm.COLUMNS, ChartForm.LINE -> minOf(MAX_BARS, (width / BAR_CELL_MIN).toInt()).coerceAtLeast(2)
    ChartForm.ROWS -> minOf(MAX_BARS, (body / chartRowHeight(m)).toInt()).coerceAtLeast(1)
    ChartForm.DONUT -> MAX_SLICES
}

/**
 * 머리·줄을 뺀 높이 [body]를 나눈다. 행을 일부만 받았으면 '일부 행만 반영' 한 줄을 먼저 빼고, 전체 크기는 막대·도넛·선 아래에 값 표를 둔다
 * (가로 막대는 줄마다 이름·값이 있어 표가 필요 없다). 그림이 읽힐 높이([MIN_CHART])가 안 남으면 null이고 위젯은 표·목록으로 대체한다.
 */
internal fun chartSizing(form: ChartForm, step: SizeStep, body: Dp, points: Int, partial: Boolean, m: ViewMetrics): ChartSizing? {
    var available = body - (if (partial) m.tableHeader else 0.dp)
    var rows = 0
    if (step == SizeStep.FULL && (form == ChartForm.COLUMNS || form == ChartForm.LINE || form == ChartForm.DONUT)) {
        rows = minOf(points, MAX_TABLE_ROWS, ((available - FULL_MIN_CHART) / chartTableRowHeight(m)).toInt().coerceAtLeast(0))
        available -= chartTableRowHeight(m) * rows
    }
    return if (available < MIN_CHART) null else ChartSizing(available, rows)
}

/**
 * 이 차트 보기를 그릴 계획. 보기 행으로 값을 계산하고([ChartCompute]) 크기 단계로 모양·높이를 정한다. 그릴 높이가 없으면 null.
 * [pane]은 탭·섹션 칸이면 그 칸의 높이를, [aboveHeight]는 머리줄 아래 이미 그려진 줄(상태·되돌리기·프리셋)의 높이를 준다.
 */
internal fun chartPlanFor(board: ViewBoard, spec: ChartSpec, size: DpSize, pane: PaneSpec, m: ViewMetrics, aboveHeight: Dp): ChartPlan? {
    val total = pane.height ?: (size.height - VIEW_PADDING_V * 2)
    val body = paneBodyHeight(total, progressOf(board, size) != null, m, aboveHeight)
    val width = size.width - PADDING_H * 2
    val step = sizeStep(size)
    val form = chartFormFor(step, spec.type)
    val result = ChartCompute.compute(board, spec, chartPointCap(form, width, body, m))
    val sizing = chartSizing(form, step, body, result.points.size, result.partial, m) ?: return null
    return ChartPlan(result, form, sizing, width)
}
