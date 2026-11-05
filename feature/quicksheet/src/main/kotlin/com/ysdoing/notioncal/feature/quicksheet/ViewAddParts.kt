package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** AUTO는 사용자가 아직 안 골랐다(글에서 읽은 날짜, 없으면 양식의 오늘 기본값). EMPTY는 '날짜 없음'을 직접 골랐다. */
internal enum class DateChoice { AUTO, EMPTY, TODAY, TOMORROW, PICK }

/**
 * 날짜 칩 줄(없음·오늘·내일·직접 고르기). [noDate]는 '날짜 없음'이 선택돼 보이는가(글에서 읽은 날짜가 쓰이면 그 칩이 선택이라 아니다),
 * [todayAuto]는 고르지 않았지만 양식의 기본값으로 오늘이 쓰여 '오늘'이 선택돼 보이는가.
 * 없음·오늘·내일은 [onChoose]로, 직접 고르기는 [onPick](대화상자를 연다)으로 알린다.
 */
@Composable
internal fun DateChips(choice: DateChoice, noDate: Boolean, todayAuto: Boolean, pickedIso: String?, onPick: () -> Unit, onChoose: (DateChoice) -> Unit) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = noDate, onClick = { onChoose(DateChoice.EMPTY) }, label = { Text(stringResource(R.string.view_add_no_date)) })
        FilterChip(selected = choice == DateChoice.TODAY || todayAuto, onClick = { onChoose(DateChoice.TODAY) }, label = { Text(stringResource(R.string.view_add_today)) })
        FilterChip(selected = choice == DateChoice.TOMORROW, onClick = { onChoose(DateChoice.TOMORROW) }, label = { Text(stringResource(R.string.view_add_tomorrow)) })
        FilterChip(
            selected = choice == DateChoice.PICK,
            onClick = onPick,
            label = { Text(pickedIso?.let { DateTimeFormatter.ofPattern("M/d").format(LocalDate.parse(it)) } ?: stringResource(R.string.view_add_pick)) },
        )
    }
}

/** 날짜를 직접 고르는 대화상자. 확인하면 고른 날짜(ISO)를 [onPicked]로 알리고 닫는다. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickDateDialog(onPicked: (String) -> Unit, onDismiss: () -> Unit) {
    val state = rememberDatePickerState()
    DatePickerDialog(
        onDismissRequest = onDismiss,
        confirmButton = {
            TextButton(onClick = {
                state.selectedDateMillis?.let { onPicked(Instant.ofEpochMilli(it).atZone(ZoneOffset.UTC).toLocalDate().toString()) }
                onDismiss()
            }) { Text(stringResource(R.string.view_add_ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.qs_cancel)) } },
    ) { DatePicker(state = state) }
}

/** 프리셋 저장 결과 한 줄: null이면 아무것도 없고, true는 저장함, false는 저장하지 못함. */
@Composable
internal fun PresetStatus(saved: Boolean?) {
    when (saved) {
        true -> Text(stringResource(R.string.view_add_preset_saved), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        false -> Text(stringResource(R.string.view_add_preset_failed), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        null -> Unit
    }
}
