package com.ysdoing.notioncal.feature.month

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.calendar.Agenda
import com.ysdoing.notioncal.core.data.calendar.AgendaDay
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import com.ysdoing.notioncal.core.data.calendar.DONE_MARK
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.layout.heightIn
import kotlinx.coroutines.flow.MutableStateFlow

private val agendaTime = DateTimeFormatter.ofPattern("H:mm")

/** 목록을 뒤로 늘리는 한계(기준 날짜로부터). 미래 일정이 없을 때 끝없이 늘리지 않게. */
private const val MAX_AHEAD_YEARS = 2L

/**
 * MV-06 목록 보기. 기준 날짜 한 달 전부터 석 달 뒤까지 일정이 있는 날을 이어 보여 주고, 끝에 가까워지면 석 달씩 늘린다.
 * - 처음에는 [initialFocus]로, [jump]가 오면 그 날짜로 스크롤한다(오늘 버튼·위젯 날짜·검색 결과).
 * - 스크롤하면 맨 위 날짜를 [onFocusChange]로 알린다(다른 보기로 바꿀 때 같은 날짜를 유지).
 * - 일정을 누르면 편집([onEventClick]), 날짜를 누르면 날짜 시트([onDayClick]).
 */
@Composable
fun AgendaView(
    initialFocus: LocalDate,
    jump: CalendarJump?,
    today: LocalDate,
    events: (from: LocalDate, to: LocalDate) -> StateFlow<List<CalendarEvent>?>,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    onFocusChange: (LocalDate) -> Unit,
    modifier: Modifier = Modifier,
    textScale: Float = 1f,
    /** EX-02 공휴일(날짜 → 이름). */
    holidays: (from: LocalDate, to: LocalDate) -> StateFlow<Map<LocalDate, String>> = { _, _ -> MutableStateFlow(emptyMap()) },
) {
    var anchor by remember { mutableStateOf(initialFocus) }
    LaunchedEffect(jump) { jump?.let { anchor = it.date } }
    val from = remember(anchor) { anchor.minusMonths(1).withDayOfMonth(1) }
    var until by remember(anchor) { mutableStateOf(anchor.plusMonths(3)) }
    val flow = remember(from, until) { events(from, until) }
    // 새 흐름마다 상태를 새로 만들어 이전 범위의 값을 새 범위의 결과로 쓰지 않는다.
    val loaded = key(flow) { flow.collectAsStateWithLifecycle().value }
    // 범위를 뒤로 늘리는 동안(새 값이 오기 전)에는 이전 목록을 그대로 둔다. 목록이 잠깐 비면 스크롤 위치가 맨 앞으로 돌아간다.
    // 기준 날짜가 바뀌면 이전 목록을 버린다(이전 기준의 목록으로 스크롤 위치를 정하지 않게).
    var lastDays by remember(anchor) { mutableStateOf<List<AgendaDay>?>(null) }
    val holidayFlow = remember(from, until) { holidays(from, until) }
    val holidayNames by holidayFlow.collectAsStateWithLifecycle()
    val days = remember(loaded, from, until, today, anchor, holidayNames) {
        loaded?.let { list -> Agenda.build(list, from, until, today, anchor, holidayNames.keys) }
    } ?: lastDays
    LaunchedEffect(days) { if (days != null) lastDays = days }
    val listState = rememberLazyListState()
    // 기준 날짜가 바뀐 뒤 처음 데이터가 오면 한 번 그 날짜로 스크롤한다.
    var scrolledTo by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(days, anchor) {
        val shown = days ?: return@LaunchedEffect
        if (scrolledTo != anchor) {
            listState.scrollToItem(shown.indexOfFirst { !it.date.isBefore(anchor) }.coerceAtLeast(0))
            scrolledTo = anchor
        }
    }
    val focusChange by rememberUpdatedState(onFocusChange)
    // 기준 날짜 바꾸기와 범위 늘리기는 사용자가 손가락으로 끌었을 때만 한다(처음 자리 잡기나 일정이 적어 끝이 바로 보일 때 말고).
    var userScrolled by remember(anchor) { mutableStateOf(false) }
    LaunchedEffect(listState, anchor) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled = true }
    }
    LaunchedEffect(listState, days) {
        val shown = days ?: return@LaunchedEffect
        snapshotFlow { listState.firstVisibleItemIndex to listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index }
            .collect { (first, last) ->
                if (!userScrolled) return@collect
                shown.getOrNull(first)?.let { focusChange(it.date) }
                if (last != null && last >= shown.size - 3 && until < anchor.plusYears(MAX_AHEAD_YEARS)) until = until.plusMonths(3)
            }
    }
    LazyColumn(modifier.fillMaxSize(), state = listState) {
        items(days.orEmpty(), key = { it.date.toEpochDay() }) { day ->
            AgendaDayRow(day, today, holidayNames[day.date], onDayClick, onEventClick, textScale)
        }
    }
}

