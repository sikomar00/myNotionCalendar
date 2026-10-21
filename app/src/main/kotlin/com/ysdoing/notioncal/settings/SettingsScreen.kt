package com.ysdoing.notioncal.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.sync.ConnectionResult
import java.time.DayOfWeek
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.compose.rememberLauncherForActivityResult
import android.provider.Settings
import android.net.Uri
import android.content.pm.PackageManager
import android.content.Intent
import android.Manifest
import androidx.lifecycle.compose.LifecycleResumeEffect

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsRoute(
    onBack: () -> Unit,
    onAddDatabase: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    onOpenDday: () -> Unit = {},
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val tokenSaved by viewModel.tokenSaved.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var pendingDelete by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingProps by rememberSaveable { mutableStateOf<String?>(null) }
    var choosingColor by rememberSaveable { mutableStateOf<String?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.settings_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.padding(padding).fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Section(stringResource(R.string.settings_section_connection))
            TokenSection(tokenSaved, connection, onSave = viewModel::saveToken, onTest = viewModel::test)

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_databases))
            if (sources.isEmpty()) {
                Text(stringResource(R.string.settings_no_databases), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            sources.forEach { source ->
                SourceRow(
                    source,
                    onToggle = { viewModel.setEnabled(source.dataSourceId, it) },
                    onChooseProps = { choosingProps = source.dataSourceId },
                    onChooseColor = { choosingColor = source.dataSourceId },
                    onDelete = { pendingDelete = source.dataSourceId },
                )
            }
            OutlinedButton(onClick = onAddDatabase) { Text(stringResource(R.string.settings_add_database)) }

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_general))
            Text(stringResource(R.string.settings_week_start))
            SingleChoiceSegmentedButtonRow {
                listOf(DayOfWeek.SUNDAY to R.string.settings_sunday, DayOfWeek.MONDAY to R.string.settings_monday)
                    .forEachIndexed { index, (day, label) ->
                        SegmentedButton(
                            selected = settings.weekStart == day,
                            onClick = { viewModel.setWeekStart(day) },
                            shape = SegmentedButtonDefaults.itemShape(index, 2),
                            icon = {},
                        ) { Text(stringResource(label), maxLines = 1) }
                    }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_hide_done))
                    Text(
                        stringResource(R.string.settings_hide_done_off),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.hideDone, onCheckedChange = viewModel::setHideDone)
            }
            HolidaySetting(settings.showHolidays, onChange = viewModel::setShowHolidays)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_dynamic_color))
                    Text(
                        stringResource(R.string.settings_dynamic_color_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Switch(checked = settings.dynamicColor, onCheckedChange = viewModel::setDynamicColor)
            }

            Text(stringResource(R.string.settings_sync_interval))
            SingleChoiceSegmentedButtonRow {
                AppSettings.SYNC_INTERVALS.forEachIndexed { index, minutes ->
                    SegmentedButton(
                        selected = settings.syncIntervalMinutes == minutes,
                        onClick = { viewModel.setSyncInterval(minutes) },
                        shape = SegmentedButtonDefaults.itemShape(index, AppSettings.SYNC_INTERVALS.size),
                        icon = {},
                    ) { Text(stringResource(R.string.settings_minutes, minutes), maxLines = 1) }
                }
            }
            Text(
                stringResource(R.string.settings_sync_interval_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            // WI-02 퀵 추가 기본 DB는 일정 DB 중에서만 고른다(습관 기록은 습관 화면에서 만든다).
            val enabled = sources.filter { it.enabled && it.sourceKind == SourceKind.CALENDAR }
            if (enabled.size > 1) {
                Text(stringResource(R.string.settings_default_database))
                val current = settings.defaultDataSourceId?.takeIf { id -> enabled.any { it.dataSourceId == id } } ?: enabled.first().dataSourceId
                enabled.forEach { source ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = source.dataSourceId == current, onClick = { viewModel.setDefaultDataSource(source.dataSourceId) })
                        Text(source.name)
                    }
                }
            }

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_weather))
            val weatherStatus by viewModel.weatherStatus.collectAsStateWithLifecycle()
            WeatherSection(
                settings,
                weatherStatus,
                WeatherSettingsActions(
                    onEnable = viewModel::setWeatherEnabled,
                    onMode = viewModel::setWeatherMode,
                    onSearch = viewModel::searchRegions,
                    onPickRegion = viewModel::pickRegion,
                    onLocation = viewModel::useLocation,
                    onMonthCells = viewModel::setWeatherMonthCells,
                ),
            )

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_dday))
            Text(
                stringResource(R.string.settings_dday_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenDday) { Text(stringResource(R.string.settings_open_dday)) }

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_reminders))
            val exactAlarm by viewModel.exactAlarm.collectAsStateWithLifecycle()
            LifecycleResumeEffect(Unit) {
                viewModel.refreshExactAlarm()
                onPauseOrDispose { }
            }
            ReminderSection(
                enabled = settings.remindersEnabled,
                rules = settings.reminderRules,
                exactAllowed = exactAlarm,
                onEnable = viewModel::setRemindersEnabled,
                onRulesChange = viewModel::setReminderRules,
            )

            HorizontalDivider()
            Section(stringResource(R.string.settings_section_diagnostics))
            Text(
                stringResource(R.string.settings_diagnostics_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedButton(onClick = onOpenDiagnostics) { Text(stringResource(R.string.settings_open_diagnostics)) }
        }
    }

    choosingColor?.let { id ->
        sources.firstOrNull { it.dataSourceId == id }?.let { source ->
            ColorDialog(
                source = source,
                onSave = { hex, useOptionColors, emphasis ->
                    viewModel.setColorStyle(id, hex, useOptionColors)
                    viewModel.setEmphasis(id, emphasis.first, emphasis.second)
                    choosingColor = null
                },
                onDismiss = { choosingColor = null },
            )
        }
    }

    choosingProps?.let { id ->
        sources.firstOrNull { it.dataSourceId == id }?.let { source ->
            ExtraPropsDialog(
                source = source,
                onSave = { ids ->
                    viewModel.setExtraProps(id, ids)
                    choosingProps = null
                },
                onDismiss = { choosingProps = null },
            )
        }
    }

    pendingDelete?.let { id ->
        val name = sources.firstOrNull { it.dataSourceId == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text(stringResource(R.string.settings_delete_confirm_title)) },
            text = { Text(stringResource(R.string.settings_delete_confirm_body, name)) },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.delete(id)
                    pendingDelete = null
                }) { Text(stringResource(R.string.settings_delete)) }
            },
            dismissButton = { TextButton(onClick = { pendingDelete = null }) { Text(stringResource(R.string.settings_cancel)) } },
        )
    }
}

