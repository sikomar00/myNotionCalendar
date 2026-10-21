package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** 칩 줄: 넘치면 다음 줄로 내린다(날짜·시간처럼 꼭 보여야 하는 칩). */
@Composable
internal fun ChipFlow(content: @Composable () -> Unit) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

/** 칩 줄: 개수가 정해지지 않은 칩(DB·옵션)은 화면 밖으로 잘리지 않게 가로로 스크롤한다. */
@Composable
internal fun ChipScrollRow(content: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) { content() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun DateChip(date: LocalDate, endDate: LocalDate? = null, onPick: (LocalDate) -> Unit) {
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
internal fun TimeChip(time: TimeRange?, onChange: (TimeRange?) -> Unit) {
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
