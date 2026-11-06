package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
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
import androidx.glance.layout.fillMaxSize
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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import com.ysdoing.notioncal.core.data.calendar.chipLabel
import com.ysdoing.notioncal.core.data.calendar.weekNumber
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

@Composable
internal fun WeekdayRow(weekStart: DayOfWeek, metrics: WidgetMetrics, palette: WidgetPalette) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.weekdayHeight)) {
        if (metrics.weekNumberWidth > 0.dp) Spacer(GlanceModifier.width(metrics.weekNumberWidth))
        repeat(7) { i ->
            val day = weekStart.plus(i.toLong())
            Text(
                text = day.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
                modifier = GlanceModifier.width(metrics.cellWidth),
                style = TextStyle(
                    color = palette.dayColor(day),
                    fontSize = metrics.weekdayFontSp.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
internal fun WeekRow(
    weekStart: LocalDate,
    state: WidgetUiState,
    metrics: WidgetMetrics,
    palette: WidgetPalette,
    context: Context,
    dayAction: ((LocalDate) -> Action)? = null,
) {
    val frame = GlanceModifier.fillMaxWidth().height(metrics.weekHeight)
    if (metrics.mode == WidgetMode.SMALL) {
        Box(modifier = frame) { WeekContent(weekStart, state, metrics, palette, context, dayAction) }
        return
    }
    // 시안: 주마다 둥근 타일 한 장. 위 1dp 틈이 이전 구분선(1dp Spacer) 자리를 대신하므로 높이·일정 줄 수는 그대로고 View도 늘지 않는다.
    // (칸마다 타일을 깔면 View가 주당 14개 늘어 빽빽한 큰 위젯이 Glance의 View 개수 한도를 넘는다.)
    Box(modifier = frame.padding(top = 1.dp)) {
        Box(modifier = GlanceModifier.fillMaxSize().tile(palette, radius = WidgetShape.CELL)) {
            WeekContent(weekStart, state, metrics, palette, context, dayAction)
        }
    }
}

/** [dayAction]이 null이면 v1 월 위젯처럼 그날 퀵 시트(WI-01)를 연다. 보기 위젯의 캘린더는 그 보기의 새 항목 시트를 넘긴다(Phase 12a). */
@Composable
private fun WeekContent(
    weekStart: LocalDate,
    state: WidgetUiState,
    metrics: WidgetMetrics,
    palette: WidgetPalette,
    context: Context,
    dayAction: ((LocalDate) -> Action)?,
) {
    Column(modifier = GlanceModifier.fillMaxSize()) {
        // 작음 단계는 주마다 구분선(타일이 없다), 그 밖은 주 타일이 구분을 맡는다.
        if (metrics.mode == WidgetMode.SMALL) Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(palette.divider))
        Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.dateLabelHeight)) {
            if (metrics.weekNumberWidth > 0.dp) WeekNumber(weekStart, state.weekStart, metrics, palette)
            repeat(7) { col ->
                val date = weekStart.plusDays(col.toLong())
                DateLabel(date, !state.dimOutsideMonth || YearMonth.from(date) == state.month, date == state.today, date in state.holidays, metrics, palette)
            }
        }
        if (metrics.mode == WidgetMode.SMALL) {
            if (metrics.showDots) DotRow(weekStart, state.events, metrics)
        } else if (metrics.lanes == 1) {
            SingleLineRow(weekStart, state.events, metrics, palette)
        } else {
            val layout = WeekLayout.layout(weekStart, state.events, metrics.lanes)
            repeat(metrics.lanes) { lane ->
                // 주차 열만큼 들여쓴다(Spacer를 두면 줄마다 View가 늘어 빽빽한 큰 위젯이 Glance View 개수 한도를 넘는다).
                Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.laneHeight).padding(start = metrics.weekNumberWidth)) {
                    laneSegments(layout, lane).forEach { segment -> Segment(segment, metrics, palette) }
                }
            }
        }
    }
    // 칸 어디를 눌러도 그 날짜의 퀵 시트(WI-01)를 연다.
    Row(modifier = GlanceModifier.fillMaxSize().padding(start = metrics.weekNumberWidth)) {
        repeat(7) { col ->
            val date = weekStart.plusDays(col.toLong())
            val count = state.events.count { it.occursOn(date) }
            // Spacer에는 클릭이 붙지 않아(실기기 확인) 빈 Box로 터치 영역을 만든다.
            Box(
                GlanceModifier
                    .width(metrics.cellWidth)
                    .fillMaxHeight()
                    // WI-01: 그날 퀵 시트(목록·체크·추가)가 홈 화면 위에 뜬다.
                    .clickable(dayAction?.invoke(date) ?: actionStartActivity(quickSheetIntent(context, AppIntents.MODE_DAY, date)))
                    .semantics {
                        contentDescription = listOfNotNull(
                            "${date.monthValue}월 ${date.dayOfMonth}일",
                            state.holidays[date],
                            "일정 ${count}개",
                        ).joinToString(", ")
                        testTag = "day"
                    },
            ) {}
        }
    }
}

