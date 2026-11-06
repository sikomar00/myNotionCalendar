package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
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
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.TimelineBar
import com.ysdoing.notioncal.core.data.view.TimelineZoom
import com.ysdoing.notioncal.core.data.view.ViewBoard
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

// Phase 12e VR-TML: 타임라인 보기 본문. 이동 줄(‹ ›) + 날짜 눈금 + 일정마다 한 줄의 막대이고, 막대를 누르면 그 행의 편집 시트다.
// 배치(주·월 창, 줄 수)는 [timelineLayoutFor]가 정하고 이 파일은 그린다.

private const val DONE_FILL = 0.06f
private const val BAR_FILL = 0.28f
private const val MONTH_TICK_DAYS = 7
private val LABEL_INSIDE_MIN = 64.dp // 이보다 좁은 막대는 제목이 잘리니 막대 옆에 쓴다(주 확대의 하루 칸은 약 69dp, 월 확대는 칸이 약 15dp)
private val LABEL_GAP = 4.dp

/** Column 자식은 이동 줄 + 눈금 + 막대 줄 최대 [TIMELINE_MAX_ROWS] + 더 보기(또는 빈 안내)라 Glance 10개 한도 안이다. */
@Composable
internal fun TimelineBody(board: ViewBoard, layout: TimelineLayout, state: ViewWidgetState, look: ViewLook) {
    val context = LocalContext.current
    Column(GlanceModifier.fillMaxWidth()) {
        TimelineNav(layout, state.config.timelineOffset, !board.rowsComplete, look)
        TimelineAxis(layout, calendarToday(state), look)
        if (layout.bars.isEmpty()) TimelineEmpty(layout, look) else layout.bars.forEach { TimelineBarRow(board, it, layout, look) }
        layout.more?.let { MoreLine(moreText(context, it), board.viewId, look) }
    }
}

