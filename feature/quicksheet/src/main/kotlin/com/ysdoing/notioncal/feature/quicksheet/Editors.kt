package com.ysdoing.notioncal.feature.quicksheet

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.quickinput.QuickInputParser
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.EditableEvent
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.PropValue
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.SelectValue
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import kotlinx.coroutines.flow.first
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.ChronoUnit
import java.util.Locale
import com.ysdoing.notioncal.core.data.calendar.Recurrence
import com.ysdoing.notioncal.core.data.calendar.RepeatRule

private val timeFormat = DateTimeFormatter.ofPattern("HH:mm")
private val dateFormat = DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREAN)

/**
 * WI-02 추가 폼: 제목(자동 포커스·키보드), 날짜, 시간(선택), DB.
 * Enter(완료)나 추가 버튼으로 저장한다. 저장하면 입력칸을 비워 연속으로 넣을 수 있다.
 */
@Composable
fun AddForm(
    initialDate: LocalDate,
    sources: List<DataSourceEntity>,
    defaultSourceId: String?,
    onAdd: (NewEvent) -> Unit,
    modifier: Modifier = Modifier,
    autoFocus: Boolean = true,
    /** WI-07 '내일'·'다음 주 월요일'의 기준일. */
    today: LocalDate = LocalDate.now(APP_ZONE),
    /** NS-10 반복 일정 여러 개 추가. null이면 '반복' 칩을 보이지 않는다. */
    onAddRepeated: ((List<NewEvent>) -> Unit)? = null,
) {
    var title by rememberSaveable { mutableStateOf("") }
    var date by rememberSaveable(initialDate) { mutableStateOf(initialDate) }
    var time by rememberSaveable(stateSaver = TimeRangeSaver) { mutableStateOf<TimeRange?>(null) }
    // 칩을 직접 바꾸면 그 값이 빠른 입력 해석보다 앞선다.
    var dateTouched by rememberSaveable(initialDate) { mutableStateOf(false) }
    var timeTouched by rememberSaveable { mutableStateOf(false) }
    var sourceId by rememberSaveable(defaultSourceId) { mutableStateOf(defaultSourceId) }
    var repeat by rememberSaveable(stateSaver = RepeatSaver) { mutableStateOf<RepeatRule?>(null) }
    var confirming by remember { mutableStateOf<List<NewEvent>?>(null) }
    val focus = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    val windowInfo = LocalWindowInfo.current
    // WI-01: 시트가 뜨면 바로 키보드. 창이 포커스를 얻기 전에 요청하면 무시되므로(실기기 확인) 창 포커스를 기다린다.
    LaunchedEffect(autoFocus) {
        if (!autoFocus) return@LaunchedEffect
        snapshotFlow { windowInfo.isWindowFocused }.first { it }
        focus.requestFocus()
        keyboard?.show()
    }

    if (sources.isEmpty()) {
        Text(stringResource(R.string.qs_no_database), modifier.padding(vertical = 8.dp), color = MaterialTheme.colorScheme.error)
        return
    }
    // WI-07: '내일 3시 회의' → 날짜·시간은 칩으로, 남은 글자는 제목으로.
    val parsed = remember(title, today) { QuickInputParser.parse(title, today) }
    val shownDate = parsed.date?.takeUnless { dateTouched } ?: date
    val shownEnd = parsed.endDate?.takeUnless { dateTouched }
    val shownTime = parsed.start?.takeUnless { timeTouched }?.let { s -> TimeRange(s, parsed.end ?: s.plusHours(1)) } ?: time
    val submit = {
        val target = sourceId ?: sources.first().dataSourceId
        if (title.isNotBlank()) {
            val cleanTitle = parsed.title.takeIf { parsed.hasSchedule && it.isNotBlank() } ?: title.trim()
            val rule = repeat
            if (rule != null && onAddRepeated != null) {
                // NS-10: 날짜를 펼쳐 만들기 전에 개수·미리보기를 확인받는다. 기간 일정은 길이를 유지한다.
                val span = Recurrence.spanDays(shownDate, shownEnd)
                confirming = Recurrence.expand(shownDate, rule).map { day ->
                    NewEvent(cleanTitle, day, shownTime, target, shownEnd?.let { day.plusDays(span) })
                }
            } else {
                onAdd(NewEvent(cleanTitle, shownDate, shownTime, target, shownEnd))
                title = ""
                dateTouched = false
                timeTouched = false
            }
        }
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = title,
                onValueChange = { title = it },
                modifier = Modifier.weight(1f).focusRequester(focus),
                placeholder = { Text(stringResource(R.string.qs_title_hint)) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { submit() }),
            )
            Button(onClick = submit, enabled = title.isNotBlank()) { Text(stringResource(R.string.qs_add)) }
        }
        if (parsed.hasSchedule && parsed.title.isNotBlank()) {
            Text(
                stringResource(R.string.qs_parsed_title, parsed.title),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
        }
        // 1줄: 날짜·시간. 글꼴을 키워도 잘리지 않게 넘치면 다음 줄로 내린다.
        ChipFlow {
            DateChip(shownDate, shownEnd) {
                date = it
                dateTouched = true
            }
            TimeChip(shownTime) {
                time = it
                timeTouched = true
            }
        }
        // 2줄: 반복·DB 선택. DB가 많아도 화면 밖으로 밀려 잘리지 않게 가로로 스크롤한다.
        if (onAddRepeated != null || sources.size > 1) {
            ChipScrollRow {
                if (onAddRepeated != null) RepeatChip(repeat, shownDate) { repeat = it }
                if (sources.size > 1) {
                    sources.forEach { s ->
                        FilterChip(selected = s.dataSourceId == (sourceId ?: sources.first().dataSourceId), onClick = { sourceId = s.dataSourceId }, label = {
                            Text(s.name, maxLines = 1)
                        })
                    }
                }
            }
        }
    }
    confirming?.let { events ->
        RepeatConfirmDialog(
            events = events,
            onConfirm = {
                onAddRepeated?.invoke(events)
                confirming = null
                repeat = null
                title = ""
                dateTouched = false
                timeTouched = false
            },
            onDismiss = { confirming = null },
        )
    }
}