/** M6 주 첫날의 주차(앱 월 화면·분할 위젯과 같은 계산). 흐린 글자로 날짜 줄 맨 앞에. */
@Composable
private fun WeekNumber(weekFirstDay: LocalDate, weekStart: DayOfWeek, metrics: WidgetMetrics, palette: WidgetPalette) {
    Text(
        text = weekNumber(weekFirstDay, weekStart).toString(),
        modifier = GlanceModifier.width(metrics.weekNumberWidth).semantics { testTag = "weekNumber" },
        style = TextStyle(color = palette.subtle, fontSize = metrics.weekNumberFontSp.sp, textAlign = TextAlign.Center),
        maxLines = 1,
    )
}

@Composable
private fun DateLabel(date: LocalDate, inMonth: Boolean, isToday: Boolean, isHoliday: Boolean, metrics: WidgetMetrics, palette: WidgetPalette) {
    Box(
        modifier = GlanceModifier.width(metrics.cellWidth).height(metrics.dateLabelHeight),
        contentAlignment = Alignment.TopCenter,
    ) {
        val text = date.dayOfMonth.toString()
        // MV-01·EX-02: 공휴일은 일요일과 같은 빨강(이름은 칸 설명으로 읽는다).
        val color = when {
            !inMonth -> palette.outside
            isHoliday -> palette.holiday
            else -> palette.dayColor(date.dayOfWeek)
        }
        if (isToday) {
            Box(
                modifier = GlanceModifier
                    .padding(horizontal = if (metrics.mode == WidgetMode.SMALL) 0.dp else 4.dp)
                    .background(palette.todayBackground)
                    .cornerRadius(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    style = TextStyle(color = palette.todayText, fontSize = metrics.dateFontSp.sp, fontWeight = FontWeight.Bold),
                )
            }
        } else {
            Text(text, style = TextStyle(color = color, fontSize = metrics.dateFontSp.sp))
        }
    }
}

/** 작음 단계: 일정 색 점만(칸당 최대 3개). */
@Composable
private fun DotRow(weekStart: LocalDate, events: List<CalendarEvent>, metrics: WidgetMetrics) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        repeat(7) { col ->
            val date = weekStart.plusDays(col.toLong())
            val colors = events.filter { it.occursOn(date) }.take(3)
            Row(
                modifier = GlanceModifier.width(metrics.cellWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                colors.forEach { event ->
                    Box(
                        GlanceModifier
                            .padding(horizontal = 1.dp)
                            .size(5.dp)
                            .cornerRadius(2.5.dp)
                            .background(ColorProvider(Color(NotionColors.argb(event.colorHex))))
                            .semantics { testTag = "dot" },
                    ) {}
                }
            }
        }
    }
}

/**
 * 한 줄만 들어갈 때(중간 크기·칸당 1줄): 날짜마다 첫 일정 제목과 나머지 개수(+N)를 함께 보여준다.
 * 제목은 남는 폭을 쓰고 +N은 항상 보인다. 여러 날 일정은 걸친 날마다 표시된다.
 */
