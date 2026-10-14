package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.calendar.TimedLayout
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import com.ysdoing.notioncal.core.data.calendar.chipLabel
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale

private val GUTTER = 40.dp
private const val TOP_LANES = 3
private val timeFormat = DateTimeFormatter.ofPattern("H:mm")

/**
 * MV-06 주·3일 시간표. [start]부터 [days]일을 칸으로 그린다.
 * - 맨 위: 요일·날짜, 그 아래 종일·여러 날 일정 줄(최대 3줄, 넘치면 +N).
 * - 본문: 0~24시 눈금(세로 스크롤), 시간 일정은 시간 칸에 블록으로, 겹치면 열을 나눠 놓는다([TimedLayout]).
 * - 빈 칸·날짜 머리를 누르면 그날 날짜 시트([onDayClick]), 일정을 누르면 편집([onEventClick]).
 * [nowMinute]가 있으면 오늘 칸에 지금 시각 선을 그린다.
 */
@Composable
fun TimeGrid(
    start: LocalDate,
    days: Int,
    today: LocalDate,
    nowMinute: Int?,
    events: List<CalendarEvent>,
    hourHeight: Dp,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
    initialScrollHour: Int = 8,
) {
    val dates = remember(start, days) { (0 until days).map { start.plusDays(it.toLong()) } }
    val density = LocalDensity.current
    Column(modifier.fillMaxSize()) {
        DayHeaders(dates, today, onDayClick)
        AllDayLane(start, days, events, onEventClick)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        val scroll = rememberScrollState(initial = with(density) { (hourHeight * initialScrollHour.coerceIn(0, 20)).roundToPx() })
        BoxWithConstraints(Modifier.fillMaxSize().verticalScroll(scroll)) {
            val cellWidth = (maxWidth - GUTTER) / days
            Box(Modifier.fillMaxWidth().height(hourHeight * 24)) {
                repeat(24) { hour ->
                    HorizontalDivider(
                        modifier = Modifier.offset(x = GUTTER, y = hourHeight * hour).width(cellWidth * days),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                    )
                    if (hour > 0) {
                        Text(
                            text = stringResource(R.string.timegrid_hour, hour),
                            modifier = Modifier.offset(y = hourHeight * hour - 7.dp).width(GUTTER - 4.dp),
                            textAlign = TextAlign.End,
                            fontSize = 10.sp,
                            lineHeight = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                dates.forEachIndexed { col, date ->
                    val description = stringResource(R.string.timegrid_day_a11y, date.monthValue, date.dayOfMonth)
                    Box(
                        Modifier
                            .offset(x = GUTTER + cellWidth * col)
                            .width(cellWidth)
                            .fillMaxHeight()
                            .semantics { contentDescription = description }
                            .pointerInput(date) { detectTapGestures { onDayClick(date) } },
                    ) {
                        Box(Modifier.width(0.5.dp).fillMaxHeight().background(MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)))
                        val blocks = remember(date, events) { TimedLayout.layout(date, events) }
                        blocks.forEach { block ->
                            val width = cellWidth / block.columns
                            TimedBlockChip(
                                event = block.event,
                                modifier = Modifier
                                    .offset(x = width * block.column, y = hourHeight * (block.startMinute / 60f))
                                    .width(width)
                                    .height(hourHeight * ((block.endMinute - block.startMinute) / 60f))
                                    .padding(1.dp),
                                onClick = { onEventClick(block.event) },
                            )
                        }
                        if (date == today && nowMinute != null) {
                            Box(
                                Modifier
                                    .offset(y = hourHeight * (nowMinute / 60f))
                                    .fillMaxWidth()
                                    .height(2.dp)
                                    .background(MaterialTheme.colorScheme.error),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DayHeaders(dates: List<LocalDate>, today: LocalDate, onDayClick: (LocalDate) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Spacer(Modifier.width(GUTTER))
        dates.forEach { date ->
            val isToday = date == today
            Column(
                Modifier.weight(1f).clickable { onDayClick(date) },
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(
                    date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                    style = MaterialTheme.typography.labelSmall,
                    color = weekdayColor(date.dayOfWeek) ?: MaterialTheme.colorScheme.onSurfaceVariant,
                )
                val circle = if (isToday) Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary) else Modifier
                Box(circle.defaultMinSize(28.dp, 28.dp), contentAlignment = Alignment.Center) {
                    Text(
                        date.dayOfMonth.toString(),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                        color = if (isToday) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

/** 종일·여러 날 일정 줄. 월 그리드와 같은 배치(WeekLayout)를 칸 수만 바꿔 쓴다. */
@Composable
private fun AllDayLane(start: LocalDate, days: Int, events: List<CalendarEvent>, onEventClick: (CalendarEvent) -> Unit) {
    val topEvents = remember(events) { events.filter { it.isAllDay || it.isMultiDay } }
    val layout = remember(start, days, topEvents) { WeekLayout.layout(start, topEvents, TOP_LANES, days) }
    if (layout.placed.isEmpty() && layout.hiddenCount.all { it == 0 }) return
    val laneHeight = with(LocalDensity.current) { 18.sp.toDp() }
    val lanes = (layout.placed.maxOfOrNull { it.lane + 1 } ?: 0).coerceAtLeast(layout.overflowLane.maxOrNull()?.plus(1) ?: 0)
    BoxWithConstraints(Modifier.fillMaxWidth().height(laneHeight * lanes + 4.dp)) {
        val cellWidth = (maxWidth - GUTTER) / days
        Text(
            stringResource(R.string.timegrid_all_day),
            modifier = Modifier.width(GUTTER - 4.dp).padding(top = 2.dp),
            textAlign = TextAlign.End,
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        layout.placed.forEach { placed ->
            val event = placed.event
            val color = Color(NotionColors.argb(event.colorHex))
            Box(
                Modifier
                    .offset(x = GUTTER + cellWidth * placed.startCol, y = laneHeight * placed.lane)
                    .width(cellWidth * placed.span)
                    .height(laneHeight)
                    .padding(horizontal = 1.dp, vertical = 1.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color.copy(alpha = 0.28f))
                    .clickable { onEventClick(event) }
                    .padding(horizontal = 3.dp),
                contentAlignment = Alignment.CenterStart,
            ) {
                Text(
                    chipLabel(event, placed.continuesBefore),
                    modifier = Modifier.alpha(if (event.done) 0.55f else 1f),
                    fontSize = 10.sp,
                    lineHeight = 12.sp,
                    maxLines = 1,
                    softWrap = false,
                    overflow = TextOverflow.Clip,
                    textDecoration = if (event.done) TextDecoration.LineThrough else null,
                    color = MaterialTheme.colorScheme.onSurface,
                )
            }
        }
        layout.hiddenCount.forEachIndexed { col, hidden ->
            if (hidden > 0) {
                Text(
                    stringResource(R.string.month_more, hidden),
                    modifier = Modifier
                        .offset(x = GUTTER + cellWidth * col, y = laneHeight * layout.overflowLane[col])
                        .width(cellWidth)
                        .padding(horizontal = 3.dp),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun TimedBlockChip(event: CalendarEvent, modifier: Modifier, onClick: () -> Unit) {
    val color = Color(NotionColors.argb(event.colorHex))
    val time = event.startTime?.atZone(APP_ZONE)?.format(timeFormat).orEmpty()
    Column(
        modifier
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = if (event.done) 0.35f else 0.85f))
            .clickable(onClick = onClick)
            .padding(horizontal = 3.dp, vertical = 1.dp),
        verticalArrangement = Arrangement.Top,
    ) {
        Text(
            event.title,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            fontWeight = FontWeight.Medium,
            color = Color.White,
            textDecoration = if (event.done) TextDecoration.LineThrough else null,
            // 겹쳐서 좁아진 블록에서 한글이 한 글자씩 줄바꿈되지 않게 한 줄로 자른다.
            maxLines = 1,
            softWrap = false,
            overflow = TextOverflow.Clip,
        )
        Text(time, fontSize = 9.sp, lineHeight = 11.sp, color = Color.White.copy(alpha = 0.85f), maxLines = 1, softWrap = false)
    }
}

private fun weekdayColor(day: DayOfWeek): Color? = when (day) {
    DayOfWeek.SUNDAY -> Color(0xFFE03E3E)
    DayOfWeek.SATURDAY -> Color(0xFF2F80ED)
    else -> null
}
