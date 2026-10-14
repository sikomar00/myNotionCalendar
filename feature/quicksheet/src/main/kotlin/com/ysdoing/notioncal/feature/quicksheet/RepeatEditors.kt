package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
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
        label = { Text(rule?.let { repeatSummary(it) } ?: stringResource(R.string.qs_repeat_none)) },
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
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                SingleChoiceSegmentedButtonRow {
                    val options = listOf(
                        RepeatFreq.DAILY to R.string.qs_repeat_freq_day,
                        RepeatFreq.WEEKLY to R.string.qs_repeat_freq_week,
                        RepeatFreq.MONTHLY to R.string.qs_repeat_freq_month,
                        RepeatFreq.YEARLY to R.string.qs_repeat_freq_year,
                    )
                    options.forEachIndexed { index, (value, label) ->
                        SegmentedButton(
                            selected = freq == value,
                            onClick = { freq = value },
                            shape = SegmentedButtonDefaults.itemShape(index, options.size),
                        ) { Text(stringResource(label)) }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { interval = (interval - 1).coerceAtLeast(1) }, enabled = interval > 1) { Text("−") }
                    Text(stringResource(R.string.qs_repeat_interval, interval))
                    TextButton(onClick = { interval = (interval + 1).coerceAtMost(Recurrence.MAX_INTERVAL) }, enabled = interval < Recurrence.MAX_INTERVAL) { Text("+") }
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
                SingleChoiceSegmentedButtonRow {
                    SegmentedButton(selected = byCount, onClick = { byCount = true }, shape = SegmentedButtonDefaults.itemShape(0, 2)) {
                        Text(stringResource(R.string.qs_repeat_by_count))
                    }
                    SegmentedButton(selected = !byCount, onClick = { byCount = false }, shape = SegmentedButtonDefaults.itemShape(1, 2)) {
                        Text(stringResource(R.string.qs_repeat_by_date))
                    }
                }
                if (byCount) {
                    OutlinedTextField(
                        value = countText,
                        onValueChange = { countText = it.filter(Char::isDigit).take(3) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        label = { Text(stringResource(R.string.qs_repeat_count_label)) },
                        supportingText = { Text(stringResource(R.string.qs_repeat_max, Recurrence.MAX)) },
                        isError = !valid,
                    )
                } else {
                    val date = LocalDate.parse(until)
                    TextButton(onClick = { pickingUntil = true }) {
                        Text(stringResource(R.string.qs_repeat_until_button, date.year, date.monthValue, date.dayOfMonth))
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
