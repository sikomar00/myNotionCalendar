package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

/** MV-01: 항상 6주. 첫 칸은 주 시작 요일에 맞춘다. */
fun gridStart(month: YearMonth, weekStart: DayOfWeek): LocalDate =
    month.atDay(1).with(TemporalAdjusters.previousOrSame(weekStart))

const val GRID_DAYS = 42L

@Immutable
data class MonthGridState(
    val month: YearMonth,
    val weekStart: DayOfWeek,
    val today: LocalDate,
    val events: List<CalendarEvent>,
)

private val SundayColor = Color(0xFFE03E3E)
private val SaturdayColor = Color(0xFF2F80ED)

/** 월 그리드(MV-01·02·05). 데이터는 호출부가 Room에서 읽어 넘긴다. */
@Composable
fun MonthGrid(state: MonthGridState, onDayClick: (LocalDate) -> Unit, modifier: Modifier = Modifier) {
    val start = gridStart(state.month, state.weekStart)
    Column(modifier.fillMaxSize()) {
        WeekdayHeader(state.weekStart)
        repeat(6) { week ->
            val weekStartDate = start.plusWeeks(week.toLong())
            WeekRow(
                weekStart = weekStartDate,
                month = state.month,
                today = state.today,
                events = state.events,
                onDayClick = onDayClick,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun WeekdayHeader(weekStart: DayOfWeek) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        repeat(7) { i ->
            val day = weekStart.plus(i.toLong())
            Text(
                text = day.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                modifier = Modifier.weight(1f),
                textAlign = TextAlign.Center,
                style = MaterialTheme.typography.labelMedium,
                color = dayColor(day) ?: MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun dayColor(day: DayOfWeek): Color? = when (day) {
    DayOfWeek.SUNDAY -> SundayColor
    DayOfWeek.SATURDAY -> SaturdayColor
    else -> null
}

@Composable
private fun WeekRow(
    weekStart: LocalDate,
    month: YearMonth,
    today: LocalDate,
    events: List<CalendarEvent>,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
) {
    val density = LocalDensity.current
    // 글꼴 크기 설정을 따라 줄 높이를 정한다(시스템 글꼴 최대에서도 글자가 잘리지 않게).
    val laneHeight = with(density) { 15.sp.toDp() }
    val labelHeight = with(density) { 20.sp.toDp() } + 4.dp
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val cellWidth = maxWidth / 7
        val maxLanes = ((maxHeight - labelHeight) / laneHeight).toInt().coerceAtLeast(1)
        val layout = remember(weekStart, events, maxLanes) { WeekLayout.layout(weekStart, events, maxLanes) }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(Modifier.fillMaxSize()) {
            repeat(7) { col ->
                val date = weekStart.plusDays(col.toLong())
                DayLabel(
                    date = date,
                    inMonth = YearMonth.from(date) == month,
                    isToday = date == today,
                    modifier = Modifier.weight(1f).height(labelHeight),
                )
            }
        }
        layout.placed.forEach { placed ->
            EventChip(
                placed = placed,
                modifier = Modifier
                    .offset(x = cellWidth * placed.startCol, y = labelHeight + laneHeight * placed.lane)
                    .width(cellWidth * placed.span)
                    .height(laneHeight)
                    .padding(horizontal = 1.dp, vertical = 0.5.dp),
            )
        }
        repeat(7) { col ->
            val hidden = layout.hiddenCount[col]
            if (hidden > 0) {
                Text(
                    text = stringResource(R.string.month_more, hidden),
                    modifier = Modifier
                        .offset(x = cellWidth * col, y = labelHeight + laneHeight * layout.overflowLane[col])
                        .width(cellWidth)
                        .height(laneHeight)
                        .padding(horizontal = 3.dp),
                    fontSize = 10.sp,
                    lineHeight = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        // 칸 전체를 누를 수 있게 일정 위에 투명한 터치 영역을 둔다. TalkBack은 날짜와 일정 수를 읽는다.
        Row(Modifier.fillMaxSize()) {
            repeat(7) { col ->
                val date = weekStart.plusDays(col.toLong())
                val count = events.count { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }
                val description = stringResource(R.string.month_day_a11y, date.monthValue, date.dayOfMonth, count)
                Spacer(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics { contentDescription = description }
                        .clickable { onDayClick(date) },
                )
            }
        }
    }
}

@Composable
private fun DayLabel(date: LocalDate, inMonth: Boolean, isToday: Boolean, modifier: Modifier = Modifier) {
    val base = dayColor(date.dayOfWeek) ?: MaterialTheme.colorScheme.onSurface
    // 글꼴 크기에 맞춰 커지도록 sp 기준 최소 크기를 쓴다(고정 dp면 글꼴 최대에서 숫자가 잘림).
    val minSize = with(LocalDensity.current) { 18.sp.toDp() }
    Box(modifier.alpha(if (inMonth) 1f else 0.4f).padding(top = 2.dp), contentAlignment = Alignment.TopCenter) {
        val circle = if (isToday) Modifier.clip(CircleShape).background(MaterialTheme.colorScheme.primary) else Modifier
        Box(
            Modifier.defaultMinSize(minWidth = minSize, minHeight = minSize).then(circle).padding(horizontal = 3.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = date.dayOfMonth.toString(),
                fontSize = 12.sp,
                lineHeight = 14.sp,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = if (isToday) MaterialTheme.colorScheme.onPrimary else base,
            )
        }
    }
}

@Composable
private fun EventChip(placed: PlacedEvent, modifier: Modifier = Modifier) {
    val event = placed.event
    val color = Color(NotionColors.argb(event.colorHex))
    val textStyle = Modifier.alpha(if (event.done) 0.55f else 1f)
    val decoration = if (event.done) TextDecoration.LineThrough else null
    if (event.isMultiDay) {
        val shape = RoundedCornerShape(
            topStart = if (placed.continuesBefore) 0.dp else 3.dp,
            bottomStart = if (placed.continuesBefore) 0.dp else 3.dp,
            topEnd = if (placed.continuesAfter) 0.dp else 3.dp,
            bottomEnd = if (placed.continuesAfter) 0.dp else 3.dp,
        )
        Box(modifier.clip(shape).background(color.copy(alpha = 0.28f)), contentAlignment = Alignment.CenterStart) {
            ChipText(chipLabel(event, placed.continuesBefore), decoration, textStyle.padding(horizontal = 3.dp))
        }
    } else {
        Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Start) {
            Box(Modifier.padding(start = 2.dp, end = 2.dp).size(5.dp).clip(CircleShape).background(color))
            ChipText(chipLabel(event, placed.continuesBefore), decoration, textStyle)
        }
    }
}

private val chipTimeFormat = DateTimeFormatter.ofPattern("H:mm")

/** 시간 일정은 시작 시각을 앞에 붙인다(예: "9:00 회의"). 이전 주에서 이어진 막대에는 붙이지 않는다. */
internal fun chipLabel(event: CalendarEvent, continuesBefore: Boolean): String {
    val start = event.startTime
    if (event.isAllDay || start == null || continuesBefore) return event.title
    return "${start.atZone(APP_ZONE).format(chipTimeFormat)} ${event.title}"
}

@Composable
private fun ChipText(text: String, decoration: TextDecoration?, modifier: Modifier) {
    Text(
        text = text,
        modifier = modifier,
        fontSize = 10.sp,
        lineHeight = 12.sp,
        maxLines = 1,
        softWrap = false,
        overflow = TextOverflow.Clip,
        textDecoration = decoration,
        color = MaterialTheme.colorScheme.onSurface,
    )
}
