package com.ysdoing.notioncal.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ysdoing.notioncal.BuildConfig
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.SettingsTab
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.designsystem.RadioRow
import com.ysdoing.notioncal.core.designsystem.SwitchRow
import com.ysdoing.notioncal.widget.AllWidgets
import com.ysdoing.notioncal.widget.PlacedWidget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.DayOfWeek

/**
 * 설정 화면이 카테고리 페이지에 넘기는 것. 대화상자는 화면(SettingsRoute)이 갖고 여기서는 열어 달라고만 한다.
 * [tokenInput]은 탭을 바꿔도 쓰던 토큰 입력이 사라지지 않게 화면이 들고 있다(remember만 쓴다: 토큰은 저장 상태(Bundle)에 남기지 않는다).
 */
internal class SettingsActions(
    val onAddDatabase: () -> Unit,
    val onAddView: () -> Unit,
    val onOpenDiagnostics: () -> Unit,
    val onOpenDday: () -> Unit,
    val onChooseProps: (String) -> Unit,
    val onChooseColor: (String) -> Unit,
    val onDelete: (String) -> Unit,
    val tokenInput: MutableState<String>,
)

/** 페이지의 한 칸: 항목과, 앞 항목과 구분선으로 나눌지(머리가 있는 항목은 나누되 페이지 맨 위는 제외). */
internal data class PageEntry(val item: SettingsItem, val dividerBefore: Boolean)

/** 머리(구역 제목)가 있는 항목. */
private val HEADED = setOf(SettingsItem.DATABASES, SettingsItem.DDAY, SettingsItem.WEATHER, SettingsItem.REMINDERS, SettingsItem.DIAGNOSTICS, SettingsItem.WIDGET_LIST)

internal fun pageEntries(tab: SettingsTab): List<PageEntry> = itemsOf(tab).mapIndexed { index, item -> PageEntry(item, dividerBefore = index > 0 && item in HEADED) }

/** 카테고리 하나의 페이지: 그 카테고리 항목을 순서대로, 카테고리마다 따로 스크롤한다(ST-01). */
@Composable
internal fun SettingsPage(tab: SettingsTab, viewModel: SettingsViewModel, actions: SettingsActions) {
    Column(
        Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        pageEntries(tab).forEach { entry ->
            if (entry.dividerBefore) HorizontalDivider()
            SettingItem(entry.item, viewModel, actions)
        }
    }
}

@Composable
private fun SettingItem(item: SettingsItem, viewModel: SettingsViewModel, actions: SettingsActions) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    when (item) {
        SettingsItem.TOKEN -> TokenItem(viewModel, actions)
        SettingsItem.DATABASES -> DatabasesItem(viewModel, actions)
        SettingsItem.DEFAULT_DATABASE -> DefaultDatabaseItem(settings.defaultDataSourceId, viewModel)
        SettingsItem.DDAY -> DdayItem(actions)
        SettingsItem.WEEK_START -> WeekStartItem(settings.weekStart, viewModel)
        SettingsItem.HIDE_DONE ->
            SwitchRow(stringResource(R.string.settings_hide_done), settings.hideDone, note = stringResource(R.string.settings_hide_done_off), onChange = viewModel::setHideDone)
        SettingsItem.HOLIDAYS -> HolidaySetting(settings.showHolidays, onChange = viewModel::setShowHolidays)
        SettingsItem.WEEK_NUMBERS ->
            SwitchRow(stringResource(R.string.settings_week_numbers), settings.showWeekNumbers, note = stringResource(R.string.settings_week_numbers_note), onChange = viewModel::setShowWeekNumbers)
        SettingsItem.DYNAMIC_COLOR ->
            SwitchRow(stringResource(R.string.settings_dynamic_color), settings.dynamicColor, note = stringResource(R.string.settings_dynamic_color_note), onChange = viewModel::setDynamicColor)
        SettingsItem.WEATHER -> WeatherItem(settings, viewModel)
        SettingsItem.REMINDERS -> RemindersItem(settings, viewModel)
        SettingsItem.SYNC_INTERVAL -> SyncIntervalItem(settings.syncIntervalMinutes, viewModel)
        SettingsItem.DIAGNOSTICS -> DiagnosticsItem(actions)
        SettingsItem.APP_VERSION -> Text(stringResource(R.string.settings_app_version, BuildConfig.VERSION_NAME), style = MaterialTheme.typography.bodyMedium)
        SettingsItem.WIDGET_LIST -> WidgetListItem()
    }
}

@Composable
private fun TokenItem(viewModel: SettingsViewModel, actions: SettingsActions) {
    val tokenSaved by viewModel.tokenSaved.collectAsStateWithLifecycle()
    val connection by viewModel.connectionState.collectAsStateWithLifecycle()
    Section(stringResource(R.string.settings_section_connection))
    TokenSection(tokenSaved, connection, actions.tokenInput, onSave = viewModel::saveToken, onTest = viewModel::test)
}

