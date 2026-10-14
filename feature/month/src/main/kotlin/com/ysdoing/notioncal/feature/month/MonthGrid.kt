package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.drag
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.zIndex
import com.ysdoing.notioncal.core.data.calendar.WeekLayoutResult
import java.time.temporal.ChronoUnit
import kotlin.math.floor
import kotlin.math.roundToInt
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
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.calendar.PlacedEvent
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import com.ysdoing.notioncal.core.data.calendar.chipLabel
import com.ysdoing.notioncal.core.data.calendar.gridStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale

@Immutable
data class MonthGridState(
    val month: YearMonth,
    val weekStart: DayOfWeek,
    val today: LocalDate,
    val events: List<CalendarEvent>,
)

private val SundayColor = Color(0xFFE03E3E)
private val SaturdayColor = Color(0xFF2F80ED)

/**
 * MV-07 끌어 옮기기 진행 상태. [position]은 그리드 기준 좌표(px), [target]은 손가락 아래 날짜.
 * [origin]은 처음 누른 칸의 날짜라 여러 날 일정의 가운데를 잡아도 옮긴 날 수만큼 통째로 움직인다.
 */
@Immutable
internal data class GridDrag(
    val event: CalendarEvent,
    val origin: LocalDate,
    val position: Offset,
    val target: LocalDate,
) {
    val days: Long get() = ChronoUnit.DAYS.between(origin, target)
}

/**
 * 월 그리드(MV-01·02·05). 데이터는 호출부가 Room에서 읽어 넘긴다.
 * MV-07: 일정을 길게 눌러 다른 칸에 놓으면 [onMove]가 옮길 날 수와 함께 불린다.
 */
@Composable
fun MonthGrid(
    state: MonthGridState,
    onDayClick: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    onMove: (CalendarEvent, Long) -> Unit = { _, _ -> },
) {
    val start = gridStart(state.month, state.weekStart)
    var drag by remember { mutableStateOf<GridDrag?>(null) }
    var headerHeight by remember { mutableIntStateOf(0) }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val weekHeight = (constraints.maxHeight - headerHeight) / 6f
        fun dateAt(position: Offset): LocalDate {
            val week = floor((position.y - headerHeight) / weekHeight).toInt().coerceIn(0, 5)
            val col = floor(position.x / (width / 7)).toInt().coerceIn(0, 6)
            return start.plusDays(week * 7L + col)
        }
        Column(Modifier.fillMaxSize()) {
            WeekdayHeader(state.weekStart, Modifier.onSizeChanged { headerHeight = it.height })
            repeat(6) { week ->
                val weekStartDate = start.plusWeeks(week.toLong())
                WeekRow(
                    weekStart = weekStartDate,
                    month = state.month,
                    today = state.today,
                    events = state.events,
                    onDayClick = onDayClick,
                    drag = drag,
                    toGrid = { local -> Offset(local.x, headerHeight + week * weekHeight + local.y) },
                    onDragStart = { event, origin, position -> drag = GridDrag(event, origin, position, dateAt(position)) },
                    onDragMove = { position -> drag = drag?.copy(position = position, target = dateAt(position)) },
                    onDragEnd = {
                        drag?.let { d -> if (d.days != 0L) onMove(d.event, d.days) }
                        drag = null
                    },
                    onDragCancel = { drag = null },
                    // 끄는 칩이 아래 주 행에 가려지지 않게 잡은 주를 위에 그린다.
                    modifier = Modifier.weight(1f).zIndex(if (drag?.event != null && drag?.origin?.let { it >= weekStartDate && it < weekStartDate.plusWeeks(1) } == true) 1f else 0f),
                )
            }
        }
        drag?.let { d -> DraggedChip(d, cellWidth = width / 7) }
    }
}

