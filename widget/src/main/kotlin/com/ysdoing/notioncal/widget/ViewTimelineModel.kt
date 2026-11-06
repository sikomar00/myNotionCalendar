package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.TimelineBar
import com.ysdoing.notioncal.core.data.view.TimelineConfig
import com.ysdoing.notioncal.core.data.view.TimelineRows
import com.ysdoing.notioncal.core.data.view.TimelineWindow
import com.ysdoing.notioncal.core.data.view.TimelineZoom
import com.ysdoing.notioncal.core.data.view.ViewBoard

// Phase 12e VR-TML: 타임라인 보기를 막대 줄로 그린다. 이 파일은 순수 계산(창·줄 수)이고 그리기는 ViewTimelineBody.kt다.

internal val TIMELINE_NAV_HEIGHT = 40.dp
private val TIMELINE_ROW_GAP = 4.dp

/** Glance Column 자식 10개 안: 이동 줄 + 날짜 눈금 + 막대 줄 7개 + 더 보기 = 10. */
internal const val TIMELINE_MAX_ROWS = 7

/**
 * 타임라인 배치: 확대 단계([zoom], 주 또는 월)의 [window], 그릴 막대 [bars](줄 수가 곧 개수), 날짜 한 칸 폭 [cellWidth]와 막대 줄 높이 [rowHeight].
 * [more]가 있으면 창 안에 줄이 모자라 더 있다(또는 일정을 일부만 받았다).
 */
internal data class TimelineLayout(
    val zoom: TimelineZoom,
    val window: TimelineWindow,
    val bars: List<TimelineBar>,
    val rowHeight: Dp,
    val cellWidth: Dp,
    val more: More?,
)

/** 타임라인으로 그릴 수 있는 보기: 종류가 타임라인이고 날짜 열을 알 때. 아니면 지금처럼 표·목록으로 그린다. */
internal fun ViewBoard.drawsTimeline(): Boolean = type == "timeline" && dateColumn != null

/** 주 시작 요일(앱 설정)이 필요한 보기: 캘린더와 타임라인. 이 보기가 든 위젯만 설정을 읽는다. */
internal fun ViewBoard.needsCalendarInfo(): Boolean = drawsCalendar() || drawsTimeline()

/**
 * 이 보기를 타임라인으로 그릴 배치. 타임라인 보기가 아니거나 날짜 열을 모르거나 2×2이거나, 이동 줄·눈금·막대 한 줄이 들어갈 높이가 없으면 null이고
 * 그때는 표·목록으로 대체한다(캘린더·갤러리와 같은 폴백). 확대는 노션 설정, 없거나 모르는 값이면 크기로(전체는 월, 그 밖은 주)이다.
 * 창은 위젯의 `monthOffset`만큼 옮겨 간다(‹ ›가 주 또는 달 단위로 옮긴다).
 */
internal fun timelineLayoutFor(board: ViewBoard, state: ViewWidgetState, size: DpSize, pane: PaneSpec, m: ViewMetrics, aboveHeight: Dp): TimelineLayout? {
    val step = sizeStep(size)
    if (!board.drawsTimeline() || step == SizeStep.COMPACT) return null
    val zoom = TimelineConfig.zoomOf(board.viewConfig).let { if (it == TimelineZoom.AUTO) (if (step == SizeStep.FULL) TimelineZoom.MONTH else TimelineZoom.WEEK) else it }
    val window = TimelineRows.window(zoom, calendarToday(state), state.calendar.weekStart, state.config.monthOffset)
    val all = TimelineRows.bars(board, window)
    val total = pane.height ?: (size.height - VIEW_PADDING_V * 2)
    val body = paneBodyHeight(total, progressOf(board, size) != null, m, aboveHeight)
    val rowHeight = m.tableRow + TIMELINE_ROW_GAP
    fun rowsFit(available: Dp): Int = minOf(TIMELINE_MAX_ROWS, ((available - TIMELINE_NAV_HEIGHT - m.tableHeader) / rowHeight).toInt())
    var rows = rowsFit(body)
    if (all.size > rows || !board.rowsComplete) rows = rowsFit(body - MORE_HEIGHT)
    if (rows < 1) return null
    val shown = all.take(rows)
    return TimelineLayout(zoom, window, shown, rowHeight, (size.width - PADDING_H * 2) / window.days, moreOf(all.size, shown.size, board.rowsComplete))
}
