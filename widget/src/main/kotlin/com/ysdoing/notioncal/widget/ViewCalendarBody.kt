package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.calendar.chipLabel
import com.ysdoing.notioncal.core.data.calendar.gridStart
import java.time.LocalDate

// Phase 12a VR-CAL: 캘린더 보기를 월 격자로 그린다. 격자·날짜 칸은 v1 월 위젯의 WeekRow를 그대로 쓰고(같은 일정이면 같은 노드),
// 날짜 칸을 누르면 그 보기의 새 항목 시트가 그 날짜로 열린다. 4×2는 '이번 주 한 줄 + 오늘 일정 목록'이다.
// 배치(높이 나누기·읽힐 최소 면)는 [calendarLayoutFor]가 정하고 이 파일은 그린다.

/**
 * [layout]대로 캘린더 본문(머리줄 아래)을 그린다. 일정이 많아 Glance 뷰 한도(500개)에 가까우면 칸당 줄 수를 줄인다([fitLanes]).
 */
@Composable
internal fun CalendarBody(source: CalendarSource, state: ViewWidgetState, look: ViewLook, layout: CalendarLayout) {
    val context = LocalContext.current
    val ui = calendarUi(source.events, state, layout.mode)
    val weeks = if (layout.mode == CalendarMode.MONTH) (0 until WEEKS).map { gridStart(ui.month, ui.weekStart).plusWeeks(it.toLong()) } else listOf(weekStartOf(ui.today, ui.weekStart))
    val metrics = fitLanes(weeks, ui.events, WidgetMetrics.of(LocalSize.current, state.config, look.m.systemFontScale, ui.showWeekNumbers, layout.grid))
    val dayAction = dayActionFor(context, source)
    Column(GlanceModifier.fillMaxWidth()) {
        if (layout.mode == CalendarMode.MONTH) CalendarNav(ui, !source.complete, layout, look)
        WeekdayRow(ui.weekStart, metrics, look.palette)
        weeks.forEach { WeekRow(it, ui, metrics, look.palette, context, dayAction) }
        if (layout.mode == CalendarMode.WEEK) TodayList(source, ui, layout.listHeight, look)
    }
}

private const val WEEKS = 6

/** 날짜 칸: 새 항목을 만들 수 있는 보기면 그 날짜로 새 항목 시트, 아니면 뷰어(그날 일정 보기는 v1 일정 DB의 시트라 쓰지 않는다). */
private fun dayActionFor(context: Context, source: CalendarSource): (LocalDate) -> Action =
    source.createViewId?.let { viewId -> { date: LocalDate -> actionStartActivity(viewAddIntent(context, viewId, source.widgetId, date)) } }
        ?: { _: LocalDate -> actionStartActivity(viewerIntent(context, source.viewerViewId)) }

/** 월 이동 줄: 달 제목(다른 달이면 눌러 오늘로) + ‹ ›. 일정을 일부만 받았으면(100행 상한) 제목 옆에 알린다(2×2는 자리가 없어 접근성 설명으로만). */
@Composable
private fun CalendarNav(ui: WidgetUiState, partial: Boolean, layout: CalendarLayout, look: ViewLook) {
    val context = LocalContext.current
    val m = look.m
    val compact = layout.compact
    val atToday = ui.config.monthOffset == 0
    val title = if (compact) context.getString(R.string.widget_month_short, ui.month.monthValue) else context.getString(R.string.widget_month_title, ui.month.year, ui.month.monthValue)
    val titleSp = (if (compact) m.titleSp else m.headerSp) * m.spFactor
    // 다른 달을 볼 때도 어느 달인지 읽어 주고(제목을 누르면 오늘로 간다는 말을 덧붙인다), 일부만 받았다는 말도 함께 읽는다.
    val description = listOfNotNull(
        if (atToday) title else context.getString(R.string.view_calendar_title_back, title),
        context.getString(R.string.view_calendar_partial_description).takeIf { partial },
    ).joinToString(", ")
    val visual = if (compact) 24.dp else 28.dp
    Row(GlanceModifier.fillMaxWidth().height(layout.navHeight), verticalAlignment = Alignment.CenterVertically) {
        val titleModifier = GlanceModifier.defaultWeight().height(layout.navHeight).padding(start = 4.dp).let { if (atToday) it else it.clickable(navAction(0)) }
        Row(titleModifier.semantics { contentDescription = description; testTag = "calendarTitle" }, verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = TextStyle(color = look.palette.text, fontSize = titleSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            if (partial && !compact) {
                Text(context.getString(R.string.view_calendar_partial), modifier = GlanceModifier.padding(start = 6.dp), style = look.sub, maxLines = 1)
            }
        }
        WidgetIconButton(R.drawable.ic_widget_prev, context.getString(R.string.widget_prev), "calendarPrev", navAction(-1), look.palette, visual = visual, height = layout.navHeight)
        WidgetIconButton(R.drawable.ic_widget_next, context.getString(R.string.widget_next), "calendarNext", navAction(+1), look.palette, visual = visual, height = layout.navHeight)
    }
}

/** 4×2: 이번 주 한 줄 아래에 오늘 일정을 한 줄씩. 일정이 없으면 한 줄 안내, 넘치면 '+N 더 보기'(뷰어). */
@Composable
private fun TodayList(source: CalendarSource, ui: WidgetUiState, height: Dp, look: ViewLook) {
    val context = LocalContext.current
    val today = eventsOn(ui.events, ui.today)
    val rowHeight = look.m.tableRow + ROW_GAP * 2
    if (today.isEmpty()) {
        Box(GlanceModifier.fillMaxWidth().height(rowHeight).padding(start = 4.dp), contentAlignment = Alignment.CenterStart) {
            Text(context.getString(R.string.view_calendar_today_empty), modifier = GlanceModifier.semantics { testTag = "calendarTodayEmpty" }, style = look.sub, maxLines = 1)
        }
        return
    }
    val shown = todayRowLimit(height, rowHeight, today.size, source.complete)
    today.take(shown).forEach { TodayRow(it, ui.today, look) }
    val more = moreOf(today.size, shown, complete = source.complete)
    if (more != null) MoreLine(moreText(context, more), source.viewerViewId, look)
}

@Composable
private fun TodayRow(event: CalendarEvent, today: LocalDate, look: ViewLook) {
    val context = LocalContext.current
    val style = TextStyle(
        color = if (event.done) look.palette.subtle else look.palette.text, fontSize = (look.m.titleSp * look.m.spFactor).sp,
        fontWeight = FontWeight.Medium, textDecoration = if (event.done) TextDecoration.LineThrough else null,
    )
    // 어제부터 이어지는 시간 일정은 시작 시각을 앞세우지 않는다(격자 칸의 칩과 같은 규칙).
    val label = chipLabel(event, continuesBefore = event.startDate.isBefore(today))
    Box(GlanceModifier.fillMaxWidth().padding(vertical = ROW_GAP)) {
        Row(
            GlanceModifier.fillMaxWidth().height(look.m.tableRow).tile(look.palette).padding(horizontal = 6.dp)
                .clickable(actionStartActivity(viewRowIntent(context, event.dataSourceId, event.pageId, look.actionWidgetId))).semantics { testTag = "calendarToday" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(GlanceModifier.size(6.dp).cornerRadius(3.dp).background(ColorProvider(Color(NotionColors.argb(event.colorHex))))) {}
            Text(label, modifier = GlanceModifier.defaultWeight().padding(start = 6.dp), style = style, maxLines = 1)
        }
    }
}