@Composable
private fun Section(title: String) {
    // 구역 제목은 먹색 굵은 글자(파랑은 누르는 것에만 쓴다).
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
}

@Composable
private fun TokenSection(tokenSaved: Boolean?, connection: ConnectionUi, onSave: (String) -> Unit, onTest: () -> Unit) {
    // 토큰은 저장 상태(Bundle)에 남기지 않도록 remember만 쓰고, 저장 후 바로 지운다. 표시는 항상 가린다.
    var input by remember { mutableStateOf("") }
    Text(
        stringResource(if (tokenSaved == true) R.string.settings_token_saved else R.string.settings_token_none),
        style = MaterialTheme.typography.bodyMedium,
    )
    OutlinedTextField(
        value = input,
        onValueChange = { input = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(stringResource(R.string.settings_token_label)) },
        supportingText = { Text(stringResource(R.string.settings_token_hint)) },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
    )
    val testing = connection is ConnectionUi.Testing
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = {
                onSave(input)
                input = ""
            },
            enabled = input.isNotBlank() && !testing,
        ) { Text(stringResource(R.string.settings_save_and_test)) }
        if (tokenSaved == true) {
            OutlinedButton(onClick = onTest, enabled = !testing) { Text(stringResource(R.string.settings_test)) }
        }
    }
    when (connection) {
        ConnectionUi.Idle -> Unit
        ConnectionUi.Testing -> Text(stringResource(R.string.settings_testing))
        is ConnectionUi.Done -> when (val result = connection.result) {
            is ConnectionResult.Connected -> Text(
                result.botName?.let { stringResource(R.string.settings_connected, it) }
                    ?: stringResource(R.string.settings_connected_unnamed),
                color = MaterialTheme.colorScheme.primary,
            )
            is ConnectionResult.Failed -> Text(result.error.text(), color = MaterialTheme.colorScheme.error)
        }
    }
}

/**
 * EX-02 공휴일 표시. 켜면 READ_CALENDAR를 요청하고, 허용하면 폰의 공휴일 캘린더를 읽는다.
 * 거부하면 꺼 둔 채 안내하고, 앱 설정에서 허용할 수 있게 버튼을 보인다. 노션 일정은 권한과 무관하다.
 */
@Composable
private fun HolidaySetting(show: Boolean, onChange: (Boolean) -> Unit) {
    val context = LocalContext.current
    var denied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) onChange(true)
    }
    // 권한을 앱 설정에서 거둬 갔으면 꺼진 것으로 보인다.
    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.settings_holidays))
            Text(
                stringResource(R.string.settings_holidays_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Switch(
            checked = show && granted,
            onCheckedChange = { on ->
                when {
                    !on -> onChange(false)
                    granted -> onChange(true)
                    else -> launcher.launch(Manifest.permission.READ_CALENDAR)
                }
            },
        )
    }
    if (denied && !granted) {
        Text(
            stringResource(R.string.settings_holidays_denied),
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
}