/** WI-01·03: 그날 일정 목록. 체크박스로 완료, 행을 누르면 편집. */
@Composable
fun DayList(
    events: List<CalendarEvent>,
    onToggle: (CalendarEvent, Boolean) -> Unit,
    onOpen: (CalendarEvent) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (events.isEmpty()) {
        Text(
            stringResource(R.string.qs_empty),
            modifier.padding(vertical = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    LazyColumn(modifier.heightIn(max = 360.dp)) {
        items(events, key = { it.pageId }) { event ->
            val toggleLabel = stringResource(R.string.qs_toggle_done, event.title)
            Row(
                Modifier.fillMaxWidth().clickable { onOpen(event) }.padding(vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(
                    checked = event.done,
                    onCheckedChange = { onToggle(event, it) },
                    modifier = Modifier.semantics { contentDescription = toggleLabel },
                )
                Box(Modifier.size(8.dp).clip(CircleShape).background(Color(NotionColors.argb(event.colorHex))))
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
                    // EX-11 강조 일정(예: 우선순위 '1st')은 제목을 일정 색 칸에 대비되는 굵은 글자로.
                    val emphasized = event.showsEmphasis
                    Text(
                        event.title,
                        modifier = if (emphasized) {
                            Modifier.clip(RoundedCornerShape(4.dp))
                                .background(Color(NotionColors.argb(event.colorHex)))
                                .padding(horizontal = 6.dp, vertical = 1.dp)
                        } else {
                            Modifier
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        textDecoration = if (event.done) TextDecoration.LineThrough else null,
                        fontWeight = if (emphasized) FontWeight.Bold else null,
                        color = when {
                            emphasized -> Color(NotionColors.onColor(event.colorHex))
                            event.done -> MaterialTheme.colorScheme.onSurfaceVariant
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        listOfNotNull(timeText(event), if (event.pending) stringResource(R.string.qs_pending) else null).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun timeText(event: CalendarEvent): String {
    val start = event.startTime ?: return stringResource(R.string.qs_all_day)
    val from = start.atZone(APP_ZONE).format(timeFormat)
    val to = event.endTime?.atZone(APP_ZONE)?.format(timeFormat)
    return if (to != null) "$from–$to" else from
}

/**
 * WI-05 편집 폼: 제목·날짜·시간·완료·색 기준 select, 설정에서 고른 추가 속성(EX-07), 노션에서 열기, 삭제(휴지통).
 * 바뀐 필드만 저장한다.
 */
@Composable
fun EventEditorForm(
    item: EditableEvent,
    onSave: (EventChange) -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val event = item.event
    val context = LocalContext.current
    val originalTime = event.startTime?.let { s ->
        TimeRange(s.atZone(APP_ZONE).toLocalTime(), (event.endTime ?: s.plusSeconds(3600)).atZone(APP_ZONE).toLocalTime())
    }
    var title by rememberSaveable(event.pageId) { mutableStateOf(event.title) }
    var date by rememberSaveable(event.pageId) { mutableStateOf(event.startDate) }
    var time by rememberSaveable(event.pageId, stateSaver = TimeRangeSaver) { mutableStateOf(originalTime) }
    var done by rememberSaveable(event.pageId) { mutableStateOf(event.done) }
    var optionId by rememberSaveable(event.pageId) { mutableStateOf(item.colorOptionId) }
    var propEdits by rememberSaveable(event.pageId, stateSaver = PropEditsSaver) { mutableStateOf(emptyMap<String, PropValue>()) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    Column(modifier, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
            value = title,
            onValueChange = { title = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text(stringResource(R.string.qs_title_hint)) },
            singleLine = true,
            // 제목에 노션 언급(@)이 있으면, 바꿔 저장할 때 언급이 글자로 바뀐다고 알린다(제목을 그대로 두면 언급은 유지).
            supportingText = if (item.titleHasMention) {
                { Text(stringResource(R.string.qs_title_mention_warning)) }
            } else {
                null
            },
            isError = item.titleHasMention && title.trim() != event.title,
        )
        ChipFlow {
            DateChip(date) { date = it }
            TimeChip(time) { time = it }
        }
        // 글자를 눌러도 체크되도록 줄 전체를 토글로 만든다(터치 영역 확대).
        Row(
            Modifier.toggleable(value = done, role = Role.Checkbox, onValueChange = { done = it }),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Checkbox(checked = done, onCheckedChange = null)
            Text(stringResource(R.string.qs_done), Modifier.padding(end = 12.dp))
        }
        if (item.colorOptions.isNotEmpty()) {
            ChipScrollRow {
                item.colorOptions.forEach { option ->
                    FilterChip(
                        selected = option.id == optionId,
                        onClick = { optionId = if (optionId == option.id) null else option.id },
                        label = { Text(option.name) },
                        leadingIcon = {
                            Box(Modifier.size(8.dp).clip(CircleShape).background(Color(NotionColors.argb(NotionColors.hexFor(option.color) ?: "#787774"))))
                        },
                    )
                }
            }
        }
        item.extraProps.forEach { prop ->
            ExtraPropEditor(
                prop = prop,
                value = propEdits[prop.id] ?: item.extraValues[prop.id] ?: PropValue(),
                onChange = { propEdits = propEdits + (prop.id to it) },
                key = "${event.pageId}/${prop.id}",
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                val dateChanged = date != event.startDate || time != originalTime
                onSave(
                    EventChange(
                        title = title.trim().takeIf { it != event.title && it.isNotBlank() },
                        date = if (dateChanged) dateValueOf(date, time).let { v ->
                            // 기간 일정은 길이를 유지한다.
                            val span = event.endDate?.let { ChronoUnit.DAYS.between(event.startDate, it) } ?: 0
                            if (time == null && span > 0) v.copy(endDate = date.plusDays(span).toString()) else v
                        } else null,
                        done = done.takeIf { it != event.done },
                        select = SelectValue(optionId).takeIf { optionId != item.colorOptionId },
                        props = propEdits.filter { (id, value) -> value != (item.extraValues[id] ?: PropValue()) }.takeIf { it.isNotEmpty() },
                    ),
                )
            }) { Text(stringResource(R.string.qs_save)) }
            if (event.url.isNotBlank()) {
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, event.url.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }) { Text(stringResource(R.string.qs_open_in_notion)) }
            }
            TextButton(onClick = { confirmDelete = true }) {
                Text(stringResource(R.string.qs_delete), color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.qs_delete_confirm_title)) },
            text = { Text(stringResource(R.string.qs_delete_confirm_body)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    onDelete()
                }) { Text(stringResource(R.string.qs_delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.qs_cancel)) } },
        )
    }
}

/** 칩 줄: 넘치면 다음 줄로 내린다(날짜·시간처럼 꼭 보여야 하는 칩). */
@Composable
private fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 칩 줄: 개수가 정해지지 않은 칩(DB·옵션)은 화면 밖으로 잘리지 않게 가로로 스크롤한다. */
@Composable
private fun ChipScrollRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DateChip(date: LocalDate, endDate: LocalDate? = null, onPick: (LocalDate) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    val label = endDate?.let { "${date.format(dateFormat)} – ${it.format(dateFormat)}" } ?: date.format(dateFormat)
    AssistChip(onClick = { open = true }, label = { Text(label) })
    if (open) {
        // DatePicker는 UTC 자정 기준 epoch ms를 쓴다(기기 시간대와 무관하게 날짜가 밀리지 않게, NS-07).
        val state = rememberDatePickerState(initialSelectedDateMillis = date.atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onPick(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate()) }
                    open = false
                }) { Text(stringResource(R.string.qs_save)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(R.string.qs_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/**
 * PF-06: 프로세스가 죽었다 살아나도(활동 유지 안 함·메모리 회수) 입력 중인 시간을 지킨다.
 * null(종일)도 빈 문자열로 저장해, 복원 때 처음 값으로 되돌아가지 않게 한다.
 */
internal val TimeRangeSaver = Saver<TimeRange?, String>(
    save = { range -> range?.let { "${it.start}|${it.end}" } ?: "" },
    restore = { saved ->
        saved.takeIf { it.isNotEmpty() }?.split('|')?.let { (start, end) -> TimeRange(LocalTime.parse(start), LocalTime.parse(end)) }
    },
)

/**
 * '종일' 스위치: 켜면 종일 일정, 끄면 시작·종료 시간 칩(기본 9:00–10:00)이 나온다. 다른 캘린더 앱과 같은 방향이다
 * (예전에는 꺼진 스위치 옆에 '종일'이 보여 '종일이 꺼짐'으로 읽혔다, 디자인 리뷰 2026-10-16).
 * 글자까지 줄 전체를 누를 수 있다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TimeChip(time: TimeRange?, onChange: (TimeRange?) -> Unit) {
    var editing by rememberSaveable { mutableStateOf<String?>(null) } // "start" | "end"
    val allDay = time == null
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(8.dp))
                .toggleable(value = allDay, role = Role.Switch) { on ->
                    onChange(if (on) null else TimeRange(LocalTime.of(9, 0), LocalTime.of(10, 0)))
                }
                .padding(start = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(stringResource(R.string.qs_all_day), style = MaterialTheme.typography.bodyMedium)
            Switch(checked = allDay, onCheckedChange = null)
        }
        if (time != null) {
            AssistChip(onClick = { editing = "start" }, label = { Text(time.start.format(timeFormat)) })
            Text("–")
            AssistChip(onClick = { editing = "end" }, label = { Text(time.end.format(timeFormat)) })
        }
    }
    val target = editing
    if (target != null && time != null) {
        val initial = if (target == "start") time.start else time.end
        val state = rememberTimePickerState(initialHour = initial.hour, initialMinute = initial.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { editing = null },
            text = { TimePicker(state = state) },
            confirmButton = {
                TextButton(onClick = {
                    val picked = LocalTime.of(state.hour, state.minute)
                    onChange(
                        if (target == "start") {
                            // 시작을 옮기면 길이를 유지해 종료도 옮긴다.
                            val length = Duration.between(time.start, time.end).takeIf { !it.isNegative && !it.isZero }
                                ?: Duration.ofHours(1)
                            TimeRange(picked, picked.plus(length))
                        } else {
                            time.copy(end = picked)
                        },
                    )
                    editing = null
                }) { Text(stringResource(R.string.qs_save)) }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text(stringResource(R.string.qs_cancel)) } },
        )
    }
}

/** 날짜 시트 제목. */
@Composable
fun dayTitle(date: LocalDate): String =
    stringResource(R.string.qs_day_title, date.monthValue, date.dayOfMonth, date.dayOfWeek.getDisplayName(TextStyle.SHORT, Locale.KOREAN))
