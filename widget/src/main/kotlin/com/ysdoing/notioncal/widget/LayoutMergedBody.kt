package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.action.clickable
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.CalendarRows

// Phase 12e WL-04 합쳐 보기: 보기 최대 3개의 일정을 한 월 격자에 소스 색으로 그린다(월 격자 캘린더만, 합친 목록·타임라인은 후속).
// 격자·날짜 칸은 캘린더 보기와 같은 [CalendarBody]를 쓰고, 소스를 구분하는 색 범례 한 줄이 머리 아래에 든다.

/** 범례 한 줄: 소스 이름 + 일정 색([hex]). */
private class MergedLine(val title: String, val hex: String, val source: CalendarRows.Source)

/** 지워졌거나 못 그리는 보기는 빼고, 남은 소스의 이름·색·기준 날짜 속성을 배치 순서대로. 색은 배치에서의 자리(index)로 정해 한 소스가 빠져도 다른 소스의 색이 바뀌지 않는다. */
private fun LayoutWidgetState.mergedLines(): List<MergedLine> = layout.items.mapIndexedNotNull { index, item ->
    boards[item.viewId]?.takeUnless { it.notFound || it.unsupported }?.let { board ->
        val hex = layout.colorHexAt(index)
        MergedLine(item.title(board.name), hex, CalendarRows.Source(board, item.dateProp, hex))
    }
}

private fun List<MergedLine>.calendarSource(): CalendarSource = CalendarSource(
    events = CalendarRows.merged(map { it.source }),
    complete = all { it.source.board.rowsComplete },
    // 날짜 칸은 새 항목을 만들 수 있는 첫 소스의 시트를 연다. 만들 수 있는 소스가 없으면 첫 소스의 뷰어다.
    createViewId = firstOrNull { it.source.board.canCreate }?.source?.board?.viewId,
    viewerViewId = first().source.board.viewId,
)

private val LEGEND_DOT = 8.dp
private val LEGEND_GAP = 4.dp

@Composable
internal fun LayoutMergedBody(state: LayoutWidgetState, look: LayoutLook, size: DpSize) {
    val context = LocalContext.current
    val m = look.metrics
    val lines = state.mergedLines()
    Column(GlanceModifier.fillMaxSize().padding(horizontal = PADDING_H, vertical = VIEW_PADDING_V)) {
        if (lines.isEmpty()) {
            ViewNote(R.string.view_widget_missing, state.config.appWidgetId, m, look.palette, tag = "mergedNote")
            return@Column
        }
        MergedHeader(lines, size, look)
        val legendHeight = m.tableHeader + LEGEND_GAP
        MergedLegend(lines, legendHeight, look)
        val layout = calendarLayoutIn(size, PaneSpec(look.staticRows), m, legendHeight, subtitleShown = false)
        if (layout == null) {
            Text(context.getString(R.string.layout_merged_too_small), modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp).semantics { testTag = "mergedTooSmall" }, style = TextStyle(color = look.palette.subtle, fontSize = (m.subSp * m.spFactor).sp))
        } else {
            CalendarBody(lines.calendarSource(), ViewWidgetState(null, state.config, state.nowMillis, state.calendar), ViewLook(m, look.palette), layout)
        }
    }
}

/** 머리: 제목 '합쳐 보기'(누르면 새로고침)와 새로고침 버튼(넓을 때). 새 항목은 날짜 칸을 눌러 만든다. */
@Composable
private fun MergedHeader(lines: List<MergedLine>, size: DpSize, look: LayoutLook) {
    val context = LocalContext.current
    val m = look.metrics
    val title = context.getString(R.string.layout_merged_title)
    WidgetHeader(
        icon = R.drawable.ic_widget_checklist, title = title, subtitle = null, palette = look.palette, height = m.header,
        titleSp = m.headerSp * m.spFactor, subSp = m.subSp * m.spFactor,
        titleModifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()).semantics {
            contentDescription = context.getString(R.string.layout_merged_header_description, lines.joinToString(", ") { it.title })
            testTag = "mergedHeader"
        },
    ) {
        if (size.width >= REFRESH_MIN_WIDTH) {
            WidgetIconButton(R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "mergedRefresh", actionRunCallback<RefreshAction>(), look.palette, height = m.header)
        }
    }
}

/** 소스마다 색 점 + 이름. 색만으로 구분하지 않도록 이름을 함께 쓴다(A11Y-03). */
@Composable
private fun MergedLegend(lines: List<MergedLine>, height: androidx.compose.ui.unit.Dp, look: LayoutLook) {
    val m = look.metrics
    Row(GlanceModifier.fillMaxWidth().height(height).semantics { testTag = "mergedLegend" }, verticalAlignment = Alignment.CenterVertically) {
        lines.forEach { line ->
            Box(GlanceModifier.size(LEGEND_DOT).cornerRadius(LEGEND_DOT / 2).background(ColorProvider(Color(NotionColors.argb(line.hex))))) {}
            Text(line.title, modifier = GlanceModifier.defaultWeight().padding(start = LEGEND_GAP, end = LEGEND_GAP * 2), style = TextStyle(color = look.palette.subtle, fontSize = (m.subSp * m.spFactor).sp), maxLines = 1)
        }
    }
}
