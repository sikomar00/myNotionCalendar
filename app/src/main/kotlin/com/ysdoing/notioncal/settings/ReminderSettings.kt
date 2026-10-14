package com.ysdoing.notioncal.settings

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.InputChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.core.app.NotificationManagerCompat
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.reminders.ReminderRules
import com.ysdoing.notioncal.reminders.notificationPermissionGranted
import androidx.compose.ui.unit.dp

/** EX-01 "10분 전", "1시간 30분 전", "1일 전", "정시". */
@Composable
internal fun reminderLabel(minutes: Int): String = when {
    minutes == 0 -> stringResource(R.string.reminder_at_start)
    minutes % (24 * 60) == 0 -> stringResource(R.string.reminder_days_before, minutes / (24 * 60))
    minutes % 60 == 0 -> stringResource(R.string.reminder_hours_before, minutes / 60)
    minutes > 60 -> stringResource(R.string.reminder_hours_minutes_before, minutes / 60, minutes % 60)
    else -> stringResource(R.string.reminder_minutes_before, minutes)
}

/**
 * EX-01·S25-11 알림 설정. 켤 때 알림 권한을 요청하고(거부하면 안내·앱 설정 버튼),
 * 정확한 알람을 못 쓰면 '최대 10분 늦을 수 있음'을 보인다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReminderSection(
    enabled: Boolean,
    rules: ReminderRules,
    exactAllowed: Boolean,
    onEnable: (Boolean) -> Unit,
    onRulesChange: (ReminderRules) -> Unit,
) {
    val context = LocalContext.current
    // minSdk 31: Android 13 미만은 알림 권한이 따로 없다.
    val granted = notificationPermissionGranted(context)
    // 권한과 별개로 시스템 설정에서 앱 알림을 끄면 알림이 오지 않는다(Android 12는 권한 대신 이것만 있다).
    val systemEnabled = NotificationManagerCompat.from(context).areNotificationsEnabled()
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        denied = !ok
        if (ok) onEnable(true)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_reminders))
            Text(
                stringResource(R.string.settings_reminders_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = enabled && granted,
            onCheckedChange = { on ->
                when {
                    !on -> onEnable(false)
                    granted -> onEnable(true)
                    else -> launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
            },
        )
    }
    if (denied && !granted) {
        Text(
            stringResource(R.string.settings_reminders_denied),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text(stringResource(R.string.settings_holidays_open_app_settings)) }
    }
    if (!enabled || !granted) return
    if (!systemEnabled) {
        Text(
            stringResource(R.string.settings_reminders_blocked),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text(stringResource(R.string.settings_reminders_open_notification_settings)) }
    }

    // 시간 일정: N분 전(여러 개)
    Text(stringResource(R.string.settings_reminders_timed), style = MaterialTheme.typography.bodyMedium)
    var adding by rememberSaveable { mutableStateOf(false) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        rules.timedMinutes.forEach { minutes ->
            val label = reminderLabel(minutes)
            InputChip(
                selected = false,
                onClick = { onRulesChange(rules.copy(timedMinutes = rules.timedMinutes - minutes)) },
                label = { Text(label) },
                trailingIcon = { Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.settings_reminders_remove, label)) },
            )
        }
        AssistChip(onClick = { adding = true }, label = { Text(stringResource(R.string.settings_reminders_add)) })
    }
    if (adding) {
        AddReminderDialog(
            onAdd = { minutes ->
                adding = false
                onRulesChange(rules.copy(timedMinutes = ReminderRules.normalize(rules.timedMinutes + minutes)))
            },
            onDismiss = { adding = false },
        )
    }

    // 종일 일정: 당일·전날의 지정 시각
    Text(stringResource(R.string.settings_reminders_all_day), style = MaterialTheme.typography.bodyMedium)
    var picking by rememberSaveable { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SingleChoiceSegmentedButtonRow {
            listOf(false to R.string.settings_reminders_same_day, true to R.string.settings_reminders_day_before)
                .forEachIndexed { index, (before, label) ->
                    SegmentedButton(
                        selected = rules.allDayDayBefore == before,
                        onClick = { onRulesChange(rules.copy(allDayDayBefore = before)) },
                        shape = SegmentedButtonDefaults.itemShape(index, 2),
                    ) { Text(stringResource(label)) }
                }
        }
        TextButton(onClick = { picking = true }) {
            Text(stringResource(R.string.settings_reminders_time, rules.allDayMinuteOfDay / 60, rules.allDayMinuteOfDay % 60))
        }
    }
    if (picking) {
        val state = rememberTimePickerState(rules.allDayMinuteOfDay / 60, rules.allDayMinuteOfDay % 60, is24Hour = true)
        AlertDialog(
            onDismissRequest = { picking = false },
            text = { TimePicker(state) },
            confirmButton = {
                TextButton(onClick = {
                    picking = false
                    onRulesChange(rules.copy(allDayMinuteOfDay = state.hour * 60 + state.minute))
                }) { Text(stringResource(R.string.settings_color_save)) }
            },
            dismissButton = { TextButton(onClick = { picking = false }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }

    // S25-11 정확한 알람 상태
    if (exactAllowed) {
        Text(
            stringResource(R.string.settings_reminders_exact_ok),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            stringResource(R.string.settings_reminders_exact_missing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        TextButton(onClick = {
            context.startActivity(
                Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.fromParts("package", context.packageName, null))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }) { Text(stringResource(R.string.settings_reminders_exact_open)) }
    }
}

/** 시간 일정 알림 추가: 숫자 + 단위(분·시간·일), 최대 7일. */
@Composable
private fun AddReminderDialog(onAdd: (Int) -> Unit, onDismiss: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    var unit by rememberSaveable { mutableIntStateOf(1) } // 분 단위 배수: 1, 60, 1440
    val minutes = text.toIntOrNull()?.let { it * unit }
    val valid = minutes != null && minutes in 0..ReminderRules.MAX_MINUTES
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_reminders_add_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { value -> text = value.filter(Char::isDigit).take(5) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    label = { Text(stringResource(R.string.settings_reminders_amount)) },
                    modifier = Modifier.fillMaxWidth(),
                    isError = text.isNotEmpty() && !valid,
                    supportingText = { Text(stringResource(R.string.settings_reminders_max)) },
                )
                SingleChoiceSegmentedButtonRow {
                    listOf(1 to R.string.settings_reminders_unit_minutes, 60 to R.string.settings_reminders_unit_hours, 1440 to R.string.settings_reminders_unit_days)
                        .forEachIndexed { index, (factor, label) ->
                            SegmentedButton(
                                selected = unit == factor,
                                onClick = { unit = factor },
                                shape = SegmentedButtonDefaults.itemShape(index, 3),
                            ) { Text(stringResource(label)) }
                        }
                }
            }
        },
        confirmButton = { TextButton(onClick = { minutes?.let(onAdd) }, enabled = valid) { Text(stringResource(R.string.settings_reminders_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}
