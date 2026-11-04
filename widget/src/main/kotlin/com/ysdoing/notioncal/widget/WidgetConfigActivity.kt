package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import android.widget.FrameLayout
import android.widget.RemoteViews
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.glance.appwidget.ExperimentalGlanceRemoteViewsApi
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.GlanceRemoteViews
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.LineSpacing
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.view.ViewPresets
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import com.ysdoing.notioncal.core.data.dday.Dday
import com.ysdoing.notioncal.core.designsystem.RadioRow
import com.ysdoing.notioncal.core.designsystem.SwitchRow
import com.ysdoing.notioncal.core.designsystem.WeatherAttribution
import com.ysdoing.notioncal.core.designsystem.selectableRow
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.designsystem.NotionShapes
import com.ysdoing.notioncal.core.designsystem.NotionTypography
import com.ysdoing.notioncal.core.designsystem.notionColorScheme

/**
 * WG-04 위젯별 설정. 위젯을 처음 놓을 때와 길게 눌러 다시 설정할 때 열린다.
 * 미리보기는 위젯과 같은 [WidgetContent]를 RemoteViews로 만들어 보여준다(실제 위젯과 같은 모양).
 */
class WidgetConfigActivity : ComponentActivity() {
    private val appWidgetId by lazy {
        intent?.extras?.getInt(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
            ?: AppWidgetManager.INVALID_APPWIDGET_ID
    }

    /**
     * 어떤 위젯의 설정인지. 보조(WG-06)·달력 + 일정(WG-09)은 그 모양으로 미리보기를 보이고, 월 위젯 전용 설정은 숨긴다.
     * 통합 위젯(WG-11)은 📅 탭이 월 위젯 내용이라 MONTH로 다룬다. 습관 위젯(HB-03)은 모양 설정(테마·투명도·글자)만 있다.
     */
    private enum class Kind { MONTH, AGENDA, SPLIT, LEDGER, DDAY, DDAY_LIST, VIEW, HABIT, BOOK }

    private val kind by lazy {
        when (AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className) {
            AgendaWidgetReceiver::class.java.name -> Kind.AGENDA
            SplitWidgetReceiver::class.java.name -> Kind.SPLIT
            LedgerWidgetReceiver::class.java.name -> Kind.LEDGER
            DdaySmallWidgetReceiver::class.java.name, DdayWideWidgetReceiver::class.java.name -> Kind.DDAY
            DdayListWidgetReceiver::class.java.name -> Kind.DDAY_LIST
            ViewWidgetReceiver::class.java.name -> Kind.VIEW
            HabitWidgetReceiver::class.java.name -> Kind.HABIT
            BookWidgetReceiver::class.java.name -> Kind.BOOK
            else -> Kind.MONTH
        }
    }

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
        var loaded by remember { mutableStateOf<Loaded?>(null) }
        var config by remember { mutableStateOf(WidgetConfig(appWidgetId)) }
        LaunchedEffect(Unit) {
            val saved = deps.widgetConfigs().get(appWidgetId)
            val settings = deps.settings().settings.first()
            val today = LocalDate.now(APP_ZONE)
            val month = NotionCalWidget.monthFor(saved)
            val events = if (kind == Kind.VIEW || kind == Kind.HABIT || kind == Kind.BOOK) {
                emptyList()
            } else if (kind == Kind.MONTH) {
                deps.events().observeRange(NotionCalWidget.gridFrom(month, settings.weekStart), NotionCalWidget.gridTo(month, settings.weekStart)).first()
            } else {
                deps.events().observeRange(today, AgendaWidget.lastDay(today)).first()
            }
            config = saved
            val ledger = if (kind == Kind.LEDGER) deps.ledger().observeBoard(YearMonth.from(today), today).first() else null
            val ddays = if (kind == Kind.DDAY || kind == Kind.DDAY_LIST) deps.ddays().observeVisible(today).first() else emptyList()
            val views = if (kind == Kind.VIEW || kind == Kind.BOOK) deps.views().observeRegistered().first() else emptyList()
            loaded = Loaded(settings, events, deps.dataSources().observeCalendarSources().first(), ledger, ddays, views)
        }
        val data = loaded ?: return

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(horizontal = 16.dp, vertical = 12.dp)) {
            Text(stringResource(R.string.widget_config_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.widget_config_preview), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            // 미리보기는 위에 고정하고 설정만 스크롤한다: 슬라이더를 만지며 바로 결과를 본다.
            Preview(data, config, previewSize)
            Column(
                Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) { Settings(data, config) { config = it } }
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { config = config.resetLook() }, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.widget_config_reset)) }
                Button(
                    onClick = {
                        scope.launch {
                            deps.widgetConfigs().save(config)
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
     * 설정 목록. 통합 위젯(WG-11)은 탭마다 적용 범위가 달라 '모든 탭'(습관 탭은 이것만 따름)·'일정 탭'·'가계부 탭'으로 나눠 보인다.
     * 그 밖의 위젯은 해당하는 것만 한 줄로 이어 보인다.
     */
    @Composable
    private fun Settings(data: Loaded, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
        TargetSettings(data, config, onChange)
        if (tabbed) {
            ConfigSection(stringResource(R.string.widget_config_tab_all))
            ConfigNote(stringResource(R.string.widget_config_tab_all_note))
        } else if (kind in CALENDAR_KINDS) {
            ConfigSection(stringResource(R.string.widget_config_sources))
            SourcePicker(data.sources, config.dataSourceIds) { onChange(config.copy(dataSourceIds = it)) }
        }
        AppearanceSettings(config, onChange)
        if (tabbed) {
            ConfigSection(stringResource(R.string.widget_config_tab_calendar))
            ConfigSection(stringResource(R.string.widget_config_sources))
            SourcePicker(data.sources, config.dataSourceIds) { onChange(config.copy(dataSourceIds = it)) }
        }
        CalendarSettings(data, config, onChange)
        if (tabbed) ConfigSection(stringResource(R.string.widget_config_tab_ledger))
        // LD-06: 가계부 위젯·통합 위젯은 금액을 가릴 수 있다(●●●, 막대만).
        if (kind == Kind.LEDGER || tabbed) {
            SwitchRow(
                stringResource(R.string.widget_config_hide_amounts), config.hideAmounts, note = stringResource(R.string.widget_config_hide_amounts_note),
            ) { onChange(config.copy(hideAmounts = it)) }
        }
    }

    /** 위젯이 가리킬 대상: 디데이(DD-03·04, 고르지 않으면 가장 가까운 것)와 노션 보기(VW-04, 앱 설정의 '보기 추가'로 등록한 것). */
    @Composable
    private fun TargetSettings(data: Loaded, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
        if (kind == Kind.DDAY) {
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
        if (kind == Kind.VIEW || kind == Kind.BOOK) {
            ConfigSection(stringResource(R.string.widget_config_view))
            data.views.forEach { view ->
                RadioRow(view.name.ifBlank { stringResource(R.string.widget_config_view_untitled) }, config.viewId == view.viewId) { onChange(config.copy(viewId = view.viewId)) }
            }
            if (data.views.isEmpty()) ConfigNote(stringResource(R.string.widget_config_view_none))
        }
        if (kind == Kind.VIEW) {
            // IX-03: 숫자 열의 − + 가 한 번에 바꾸는 값. 정수 단계만 고른다(소수 단계는 시트에서 직접 고친다).
            ConfigSection(stringResource(R.string.widget_config_number_step))
            ChoiceChips(NUMBER_STEPS.map { it to null }, config.numberStep.toInt().takeIf { it.toDouble() == config.numberStep } ?: -1) {
                onChange(config.copy(numberStep = it.toDouble()))
            }
            PresetList(config, onChange)
        }
    }

    /** 일정 위젯 전용: 칸당 줄 수·줄 간격(월간), 머리 날씨(월간·달력 + 일정, WX-04·05), 완료 숨김. */
    @Composable
    private fun CalendarSettings(data: Loaded, config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
        if (kind == Kind.MONTH) {
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
        if (kind == Kind.MONTH || kind == Kind.SPLIT) {
            SwitchRow(
                stringResource(R.string.widget_config_weather), config.showWeather,
                note = stringResource(if (data.settings.weatherEnabled) R.string.widget_config_weather_note else R.string.widget_config_weather_off),
            ) { onChange(config.copy(showWeather = it)) }
            if (config.showWeather) WeatherAttribution()
        }
        if (kind in CALENDAR_KINDS) SwitchRow(stringResource(R.string.widget_config_hide_done), config.hideDone) { onChange(config.copy(hideDone = it)) }
    }

    private data class Loaded(
        val settings: AppSettings,
        val events: List<CalendarEvent>,
        val sources: List<DataSourceEntity>,
        val ledger: LedgerBoard? = null,
        val ddays: List<Dday> = emptyList(),
        val views: List<com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity> = emptyList(),
    )

    @OptIn(ExperimentalGlanceRemoteViewsApi::class)
    @Composable
    private fun Preview(data: Loaded, config: WidgetConfig, size: DpSize) {
        var views by remember { mutableStateOf<RemoteViews?>(null) }
        LaunchedEffect(config) {
            val today = LocalDate.now(APP_ZONE)
            views = when (kind) {
                Kind.AGENDA -> {
                    val state = AgendaWidget.uiState(today, config, data.events)
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) { AgendaWidgetContent(state) }.remoteViews
                }
                Kind.SPLIT -> {
                    val state = SplitWidget.uiState(today, data.settings, config, data.events)
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) { SplitWidgetContent(state) }.remoteViews
                }
                Kind.MONTH -> {
                    val state = NotionCalWidget.uiState(NotionCalWidget.monthFor(config), today, data.settings, config, data.events)
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) { WidgetContent(state) }.remoteViews
                }
                Kind.LEDGER -> {
                    val board = data.ledger ?: WidgetPreviews.ledgerState(today).board
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) { LedgerWidgetContent(LedgerWidgetState(board, config)) }.remoteViews
                }
                Kind.VIEW -> {
                    val state = WidgetPreviews.viewState().copy(config = config.copy(viewId = "preview"))
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) { ViewWidgetContent(state, staticRows = true) }.remoteViews
                }
                Kind.BOOK -> {
                    val state = WidgetPreviews.bookState().copy(config = config.copy(viewId = "preview"))
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) { BookWidgetContent(state) }.remoteViews
                }
                Kind.HABIT -> {
                    val state = WidgetPreviews.habitState(today).copy(config = config)
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) { HabitWidgetContent(state) }.remoteViews
                }
                Kind.DDAY, Kind.DDAY_LIST -> {
                    val state = DdayWidgetState(data.ddays, config, today)
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) {
                        if (kind == Kind.DDAY) DdayWidgetContent(state) else DdayListWidgetContent(state)
                    }.remoteViews
                }
            }
        }
        // 폭이 화면보다 넓으면 폭에 맞춰 줄이고, 높이는 화면의 일부까지만 보여 준다(남는 아래쪽은 자른다).
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val scale = previewScale(size, maxWidth)
            val maxHeight = with(LocalDensity.current) { LocalWindowInfo.current.containerSize.height.toDp() } * PREVIEW_MAX_SCREEN_FRACTION
            Box(Modifier.width(size.width * scale).height(previewHeight(size, scale, maxHeight)).clipToBounds()) {
                AndroidView(
                    factory = { FrameLayout(it) },
                    update = { frame ->
                        views?.let { remote ->
                            frame.removeAllViews()
                            frame.addView(remote.apply(frame.context, frame))
                        }
                    },
                    // requiredSize만 쓰면 부모보다 큰 내용이 가운데 기준으로 놓여 위가 잘린다: 위쪽 기준으로 놓는다.
                    modifier = Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).requiredSize(size.width, size.height)
                        .graphicsLayer { scaleX = scale; scaleY = scale; transformOrigin = TransformOrigin(0f, 0f) },
                )
            }
        }
    }

    private companion object {
        /** 일정 DB 고르기·완료 숨김을 쓰는 위젯. */
        val CALENDAR_KINDS = setOf(Kind.MONTH, Kind.AGENDA, Kind.SPLIT)

        /** 고정 미리보기가 차지하는 화면 높이 비율 상한(설정 목록에 나머지를 남긴다). */
        const val PREVIEW_MAX_SCREEN_FRACTION = 0.30f
    }
}

private val NUMBER_STEPS = listOf(1, 5, 10, 50, 100)

/** AD-05: 저장해 둔 프리셋 목록과 삭제(만들기는 새 항목 시트의 '프리셋으로 저장'이다). 프리셋이 없으면 아무것도 그리지 않는다. */
@Composable
private fun PresetList(config: WidgetConfig, onChange: (WidgetConfig) -> Unit) {
    val presets = ViewPresets.parse(config.presetsJson)
    if (presets.isEmpty()) return
    ConfigSection(stringResource(R.string.widget_config_presets))
    presets.forEach { preset ->
        val delete = stringResource(R.string.widget_config_preset_delete, preset.name)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(preset.name, Modifier.weight(1f))
            TextButton(onClick = { onChange(config.copy(presetsJson = ViewPresets.remove(config.presetsJson, preset.name))) }, modifier = Modifier.semantics { contentDescription = delete }) {
                Text(stringResource(R.string.widget_config_preset_delete_label), color = MaterialTheme.colorScheme.error)
            }
        }
    }
}
