package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.calendar.Recurrence
import com.ysdoing.notioncal.core.data.calendar.RepeatFreq
import com.ysdoing.notioncal.core.data.calendar.RepeatRule
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.TextStyle
import java.util.Locale

/** PF-06: 회전·프로세스 복원 뒤에도 고른 반복을 지킨다(null = 반복 안 함). */
internal val RepeatSaver = Saver<RepeatRule?, String>(
    save = { it?.encode().orEmpty() },
    restore = { saved -> saved.takeIf { it.isNotEmpty() }?.let(RepeatRule::decode) },
)

private fun DayOfWeek.short(): String = getDisplayName(TextStyle.SHORT, Locale.KOREAN)

/** "매주 월·수, 12/31까지", "2일마다, 5번" */
@Composable
internal fun repeatSummary(rule: RepeatRule): String {
    val every = when (rule.freq) {
        RepeatFreq.DAILY -> if (rule.interval == 1) stringResource(R.string.qs_repeat_daily) else stringResource(R.string.qs_repeat_every_days, rule.interval)
        RepeatFreq.WEEKLY -> if (rule.interval == 1) stringResource(R.string.qs_repeat_weekly) else stringResource(R.string.qs_repeat_every_weeks, rule.interval)
        RepeatFreq.MONTHLY -> if (rule.interval == 1) stringResource(R.string.qs_repeat_monthly) else stringResource(R.string.qs_repeat_every_months, rule.interval)
        RepeatFreq.YEARLY -> if (rule.interval == 1) stringResource(R.string.qs_repeat_yearly) else stringResource(R.string.qs_repeat_every_years, rule.interval)
    }
    val days = if (rule.freq == RepeatFreq.WEEKLY && rule.weekdays.isNotEmpty()) " " + rule.weekdays.sorted().joinToString("·") { it.short() } else ""
    val count = rule.count
    val until = rule.until
    val end = when {
        count != null -> stringResource(R.string.qs_repeat_end_count, count)
        until != null -> stringResource(R.string.qs_repeat_end_until, until.monthValue, until.dayOfMonth)
        else -> ""
    }
    return listOf(every + days, end).filter { it.isNotBlank() }.joinToString(", ")
}

/** NS-10 '반복' 칩. 누르면 규칙 대화상자. */
@Composable
internal fun RepeatChip(rule: RepeatRule?, start: LocalDate, onChange: (RepeatRule?) -> Unit) {
    var open by rememberSaveable { mutableStateOf(false) }
    AssistChip(
        onClick = { open = true },
        label = { Text(rule?.let { repeatSummary(it) } ?: stringResource(R.string.qs_repeat_none), maxLines = 1) },
    )
    if (open) {
        RepeatDialog(
            initial = rule ?: RepeatRule(RepeatFreq.WEEKLY, count = DEFAULT_COUNT),
            start = start,
            onDone = { picked ->
                open = false
                onChange(picked)
            },
            onDismiss = { open = false },
        )
    }
}

private const val DEFAULT_COUNT = 10