@Composable
private fun DdayItem(actions: SettingsActions) {
    Section(stringResource(R.string.settings_section_dday))
    Text(stringResource(R.string.settings_dday_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = actions.onOpenDday) { Text(stringResource(R.string.settings_open_dday)) }
}

@Composable
private fun WeekStartItem(weekStart: DayOfWeek, viewModel: SettingsViewModel) {
    Text(stringResource(R.string.settings_week_start))
    SingleChoiceSegmentedButtonRow {
        listOf(DayOfWeek.SUNDAY to R.string.settings_sunday, DayOfWeek.MONDAY to R.string.settings_monday).forEachIndexed { index, (day, label) ->
            SegmentedButton(
                selected = weekStart == day,
                onClick = { viewModel.setWeekStart(day) },
                shape = SegmentedButtonDefaults.itemShape(index, 2),
                icon = {},
            ) { Text(stringResource(label), maxLines = 1) }
        }
    }
}

@Composable
private fun WeatherItem(settings: AppSettings, viewModel: SettingsViewModel) {
    val weatherStatus by viewModel.weatherStatus.collectAsStateWithLifecycle()
    Section(stringResource(R.string.settings_section_weather))
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
}

@Composable
private fun RemindersItem(settings: AppSettings, viewModel: SettingsViewModel) {
    val exactAlarm by viewModel.exactAlarm.collectAsStateWithLifecycle()
    Section(stringResource(R.string.settings_section_reminders))
    ReminderSection(
        enabled = settings.remindersEnabled,
        rules = settings.reminderRules,
        exactAllowed = exactAlarm,
        onEnable = viewModel::setRemindersEnabled,
        onRulesChange = viewModel::setReminderRules,
    )
}

@Composable
private fun SyncIntervalItem(minutes: Int, viewModel: SettingsViewModel) {
    Text(stringResource(R.string.settings_sync_interval))
    SingleChoiceSegmentedButtonRow {
        AppSettings.SYNC_INTERVALS.forEachIndexed { index, interval ->
            SegmentedButton(
                selected = minutes == interval,
                onClick = { viewModel.setSyncInterval(interval) },
                shape = SegmentedButtonDefaults.itemShape(index, AppSettings.SYNC_INTERVALS.size),
                icon = {},
            ) { Text(stringResource(R.string.settings_minutes, interval), maxLines = 1) }
        }
    }
    Text(stringResource(R.string.settings_sync_interval_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun DiagnosticsItem(actions: SettingsActions) {
    Section(stringResource(R.string.settings_section_diagnostics))
    Text(stringResource(R.string.settings_diagnostics_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    OutlinedButton(onClick = actions.onOpenDiagnostics) { Text(stringResource(R.string.settings_open_diagnostics)) }
}

/** 노션 DB 목록과 DB·보기 추가 버튼. */
@Composable
private fun DatabasesItem(viewModel: SettingsViewModel, actions: SettingsActions) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    Section(stringResource(R.string.settings_section_databases))
    if (sources.isEmpty()) Text(stringResource(R.string.settings_no_databases), color = MaterialTheme.colorScheme.onSurfaceVariant)
    sources.forEach { source ->
        SourceRow(
            source,
            onToggle = { viewModel.setEnabled(source.dataSourceId, it) },
            onChooseProps = { actions.onChooseProps(source.dataSourceId) },
            onChooseColor = { actions.onChooseColor(source.dataSourceId) },
            onDelete = { actions.onDelete(source.dataSourceId) },
        )
    }
    OutlinedButton(onClick = actions.onAddDatabase) { Text(stringResource(R.string.settings_add_database)) }
    OutlinedButton(onClick = actions.onAddView) { Text(stringResource(R.string.settings_add_view)) }
}

/** WI-02 퀵 추가 기본 DB는 일정 DB 중에서만 고른다(습관 기록은 습관 화면에서 만든다). 고를 DB가 둘 미만이면 그리지 않는다. */
@Composable
private fun DefaultDatabaseItem(defaultId: String?, viewModel: SettingsViewModel) {
    val sources by viewModel.sources.collectAsStateWithLifecycle()
    val enabled = sources.filter { it.enabled && it.sourceKind == SourceKind.CALENDAR }
    if (enabled.size <= 1) return
    Text(stringResource(R.string.settings_default_database))
    val current = defaultId?.takeIf { id -> enabled.any { it.dataSourceId == id } } ?: enabled.first().dataSourceId
    enabled.forEach { source -> RadioRow(source.name, source.dataSourceId == current) { viewModel.setDefaultDataSource(source.dataSourceId) } }
}

/** 설정의 '위젯' 목록: 홈 화면에 놓인 위젯을 읽어(설정에 돌아올 때마다 다시) 보여 주고 누르면 그 위젯의 설정을 연다. */
@Composable
private fun WidgetListItem() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var widgets by remember { mutableStateOf<List<PlacedWidget>?>(null) }
    LifecycleResumeEffect(Unit) {
        val job = scope.launch { widgets = withContext(Dispatchers.Default) { AllWidgets.placed(context) } }
        onPauseOrDispose { job.cancel() }
    }
    WidgetListContent(widgets) { context.startActivity(AllWidgets.configIntent(context, it)) }
}

/** [WidgetListItem]의 그림. [widgets]가 null이면 읽는 중이라 아무것도 그리지 않는다. */
@Composable
internal fun WidgetListContent(widgets: List<PlacedWidget>?, onOpen: (Int) -> Unit) {
    Section(stringResource(R.string.settings_section_widgets))
    if (widgets == null) return
    Text(stringResource(R.string.settings_widgets_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (widgets.isEmpty()) Text(stringResource(R.string.settings_widgets_empty), color = MaterialTheme.colorScheme.onSurfaceVariant)
    widgets.forEach { widget ->
        val description = stringResource(R.string.settings_widget_open, listOf(widget.kindLabel, widget.summary).filter { it.isNotBlank() }.joinToString(", "))
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(role = Role.Button) { onOpen(widget.appWidgetId) }.semantics { contentDescription = description },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f).padding(vertical = 6.dp)) {
                Text(widget.kindLabel)
                if (widget.summary.isNotBlank()) Text(widget.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.settings_widget_open_label), color = MaterialTheme.colorScheme.primary)
        }
    }
}
