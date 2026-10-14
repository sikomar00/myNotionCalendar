package com.ysdoing.notioncal.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.R
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.ui.semantics.Role
import com.ysdoing.notioncal.core.data.parseIdList
import com.ysdoing.notioncal.core.data.selectableExtraProps
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.sync.ConnectionResult
import java.time.DayOfWeek
import androidx.compose.material.icons.filled.Check
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.filled.Warning
import com.ysdoing.notioncal.core.data.SchemaIssue
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
                        ) { Text(stringResource(label)) }
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
                    ) { Text(stringResource(R.string.settings_minutes, minutes)) }
                }
            }
            Text(
                stringResource(R.string.settings_sync_interval_note),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            val enabled = sources.filter { it.enabled }
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
                onSave = { hex, useOptionColors ->
                    viewModel.setColorStyle(id, hex, useOptionColors)
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
    Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
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

@Composable
private fun SourceRow(
    source: DataSourceEntity,
    onToggle: (Boolean) -> Unit,
    onChooseProps: () -> Unit,
    onChooseColor: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        // EX-03: 색 점을 누르면 DB 색·색 기준을 고른다(터치 영역 48dp).
        val colorLabel = stringResource(R.string.settings_color_button, source.name)
        IconButton(onClick = onChooseColor, modifier = Modifier.semantics { contentDescription = colorLabel }) {
            Box(Modifier.size(18.dp).clip(CircleShape).background(Color(NotionColors.argb(source.colorHex))))
        }
        Text(source.name, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
        // NS-09: 매핑한 속성이 사라지거나 종류가 바뀌었으면 경고(자세한 안내는 월 화면 배너).
        if (SchemaIssue.parse(source.schemaIssues).any { it.kind != SchemaIssue.RENAMED }) {
            Icon(
                Icons.Filled.Warning,
                contentDescription = stringResource(R.string.settings_schema_warning),
                tint = MaterialTheme.colorScheme.error,
            )
        }
        TextButton(onClick = onChooseProps) { Text(stringResource(R.string.settings_extra_props)) }
        Switch(checked = source.enabled, onCheckedChange = onToggle)
        IconButton(onClick = onDelete) {
            Icon(Icons.Filled.Delete, contentDescription = stringResource(R.string.settings_delete_database, source.name))
        }
    }
}

/**
 * EX-03: DB 색(20색 격자)과 일정 색 기준을 고른다. 색 속성(select)이 있는 DB만 '속성 값 색'을 고를 수 있다.
 * 노션 쪽 옵션 색은 바꾸지 않는다(앱 표시 색만).
 */
@Composable
private fun ColorDialog(source: DataSourceEntity, onSave: (String, Boolean) -> Unit, onDismiss: () -> Unit) {
    var hex by rememberSaveable(source.dataSourceId) { mutableStateOf(source.colorHex) }
    var useOptions by rememberSaveable(source.dataSourceId) { mutableStateOf(source.useOptionColors) }
    val hasColorProp = source.colorPropId != null
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_color_title, source.name)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (hasColorProp) {
                    listOf(true to R.string.settings_color_by_option, false to R.string.settings_color_by_database).forEach { (value, label) ->
                        Row(
                            Modifier.fillMaxWidth().selectable(selected = useOptions == value, role = Role.RadioButton) { useOptions = value },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = useOptions == value, onClick = null)
                            Text(stringResource(label), Modifier.padding(start = 8.dp))
                        }
                    }
                }
                Text(
                    stringResource(if (hasColorProp && useOptions) R.string.settings_color_fallback else R.string.settings_color_database),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                NotionColors.pickerPalette.chunked(5).forEach { row ->
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        row.forEach { color ->
                            val selected = color.equals(hex, ignoreCase = true)
                            Box(
                                Modifier
                                    .size(44.dp)
                                    .clip(CircleShape)
                                    .selectable(selected = selected, role = Role.RadioButton) { hex = color }
                                    .semantics { contentDescription = color },
                                contentAlignment = Alignment.Center,
                            ) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(Color(NotionColors.argb(color))), contentAlignment = Alignment.Center) {
                                    if (selected) Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White)
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(hex, useOptions) }) { Text(stringResource(R.string.settings_color_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

/** EX-07: 편집 화면에 보여 줄 속성 고르기. 목록은 동기화 때 받아 둔 스키마(Room)에서 읽는다. */
@Composable
private fun ExtraPropsDialog(source: DataSourceEntity, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val candidates = source.selectableExtraProps()
    var chosen by rememberSaveable(source.dataSourceId) { mutableStateOf(parseIdList(source.extraPropIds)) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.settings_extra_props_title, source.name)) },
        text = {
            Column {
                when {
                    source.propCatalog == null -> Text(stringResource(R.string.settings_extra_props_pending))
                    candidates.isEmpty() -> Text(stringResource(R.string.settings_extra_props_empty))
                    else -> candidates.forEach { prop ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .toggleable(value = prop.id in chosen, role = Role.Checkbox) { on ->
                                    chosen = if (on) chosen + prop.id else chosen - prop.id
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(checked = prop.id in chosen, onCheckedChange = null)
                            Text(prop.name, Modifier.weight(1f))
                            Text(
                                extraTypeLabel(prop.type),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            // 목록 순서대로 저장한다(편집 화면도 이 순서).
            TextButton(onClick = { onSave(candidates.map { it.id }.filter { it in chosen }) }) {
                Text(stringResource(R.string.settings_save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.settings_cancel)) } },
    )
}

@Composable
private fun extraTypeLabel(type: String): String = stringResource(
    when (type) {
        "select" -> R.string.settings_type_select
        "multi_select" -> R.string.settings_type_multi_select
        "status" -> R.string.settings_type_status
        "number" -> R.string.settings_type_number
        else -> R.string.settings_type_url
    },
)