/** 이동 줄: 범위 제목(다른 창이면 눌러 오늘로) + ‹ ›. 주는 '11/2 – 11/8', 월은 '2026년 11월'. */
@Composable
private fun TimelineNav(layout: TimelineLayout, offset: Int, partial: Boolean, look: ViewLook) {
    val context = LocalContext.current
    val window = layout.window
    val title = if (layout.zoom == TimelineZoom.MONTH) context.getString(R.string.widget_month_title, window.start.year, window.start.monthValue)
    else context.getString(R.string.view_timeline_week_title, window.start.monthValue, window.start.dayOfMonth, window.last.monthValue, window.last.dayOfMonth)
    val atToday = offset == 0
    val description = listOfNotNull(
        if (atToday) title else context.getString(R.string.view_calendar_title_back, title),
        context.getString(R.string.view_calendar_partial_description).takeIf { partial },
    ).joinToString(", ")
    val m = look.m
    Row(GlanceModifier.fillMaxWidth().height(NAV_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
        val titleModifier = GlanceModifier.defaultWeight().height(NAV_HEIGHT).padding(start = 4.dp).let { if (atToday) it else it.clickable(timelineNavAction(0)) }
        Row(titleModifier.semantics { contentDescription = description; testTag = "timelineTitle" }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TextStyle(color = look.palette.text, fontSize = (m.headerSp * m.spFactor).sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (partial) Text(context.getString(R.string.view_calendar_partial), modifier = GlanceModifier.padding(start = 6.dp), style = look.sub, maxLines = 1)
        }
        // ‹ ›는 주 확대에서는 한 주, 월 확대에서는 한 달을 옮기니 낭독도 그렇게 읽는다.
        val weekly = layout.zoom != TimelineZoom.MONTH
        val prev = context.getString(if (weekly) R.string.view_timeline_prev_week else R.string.widget_prev)
        val next = context.getString(if (weekly) R.string.view_timeline_next_week else R.string.widget_next)
        WidgetIconButton(R.drawable.ic_widget_prev, prev, "timelinePrev", timelineNavAction(-1), look.palette, visual = 28.dp, height = NAV_HEIGHT)
        WidgetIconButton(R.drawable.ic_widget_next, next, "timelineNext", timelineNavAction(+1), look.palette, visual = 28.dp, height = NAV_HEIGHT)
    }
}

/** 날짜 눈금: 주는 요일 7칸(오늘은 굵게·강조색), 월은 7일 간격의 날짜 숫자. */
@Composable
private fun TimelineAxis(layout: TimelineLayout, today: LocalDate, look: ViewLook) {
    val sp = (look.m.subSp * look.m.spFactor).sp
    Row(GlanceModifier.fillMaxWidth().height(look.m.tableHeader).semantics { testTag = "timelineAxis" }) {
        if (layout.zoom == TimelineZoom.MONTH) {
            for (col in 0 until layout.window.days step MONTH_TICK_DAYS) {
                val width = layout.cellWidth * minOf(MONTH_TICK_DAYS, layout.window.days - col)
                Text(layout.window.start.plusDays(col.toLong()).dayOfMonth.toString(), modifier = GlanceModifier.width(width), style = TextStyle(color = look.palette.subtle, fontSize = sp), maxLines = 1)
            }
        } else {
            repeat(layout.window.days) { col ->
                val day = layout.window.start.plusDays(col.toLong())
                val isToday = day == today
                Text(
                    day.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN), modifier = GlanceModifier.width(layout.cellWidth),
                    style = TextStyle(
                        color = if (isToday) look.palette.accent else look.palette.subtle, fontSize = sp,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal, textAlign = TextAlign.Center,
                    ),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TimelineEmpty(layout: TimelineLayout, look: ViewLook) {
    val text = LocalContext.current.getString(R.string.view_timeline_empty)
    Box(GlanceModifier.fillMaxWidth().height(layout.rowHeight).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
        Text(text, modifier = GlanceModifier.semantics { testTag = "timelineEmpty" }, style = look.sub, maxLines = 1)
    }
}

/** 일정 기간 글자: 하루면 '1/15', 여러 날이면 '1/15 – 1/17'. */
private fun rangeText(context: Context, event: CalendarEvent): String {
    val (first, last) = event.startDate to event.lastDate
    return if (first == last) context.getString(R.string.view_timeline_bar_day, first.monthValue, first.dayOfMonth)
    else context.getString(R.string.view_timeline_bar_range, first.monthValue, first.dayOfMonth, last.monthValue, last.dayOfMonth)
}

/**
 * 막대 한 줄: 시작 칸 앞의 빈칸 + 일정 색 옅은 막대. 제목은 막대가 [LABEL_INSIDE_MIN]보다 넓을 때만 안에 쓰고, 좁으면 넓은 쪽 옆에 쓴다
 * (월 확대의 하루짜리 막대가 '…'만 남지 않게). 창 밖으로 이어지면 '‹'·'›'를 붙이고, 완료는 더 옅게 + 취소선이다. 줄 어디를 눌러도 그 행의 편집 시트다.
 */
@Composable
private fun TimelineBarRow(board: ViewBoard, bar: TimelineBar, layout: TimelineLayout, look: ViewLook) {
    val context = LocalContext.current
    val event = bar.event
    val style = TextStyle(
        color = look.palette.text, fontSize = (look.m.titleSp * look.m.spFactor).sp, fontWeight = FontWeight.Medium,
        textDecoration = if (event.done) TextDecoration.LineThrough else null,
    )
    val label = (if (bar.continuesBefore) "‹ " else "") + event.title + (if (bar.continuesAfter) " ›" else "")
    // 낭독은 색·옅기·취소선에 기대지 않고 기간과 완료를 글자로 읽어 준다(A11Y-03).
    val description = listOfNotNull(event.title, rangeText(context, event), context.getString(R.string.view_timeline_bar_done).takeIf { event.done }).joinToString(", ")
    val before = layout.cellWidth * bar.startCol
    val width = layout.cellWidth * bar.span
    val after = layout.cellWidth * layout.window.days - before - width
    val inside = width >= LABEL_INSIDE_MIN
    val fill = ColorProvider(Color(NotionColors.argb(event.colorHex)).copy(alpha = if (event.done) DONE_FILL else BAR_FILL))
    val bounds = GlanceModifier.width(width).fillMaxHeight().background(fill).cornerRadius(3.dp)
    Row(
        GlanceModifier.fillMaxWidth().height(layout.rowHeight).padding(vertical = 2.dp)
            .clickable(actionStartActivity(viewRowIntent(context, board.viewId, event.pageId, look.actionWidgetId)))
            .semantics { contentDescription = description; testTag = "timelineBar" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val labelOnLeft = !inside && before > after
        if (labelOnLeft) Text(label, modifier = GlanceModifier.width((before - LABEL_GAP).coerceAtLeast(0.dp)), style = style.copy(textAlign = TextAlign.End), maxLines = 1)
        else if (before > 0.dp) Spacer(GlanceModifier.width(before))
        if (inside) Box(bounds.padding(horizontal = 4.dp), contentAlignment = Alignment.CenterStart) { Text(label, style = style, maxLines = 1) }
        else Box(bounds) {}
        if (!inside && !labelOnLeft) Text(label, modifier = GlanceModifier.width((after - LABEL_GAP).coerceAtLeast(0.dp)).padding(start = LABEL_GAP), style = style, maxLines = 1)
    }
}