@Composable
private fun AgendaDayRow(
    day: AgendaDay,
    today: LocalDate,
    holiday: String?,
    onDayClick: (LocalDate) -> Unit,
    onEventClick: (CalendarEvent) -> Unit,
    textScale: Float,
) {
    val isToday = day.date == today
    val accent = when {
        isToday -> MaterialTheme.colorScheme.primary
        holiday != null -> sundayColor() // EX-02
        else -> MaterialTheme.colorScheme.onSurface
    }
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp)) {
        Column(
            // A11Y-02: 날짜 머리도 누르는 버튼이라 48dp 이상.
            Modifier.width(56.dp).heightIn(min = 48.dp).clickable { onDayClick(day.date) }.padding(top = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                day.date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN),
                style = MaterialTheme.typography.labelSmall,
                color = if (isToday) accent else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                stringResource(R.string.agenda_date, day.date.monthValue, day.date.dayOfMonth),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
                color = accent,
            )
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            if (holiday != null) {
                Text(
                    holiday,
                    style = MaterialTheme.typography.labelMedium,
                    color = sundayColor(),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            if (day.items.isEmpty() && holiday == null) {
                Text(
                    stringResource(R.string.agenda_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            day.items.forEach { (event, continues) -> AgendaEventRow(event, continues, onEventClick, textScale) }
        }
    }
}

@Composable
private fun AgendaEventRow(event: CalendarEvent, continues: Boolean, onEventClick: (CalendarEvent) -> Unit, textScale: Float) {
    val color = Color(NotionColors.argb(event.colorHex))
    val time = when {
        event.isMultiDay -> stringResource(if (continues) R.string.agenda_continues else R.string.agenda_multi_day)
        event.startTime == null -> stringResource(R.string.timegrid_all_day)
        else -> listOfNotNull(event.startTime, event.endTime).joinToString("–") { it.atZone(APP_ZONE).format(agendaTime) }
    }
    val doneLabel = stringResource(R.string.a11y_done)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp) // A11Y-02
            .clickable { onEventClick(event) }
            .semantics { if (event.done) stateDescription = doneLabel }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A11Y-03: 완료는 색 점 대신 체크(같은 색) + 취소선.
        if (event.done) {
            Text(DONE_MARK, color = color, fontSize = scaled(12.sp, textScale), modifier = Modifier.width(8.dp))
        } else {
            Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        }
        // EX-11 강조 일정은 제목을 일정 색 칸에 대비되는 굵은 글자로.
        Box(Modifier.weight(1f).padding(horizontal = 8.dp)) {
            Text(
                event.title,
                modifier = Modifier.emphasisPill(event).alpha(if (event.done) 0.55f else 1f),
                fontSize = scaled(15.sp, textScale),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textDecoration = if (event.done) TextDecoration.LineThrough else null,
                fontWeight = event.emphasisWeight,
                color = if (event.showsEmphasis) event.emphasisTextColor() else Color.Unspecified,
            )
        }
        Text(time, fontSize = scaled(12.sp, textScale), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun scaled(size: TextUnit, scale: Float): TextUnit = (size.value * scale).sp