@Composable
private fun SingleLineRow(weekStart: LocalDate, events: List<CalendarEvent>, metrics: WidgetMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.laneHeight).padding(start = metrics.weekNumberWidth)) {
        repeat(7) { col ->
            val date = weekStart.plusDays(col.toLong())
            val dayEvents = events.filter { it.occursOn(date) }
            val first = dayEvents.firstOrNull()
            Row(
                modifier = GlanceModifier.width(metrics.cellWidth).height(metrics.laneHeight).padding(start = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (first != null) {
                    if (!first.showsEmphasis) {
                        Box(GlanceModifier.size(4.dp).cornerRadius(2.dp).background(ColorProvider(Color(NotionColors.argb(first.colorHex))))) {}
                    }
                    Text(
                        text = chipLabel(first, continuesBefore = first.startDate.isBefore(date)),
                        modifier = GlanceModifier.defaultWeight().padding(start = 2.dp).emphasisBox(first).semantics { testTag = "chip" },
                        style = chipStyle(first, metrics, palette),
                        maxLines = 1,
                    )
                    if (dayEvents.size > 1) {
                        Text(
                            text = context.getString(R.string.widget_more, dayEvents.size - 1),
                            modifier = GlanceModifier.semantics { testTag = "more" },
                            style = TextStyle(color = palette.subtle, fontSize = (metrics.chipFontSp * 0.9f).sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun chipStyle(event: CalendarEvent, metrics: WidgetMetrics, palette: WidgetPalette) = TextStyle(
    color = when {
        event.showsEmphasis -> emphasisTextColor(event)
        event.done -> palette.subtle
        else -> palette.text
    },
    fontSize = metrics.chipFontSp.sp,
    fontWeight = if (event.showsEmphasis) FontWeight.Bold else null,
    textDecoration = if (event.done) TextDecoration.LineThrough else null,
)

/**
 * EX-11 강조 일정(예: 우선순위 '1st'): 일정 색으로 칠한 칸에 대비되는 굵은 글자. 색만으로 구분하지 않게 굵기도 바꾼다(A11Y-03).
 * 완료한 일정은 강조하지 않는다([CalendarEvent.showsEmphasis]).
 */
internal fun emphasisTextColor(event: CalendarEvent): ColorProvider = ColorProvider(Color(NotionColors.onColor(event.colorHex)))

internal fun GlanceModifier.emphasisBox(event: CalendarEvent): GlanceModifier =
    if (event.showsEmphasis) {
        background(ColorProvider(Color(NotionColors.argb(event.colorHex)))).cornerRadius(3.dp).padding(horizontal = 3.dp)
    } else {
        this
    }

@Composable
private fun Segment(segment: LaneSegment, metrics: WidgetMetrics, palette: WidgetPalette) {
    val width = metrics.cellWidth * segment.span
    val placed = segment.placed
    when {
        placed != null -> {
            val event = placed.event
            val color = Color(NotionColors.argb(event.colorHex))
            // 완료 막대는 주 타일 위에서 흐린 글자(흑연)가 4.5:1에 못 미치는 색이 있어(라이트 최저 4.1:1) 본문색을 쓴다. 취소선이 완료를 알린다.
            val style = chipStyle(event, metrics, palette).let { if (event.isMultiDay && event.done && !event.showsEmphasis) it.copy(color = palette.text) else it }
            val label = chipLabel(event, placed.continuesBefore)
            if (event.isMultiDay) {
                Box(
                    modifier = GlanceModifier.width(width).height(metrics.laneHeight).padding(horizontal = 1.dp, vertical = 0.5.dp),
                ) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            // 완료 막대는 더 옅게: 흐린 글자(흑연)가 노션 20색 모두에서 라이트 4.6:1·다크 5.5:1 이상(12%는 라이트 4.2:1).
                            .background(ColorProvider(if (event.showsEmphasis) color else color.copy(alpha = if (event.done) 0.06f else 0.28f)))
                            .cornerRadius(3.dp)
                            .padding(horizontal = 3.dp)
                            .semantics { testTag = "chip" },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(label, style = style, maxLines = 1)
                    }
                }
            } else {
                Row(
                    modifier = GlanceModifier.width(width).height(metrics.laneHeight).padding(start = 2.dp).semantics { testTag = "chip" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (event.showsEmphasis) {
                        Text(label, modifier = GlanceModifier.emphasisBox(event).semantics { testTag = "emphasized" }, style = style, maxLines = 1)
                    } else {
                        Box(GlanceModifier.size(4.dp).cornerRadius(2.dp).background(ColorProvider(color))) {}
                        Text(label, modifier = GlanceModifier.padding(start = 2.dp), style = style, maxLines = 1)
                    }
                }
            }
        }
        segment.hiddenCount > 0 -> Text(
            text = LocalContext.current.getString(R.string.widget_more, segment.hiddenCount),
            modifier = GlanceModifier.width(width).padding(start = 3.dp).semantics { testTag = "more" },
            style = TextStyle(color = palette.subtle, fontSize = metrics.chipFontSp.sp),
            maxLines = 1,
        )
        else -> Spacer(GlanceModifier.width(width))
    }
}