/** 종류(매일·매주·매월·매년)·간격·요일·끝(횟수·날짜). '반복 안 함'으로 끌 수 있다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun RepeatDialog(initial: RepeatRule, start: LocalDate, onDone: (RepeatRule?) -> Unit, onDismiss: () -> Unit) {
    var freq by rememberSaveable { mutableStateOf(initial.freq) }
    var interval by rememberSaveable { mutableIntStateOf(initial.interval) }
    var weekdays by rememberSaveable { mutableStateOf(initial.weekdays.ifEmpty { setOf(start.dayOfWeek) }.map { it.name }.toSet()) }
    var byCount by rememberSaveable { mutableStateOf(initial.until == null) }
    var countText by rememberSaveable { mutableStateOf((initial.count ?: DEFAULT_COUNT).toString()) }
    var until by rememberSaveable { mutableStateOf((initial.until ?: start.plusMonths(3)).toString()) }
    var pickingUntil by rememberSaveable { mutableStateOf(false) }
    val count = countText.toIntOrNull()
    val valid = !byCount || (count != null && count in 1..Recurrence.MAX)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qs_repeat_title)) },
        text = {
            // 작은 화면·큰 글꼴에서도 끝 조건까지 닿도록 내용을 스크롤한다.
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    val options = listOf(
                        RepeatFreq.DAILY to R.string.qs_repeat_freq_day,
                        RepeatFreq.WEEKLY to R.string.qs_repeat_freq_week,
                        RepeatFreq.MONTHLY to R.string.qs_repeat_freq_month,
                        RepeatFreq.YEARLY to R.string.qs_repeat_freq_year,
                    )
                    options.forEachIndexed { index, (value, label) ->
                        // 체크 아이콘 없이 색으로만 선택을 보인다(좁은 대화상자에서 글자가 줄바꿈되지 않게).
                        SegmentedButton(
                            selected = freq == value,
                            onClick = { freq = value },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                            icon = {},
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
                }
                // 간격: "− 2주마다 +"처럼 단위가 함께 보인다.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    val less = stringResource(R.string.qs_repeat_interval_less)
                    val more = stringResource(R.string.qs_repeat_interval_more)
                    OutlinedIconButton(
                        onClick = { interval = (interval - 1).coerceAtLeast(1) },
                        enabled = interval > 1,
                        modifier = Modifier.semantics { contentDescription = less },
                    ) { Text("−") }
                    Text(
                        intervalLabel(freq, interval),
                        modifier = Modifier.weight(1f),
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.titleMedium,
                    )
                    OutlinedIconButton(
                        onClick = { interval = (interval + 1).coerceAtMost(Recurrence.MAX_INTERVAL) },
                        enabled = interval < Recurrence.MAX_INTERVAL,
                        modifier = Modifier.semantics { contentDescription = more },
                    ) { Text("+") }
                }
                if (freq == RepeatFreq.WEEKLY) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        DayOfWeek.entries.forEach { day ->
                            val on = day.name in weekdays
                            FilterChip(
                                selected = on,
                                onClick = { weekdays = if (on && weekdays.size > 1) weekdays - day.name else weekdays + day.name },
                                label = { Text(day.short()) },
                            )
                        }
                    }
                }
                // 끝: 라디오 두 줄. 고르지 않은 줄의 입력은 흐리게 둔다.
                Text(stringResource(R.string.qs_repeat_end_title), style = MaterialTheme.typography.labelLarge)
                Column(Modifier.selectableGroup()) {
                    EndOption(selected = byCount, label = stringResource(R.string.qs_repeat_by_count), onSelect = { byCount = true }) {
                        OutlinedTextField(
                            value = countText,
                            onValueChange = { countText = it.filter(Char::isDigit).take(3) },
                            modifier = Modifier.width(96.dp),
                            enabled = byCount,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            suffix = { Text(stringResource(R.string.qs_repeat_count_suffix)) },
                            isError = byCount && !valid,
                        )
                    }
                    if (byCount) {
                        Text(
                            stringResource(R.string.qs_repeat_max, Recurrence.MAX),
                            modifier = Modifier.padding(start = 48.dp),
                            style = MaterialTheme.typography.bodySmall,
                            color = if (valid) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                        )
                    }
                    EndOption(selected = !byCount, label = stringResource(R.string.qs_repeat_by_date), onSelect = { byCount = false }) {
                        val date = LocalDate.parse(until)
                        AssistChip(
                            onClick = {
                                byCount = false
                                pickingUntil = true
                            },
                            enabled = !byCount,
                            label = { Text(stringResource(R.string.qs_repeat_until_date, date.year, date.monthValue, date.dayOfMonth), maxLines = 1) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = {
                    onDone(
                        RepeatRule(
                            freq = freq,
                            interval = interval,
                            weekdays = if (freq == RepeatFreq.WEEKLY) weekdays.map(DayOfWeek::valueOf).toSet() else emptySet(),
                            until = if (byCount) null else LocalDate.parse(until),
                            count = if (byCount) count else null,
                        ),
                    )
                },
            ) { Text(stringResource(R.string.qs_save)) }
        },
        dismissButton = {
            TextButton(onClick = { onDone(null) }) { Text(stringResource(R.string.qs_repeat_off)) }
        },
    )
    if (pickingUntil) {
        val state = rememberDatePickerState(initialSelectedDateMillis = LocalDate.parse(until).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli())
        DatePickerDialog(
            onDismissRequest = { pickingUntil = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { until = Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString() }
                    pickingUntil = false
                }) { Text(stringResource(R.string.qs_save)) }
            },
            dismissButton = { TextButton(onClick = { pickingUntil = false }) { Text(stringResource(R.string.qs_cancel)) } },
        ) { DatePicker(state = state) }
    }
}

/** "1일마다"·"2주마다"·"1개월마다"·"1년마다". */
@Composable
private fun intervalLabel(freq: RepeatFreq, interval: Int): String = when (freq) {
    RepeatFreq.DAILY -> stringResource(R.string.qs_repeat_every_days, interval)
    RepeatFreq.WEEKLY -> stringResource(R.string.qs_repeat_every_weeks, interval)
    RepeatFreq.MONTHLY -> stringResource(R.string.qs_repeat_every_months, interval)
    RepeatFreq.YEARLY -> stringResource(R.string.qs_repeat_every_years, interval)
}

/** 반복 끝 조건 한 줄: 줄 전체가 라디오 선택 영역이고, 오른쪽에 그 조건의 입력(횟수·날짜)이 붙는다. */
@Composable
private fun EndOption(selected: Boolean, label: String, onSelect: () -> Unit, trailing: @Composable () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .selectable(selected = selected, onClick = onSelect, role = Role.RadioButton),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        Text(label, modifier = Modifier.weight(1f), maxLines = 1)
        trailing()
    }
}

/** NS-10 만들기 전 확인: 개수와 앞 5개·마지막 날짜. 노션에는 페이지가 따로따로 만들어진다. */
@Composable
internal fun RepeatConfirmDialog(events: List<NewEvent>, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    fun label(date: LocalDate) = "${date.monthValue}/${date.dayOfMonth}(${date.dayOfWeek.short()})"
    val preview = buildString {
        append(events.take(5).joinToString(", ") { label(it.date) })
        if (events.size > 5) append(", … ").append(label(events.last().date))
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.qs_repeat_confirm_title, events.size)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(events.firstOrNull()?.title.orEmpty(), style = MaterialTheme.typography.titleSmall)
                Text(preview)
                Text(
                    stringResource(R.string.qs_repeat_confirm_note, events.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(R.string.qs_repeat_create, events.size)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.qs_cancel)) } },
    )
}