@Composable
private fun WeekdayHeader(weekStart: DayOfWeek, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
    drag: GridDrag?,
    toGrid: (Offset) -> Offset,
    onDragStart: (CalendarEvent, LocalDate, Offset) -> Unit,
    onDragMove: (Offset) -> Unit,
    onDragEnd: () -> Unit,
    onDragCancel: () -> Unit,
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
        val hitTarget = remember(layout, density, maxWidth) {
            ChipHitTest(
                layout = layout,
                cellWidthPx = with(density) { cellWidth.toPx() },
                labelHeightPx = with(density) { labelHeight.toPx() },
                laneHeightPx = with(density) { laneHeight.toPx() },
            )
        }
        val currentToGrid by rememberUpdatedState(toGrid)
        val currentDayClick by rememberUpdatedState(onDayClick)
        val callbacks by rememberUpdatedState(DragCallbacks(onDragStart, onDragMove, onDragEnd, onDragCancel))

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        // 놓을 칸 강조(MV-07)
        drag?.target?.let { target ->
            val col = ChronoUnit.DAYS.between(weekStart, target)
            if (col in 0..6) {
                Box(
                    Modifier
                        .offset(x = cellWidth * col.toInt())
                        .width(cellWidth)
                        .fillMaxHeight()
                        .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.12f)),
                )
            }
        }
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
                    .alpha(if (drag?.event?.pageId == placed.event.pageId) 0.35f else 1f)
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
        // 칸마다 날짜와 일정 수를 읽게 한다(TalkBack). 탭·길게 눌러 끌기는 아래 행 전체 터치 처리기가 맡는다.
        Row(
            Modifier
                .fillMaxSize()
                .pointerInput(weekStart, hitTarget) {
                    awaitEachGesture {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        val longPress = awaitLongPressOrCancellation(down.id)
                        if (longPress == null) {
                            // 길게 누르기 전에 손을 뗐으면 탭. 페이저·새로고침이 가져간 제스처(소비됨)는 무시한다.
                            val up = currentEvent.changes.firstOrNull { it.id == down.id }
                            if (up != null && !up.pressed && !up.isConsumed) {
                                up.consume()
                                currentDayClick(weekStart.plusDays(hitTarget.colAt(down.position.x).toLong()))
                            }
                            return@awaitEachGesture
                        }
                        val hit = hitTarget.eventAt(longPress.position) ?: return@awaitEachGesture
                        longPress.consume()
                        val origin = weekStart.plusDays(hitTarget.colAt(longPress.position.x).toLong())
                        var local = longPress.position
                        callbacks.start(hit, origin, currentToGrid(local))
                        val completed = drag(longPress.id) { change ->
                            // 소비한 뒤에는 positionChange()가 0이므로 위치를 직접 읽는다.
                            local = change.position
                            change.consume()
                            callbacks.move(currentToGrid(local))
                        }
                        if (completed) callbacks.end() else callbacks.cancel()
                    }
                },
        ) {
            repeat(7) { col ->
                val date = weekStart.plusDays(col.toLong())
                val count = events.count { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }
                val description = stringResource(R.string.month_day_a11y, date.monthValue, date.dayOfMonth, count)
                Spacer(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .semantics {
                            contentDescription = description
                            onClick {
                                currentDayClick(date)
                                true
                            }
                        },
                )
            }
        }
    }
}

private data class DragCallbacks(
    val start: (CalendarEvent, LocalDate, Offset) -> Unit,
    val move: (Offset) -> Unit,
    val end: () -> Unit,
    val cancel: () -> Unit,
)

/** 주 행 안의 좌표(px) → 칸·일정. */
internal class ChipHitTest(
    private val layout: WeekLayoutResult,
    private val cellWidthPx: Float,
    private val labelHeightPx: Float,
    private val laneHeightPx: Float,
) {
    fun colAt(x: Float): Int = floor(x / cellWidthPx).toInt().coerceIn(0, 6)

    /** 날짜 숫자 줄이나 +N 줄, 빈 자리를 누르면 null. */
    fun eventAt(position: Offset): CalendarEvent? {
        if (position.y < labelHeightPx) return null
        val lane = floor((position.y - labelHeightPx) / laneHeightPx).toInt()
        val col = colAt(position.x)
        return layout.placed.firstOrNull { it.lane == lane && col >= it.startCol && col < it.startCol + it.span }?.event
    }
}

/** 끄는 동안 손가락 위에 보이는 칩. */
@Composable
private fun DraggedChip(drag: GridDrag, cellWidth: Float) {
    val density = LocalDensity.current
    val color = Color(NotionColors.argb(drag.event.colorHex))
    val height = with(density) { 18.sp.toPx() }
    Box(
        Modifier
            .offset { IntOffset((drag.position.x - cellWidth / 2).roundToInt(), (drag.position.y - height * 1.6f).roundToInt()) }
            .width(with(density) { cellWidth.toDp() } * 1.4f)
            .height(with(density) { height.toDp() })
            .clip(RoundedCornerShape(4.dp))
            .background(color.copy(alpha = 0.9f))
            .padding(horizontal = 4.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(drag.event.title, fontSize = 11.sp, maxLines = 1, softWrap = false, overflow = TextOverflow.Ellipsis, color = Color.White)
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
