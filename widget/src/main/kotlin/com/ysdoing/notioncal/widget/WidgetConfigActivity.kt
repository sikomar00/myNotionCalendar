package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.appwidget.GlanceAppWidgetManager
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.LineSpacing
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ViewPreset
import com.ysdoing.notioncal.core.data.view.ViewPresets
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import com.ysdoing.notioncal.core.designsystem.RadioRow
import com.ysdoing.notioncal.core.designsystem.SwitchRow
import com.ysdoing.notioncal.core.designsystem.WeatherAttribution
import com.ysdoing.notioncal.core.designsystem.selectableRow
import com.ysdoing.notioncal.core.designsystem.NotionShapes
import com.ysdoing.notioncal.core.designsystem.NotionTypography
import com.ysdoing.notioncal.core.designsystem.notionColorScheme
import kotlinx.coroutines.withContext

/**
 * WG-04 위젯별 설정. 위젯을 처음 놓을 때와 길게 눌러 다시 설정할 때 열린다.
 * 미리보기는 위젯과 같은 [WidgetContent]를 RemoteViews로 만들어 보여준다(실제 위젯과 같은 모양).
 */
class WidgetConfigActivity : ComponentActivity() {
    private val appWidgetId by lazy {
        intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    private val kind by lazy { configKindFor(AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className) }

    /** WG-11 통합 위젯: 월 위젯 설정에 💰 탭의 금액 숨김(LD-06)을 더한다. */
    private val tabbed by lazy {
        AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className == TabbedWidgetReceiver::class.java.name
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // 저장하지 않고 나가면 취소로 처리한다(런처 규칙).
        setResult(RESULT_CANCELED, resultIntent())
        if (appWidgetId == AppWidgetManager.INVALID_APPWIDGET_ID) {
            finish()
            return
        }
        val deps = WidgetDependencies.from(this)
        val previewSize = widgetSize()
        setContent {
            MaterialTheme(colorScheme = notionColorScheme(isSystemInDarkTheme()), typography = NotionTypography, shapes = NotionShapes) {
                Surface(Modifier.fillMaxSize()) {
                    ConfigScreen(deps, previewSize, onSaved = ::finishSaved)
                }
            }
        }
    }

    /** 프리셋 만들기 시트가 돌려준 프리셋. 화면이 목록에 더하고 비운다(DB는 '저장'에서 목록과 함께 쓴다). */
    private var madePreset by mutableStateOf<ViewPreset?>(null)

    private val presetSheet = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) madePreset = presetFrom(result.data)
    }

    private fun createPreset(viewId: String) = presetSheet.launch(presetAddIntent(this, viewId, appWidgetId))

    private fun finishSaved() {
        setResult(RESULT_OK, resultIntent())
        finish()
    }

    private fun resultIntent() = Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)

    /** 런처가 알려준 현재 위젯 크기(세로 방향 기준). 처음 놓을 때 값이 없으면 4×4 근사값. */
    private fun widgetSize(): DpSize {
        val options = AppWidgetManager.getInstance(this).getAppWidgetOptions(appWidgetId)
        val width = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MIN_WIDTH).takeIf { it > 0 } ?: 330
        val height = options.getInt(AppWidgetManager.OPTION_APPWIDGET_MAX_HEIGHT).takeIf { it > 0 } ?: 380
        return DpSize(width.dp, height.dp)
    }

    @Composable
    private fun ConfigScreen(deps: WidgetDependencies, previewSize: DpSize, onSaved: () -> Unit) {
        val scope = rememberCoroutineScope()
        var loaded by remember { mutableStateOf<ConfigData?>(null) }
        var config by remember { mutableStateOf(WidgetConfig(appWidgetId)) }
        LaunchedEffect(Unit) {
            // Phase 11h: 레시피로 놓은 새 노션 위젯(저장된 행이 없는 위젯)이면 대기 레시피의 구성으로 시작한다.
            val start = if (kind == ConfigKind.LAYOUT) deps.recipeClaims().startFor(appWidgetId, System.currentTimeMillis()) else null
            val saved = start ?: deps.widgetConfigs().get(appWidgetId)
            val settings = deps.settings().settings.first()
            val today = LocalDate.now(APP_ZONE)
            val month = NotionCalWidget.monthFor(saved)
            val events = if (kind == ConfigKind.VIEW || kind == ConfigKind.LAYOUT || kind == ConfigKind.HABIT || kind == ConfigKind.BOOK) {
                emptyList()
            } else if (kind == ConfigKind.MONTH) {
                deps.events().observeRange(NotionCalWidget.gridFrom(month, settings.weekStart), NotionCalWidget.gridTo(month, settings.weekStart)).first()
            } else {
                deps.events().observeRange(today, AgendaWidget.lastDay(today)).first()
            }
            config = saved
            val ledger = if (kind == ConfigKind.LEDGER) deps.ledger().observeBoard(YearMonth.from(today), today).first() else null
            val ddays = if (kind == ConfigKind.DDAY || kind == ConfigKind.DDAY_LIST) deps.ddays().observeVisible(today).first() else emptyList()
            val views = if (kind == ConfigKind.VIEW || kind == ConfigKind.LAYOUT || kind == ConfigKind.BOOK) deps.views().observeRegistered().first() else emptyList()
            // 집계 타일 미리보기는 등록한 보기의 실제 행으로 계산한다(보기마다 한 번, 행 JSON을 읽으니 메인 스레드 밖에서).
            val boards = if (kind == ConfigKind.LAYOUT) withContext(Dispatchers.Default) { views.associate { it.viewId to deps.views().observe(it.viewId).first() } } else emptyMap()
            loaded = ConfigData(settings, events, deps.dataSources().observeCalendarSources().first(), ledger, ddays, views, boards)
        }
        val data = loaded ?: run {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() } // 로딩 중 빈 화면 방지(부록 A-8)
            return
        }
        LaunchedEffect(madePreset) {
            val preset = madePreset ?: return@LaunchedEffect
            madePreset = null
            // 화면에 있는 목록으로 센다: 화면에서 지운 프리셋은 되살아나지 않고, 5개가 차 있으면 알린다.
            val updated = ViewPresets.add(config.presetsJson, preset)
            if (updated == null) Toast.makeText(this@WidgetConfigActivity, R.string.widget_config_preset_full, Toast.LENGTH_SHORT).show() else config = config.copy(presetsJson = updated)
        }
        val tabs = remember { configTabs(kind) }
        var tabIndex by rememberSaveable { mutableIntStateOf(0) }
        val tab = tabs[tabIndex.coerceIn(tabs.indices)]

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.widget_config_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.widget_config_preview), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            // 미리보기는 위에 고정하고 설정만 스크롤한다: 슬라이더를 만지며 바로 결과를 본다.
            Preview(data, config, previewSize)
            if (tabs.size > 1) ConfigTabRow(tabs, tab) { tabIndex = tabs.indexOf(it) }
            key(tab) { // 탭을 바꾸면 위에서부터 보인다
                Column(
                    Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    when (tab) {
                        ConfigTab.CONTENT -> ContentSettings(data, config) { config = it }
                        ConfigTab.LOOK -> LookSettings(data, config) { config = it }
                        ConfigTab.ACTION -> ActionSettings(kind, data, config, { config = it }, ::createPreset)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { config = config.resetLook() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_config_reset)) }
                Button(
                    onClick = {
                        scope.launch {
                            deps.widgetConfigs().save(config)
                            if (kind == ConfigKind.LAYOUT) deps.recipeClaims().consume() // 레시피로 시작했으면 저장한 지금 대기를 지운다(회전해도 저장 전에는 남는다)
                            val glanceId = GlanceAppWidgetManager(this@WidgetConfigActivity).getGlanceIdBy(appWidgetId)
                            // 통합 위젯(WG-11)도 월 미리보기·설정을 쓰고, 다시 그릴 때는 제 종류로 그린다.
                            WidgetKinds.update(this@WidgetConfigActivity, glanceId)
                            onSaved()
                        }
                    },
                    modifier = Modifier.weight(2f),
                ) { Text(stringResource(R.string.widget_config_save)) }
            }
        }
    }

    /**
     * 내용 탭: 위젯이 가리킬 대상(디데이·보기·배치)과 일정 DB 고르기. 통합 위젯(WG-11)은 탭마다 적용 범위가 달라 '일정 탭' 아래에 DB를 둔다.
     */
    @Composable
    private fun ContentSettings(data: ConfigData, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
        TargetSettings(data, config, onChange)
        if (tabbed) ConfigSection(stringResource(R.string.widget_config_tab_calendar))
        if (kind in CALENDAR_KINDS) {
            ConfigSection(stringResource(R.string.widget_config_sources))
            SourcePicker(data.sources, config.dataSourceIds) { onChange(config.copy(dataSourceIds = it)) }
        }
    }

    /**
     * 모양 탭: 테마·투명도·글자 크기와 위젯 종류별 표시 방식. 통합 위젯(WG-11)은 탭마다 적용 범위가 달라 '모든 탭'(습관 탭은 이것만 따름)·'일정 탭'·'가계부 탭'으로 나눠 보인다.
     */
    @Composable
    private fun LookSettings(data: ConfigData, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
        if (tabbed) {
            ConfigSection(stringResource(R.string.widget_config_tab_all))
            ConfigNote(stringResource(R.string.widget_config_tab_all_note))
        }
        AppearanceSettings(config, onChange)
        if (tabbed) ConfigSection(stringResource(R.string.widget_config_tab_calendar))
        CalendarSettings(data, config, onChange)
        if (tabbed) ConfigSection(stringResource(R.string.widget_config_tab_ledger))
        // LD-06: 가계부 위젯·통합 위젯은 금액을 가릴 수 있다(●●●, 막대만).
        if (kind == ConfigKind.LEDGER || tabbed) {
            SwitchRow(
                stringResource(R.string.widget_config_hide_amounts), config.hideAmounts, note = stringResource(R.string.widget_config_hide_amounts_note),
            ) { onChange(config.copy(hideAmounts = it)) }
        }
    }

    /** 위젯이 가리킬 대상: 디데이(DD-03·04, 고르지 않으면 가장 가까운 것)와 노션 보기(VW-04, 앱 설정의 '보기 추가'로 등록한 것). */
    @Composable
    private fun TargetSettings(data: ConfigData, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
        if (kind == ConfigKind.DDAY) {
            ConfigSection(stringResource(R.string.widget_config_dday))
            Row(Modifier.selectableRow(config.ddayPageId == null) { onChange(config.copy(ddayPageId = null)) }, verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = config.ddayPageId == null, onClick = null)
                Text(stringResource(R.string.widget_config_dday_nearest))
            }
            data.ddays.forEach { dday ->
                Row(Modifier.selectableRow(config.ddayPageId == dday.pageId) { onChange(config.copy(ddayPageId = dday.pageId)) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = config.ddayPageId == dday.pageId, onClick = null)
                    dday.label?.let { Text(it.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp)) }
                    Text(dday.title.orEmpty())
                }
            }
            if (data.ddays.isEmpty()) ConfigNote(stringResource(R.string.widget_config_dday_none))
        }
        if (kind == ConfigKind.VIEW || kind == ConfigKind.BOOK) {
            ConfigSection(stringResource(R.string.widget_config_view))
            data.views.forEach { view ->
                RadioRow(view.name.ifBlank { stringResource(R.string.widget_config_view_untitled) }, config.viewId == view.viewId) { onChange(config.copy(viewId = view.viewId)) }
            }
            if (data.views.isEmpty()) ConfigNote(stringResource(R.string.widget_config_view_none))
        }
        if (kind == ConfigKind.LAYOUT) LayoutSettings(data.views, config, onChange)
    }

    /** 일정 위젯 전용: 칸당 줄 수·줄 간격(월간·3일), 머리 날씨(월간·달력 + 일정, WX-04·05), 완료 숨김. */
    @Composable
    private fun CalendarSettings(data: ConfigData, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
        if (kind == ConfigKind.MONTH || kind == ConfigKind.THREE_DAY) {
            ConfigSection(stringResource(R.string.widget_config_lanes))
            ChoiceChips(
                WidgetConfig.MAX_PER_CELL_RANGE.map { it to if (it == 0) R.string.widget_config_lanes_auto else null },
                config.maxPerCell,
            ) { onChange(config.copy(maxPerCell = it)) }

            ConfigSection(stringResource(R.string.widget_config_spacing))
            ChoiceChips(
                listOf(
                    LineSpacing.COMPACT to R.string.widget_config_spacing_compact,
                    LineSpacing.NORMAL to R.string.widget_config_spacing_normal,
                    LineSpacing.WIDE to R.string.widget_config_spacing_wide,
                ),
                config.lineSpacing,
            ) { onChange(config.copy(lineSpacing = it)) }
        }
        // 날씨가 보이는 설정 화면이라 출처를 둔다.
        if (kind == ConfigKind.MONTH || kind == ConfigKind.SPLIT) {
            SwitchRow(
                stringResource(R.string.widget_config_weather), config.showWeather,
                note = stringResource(if (data.settings.weatherEnabled) R.string.widget_config_weather_note else R.string.widget_config_weather_off),
            ) { onChange(config.copy(showWeather = it)) }
            if (config.showWeather) WeatherAttribution()
        }
        if (kind in CALENDAR_KINDS) SwitchRow(stringResource(R.string.widget_config_hide_done), config.hideDone) { onChange(config.copy(hideDone = it)) }
    }

    @Composable
    private fun Preview(data: ConfigData, config: WidgetConfig, size: DpSize) {
        var views by remember { mutableStateOf<RemoteViews?>(null) }
        LaunchedEffect(config) {
            val today = LocalDate.now(APP_ZONE)
            views = when (kind) {
                ConfigKind.AGENDA -> {
                    val state = AgendaWidget.uiState(today, config, data.events)
                    staticRemoteViews(this@WidgetConfigActivity, size) { AgendaWidgetContent(state) }
                }
                ConfigKind.THREE_DAY -> {
                    val state = ThreeDayWidget.uiState(today, config, data.events)
                    staticRemoteViews(this@WidgetConfigActivity, size) { ThreeDayWidgetContent(state) }
                }
                ConfigKind.SPLIT -> {
                    val state = SplitWidget.uiState(today, data.settings, config, data.events)
                    staticRemoteViews(this@WidgetConfigActivity, size) { SplitWidgetContent(state) }
                }
                ConfigKind.MONTH -> {
                    val state = NotionCalWidget.uiState(NotionCalWidget.monthFor(config), today, data.settings, config, data.events)
                    staticRemoteViews(this@WidgetConfigActivity, size) { WidgetContent(state) }
                }
                ConfigKind.LEDGER -> {
                    val board = data.ledger ?: WidgetPreviews.ledgerState(today).board
                    staticRemoteViews(this@WidgetConfigActivity, size) { LedgerWidgetContent(LedgerWidgetState(board, config)) }
                }
                ConfigKind.VIEW -> {
                    val state = WidgetPreviews.viewState().copy(config = config.copy(viewId = "preview"))
                    staticRemoteViews(this@WidgetConfigActivity, size) { ViewWidgetContent(state, staticRows = true) }
                }
                ConfigKind.LAYOUT -> {
                    val state = WidgetPreviews.layoutPreview(config, data.views.associate { it.viewId to it.name }, boards = data.boards, weekStart = data.settings.weekStart, showWeekNumbers = data.settings.showWeekNumbers)
                    staticRemoteViews(this@WidgetConfigActivity, size) { LayoutWidgetContent(state, staticRows = true) }
                }
                ConfigKind.BOOK -> {
                    val state = WidgetPreviews.bookState().copy(config = config.copy(viewId = "preview"))
                    staticRemoteViews(this@WidgetConfigActivity, size) { BookWidgetContent(state) }
                }
                ConfigKind.HABIT -> {
                    val state = WidgetPreviews.habitState(today).copy(config = config)
                    staticRemoteViews(this@WidgetConfigActivity, size) { HabitWidgetContent(state) }
                }
                ConfigKind.DDAY, ConfigKind.DDAY_LIST -> {
                    val state = DdayWidgetState(data.ddays, config, today)
                    staticRemoteViews(this@WidgetConfigActivity, size) {
                        if (kind == ConfigKind.DDAY) DdayWidgetContent(state) else DdayListWidgetContent(state)
                    }
                }
            }
        }
        RemoteViewsFrame(views, size, PREVIEW_MAX_SCREEN_FRACTION)
    }

    private companion object {
        /** 고정 미리보기가 차지하는 화면 높이 비율 상한(설정 목록에 나머지를 남긴다). */
        const val PREVIEW_MAX_SCREEN_FRACTION = 0.30f
    }
}
