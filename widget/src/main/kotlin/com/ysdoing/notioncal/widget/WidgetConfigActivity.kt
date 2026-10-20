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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.ui.graphics.Color
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
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import com.ysdoing.notioncal.core.data.dday.Dday
import com.ysdoing.notioncal.core.designsystem.WeatherAttribution
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import kotlin.math.roundToInt
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
     * 통합 위젯(WG-11)은 📅 탭이 월 위젯 내용이라 MONTH로 다룬다. 습관 위젯(HB-03)은 설정 화면이 없다.
     */
    private enum class Kind { MONTH, AGENDA, SPLIT, LEDGER, DDAY, DDAY_LIST }

    private val kind by lazy {
        when (AppWidgetManager.getInstance(this).getAppWidgetInfo(appWidgetId)?.provider?.className) {
            AgendaWidgetReceiver::class.java.name -> Kind.AGENDA
            SplitWidgetReceiver::class.java.name -> Kind.SPLIT
            LedgerWidgetReceiver::class.java.name -> Kind.LEDGER
            DdaySmallWidgetReceiver::class.java.name, DdayWideWidgetReceiver::class.java.name -> Kind.DDAY
            DdayListWidgetReceiver::class.java.name -> Kind.DDAY_LIST
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
            val events = if (kind == Kind.MONTH) {
                deps.events().observeRange(NotionCalWidget.gridFrom(month, settings.weekStart), NotionCalWidget.gridTo(month, settings.weekStart)).first()
            } else {
                deps.events().observeRange(today, AgendaWidget.lastDay(today)).first()
            }
            config = saved
            val ledger = if (kind == Kind.LEDGER) deps.ledger().observeBoard(YearMonth.from(today), today).first() else null
            val ddays = if (kind == Kind.DDAY || kind == Kind.DDAY_LIST) deps.ddays().observeVisible(today).first() else emptyList()
            loaded = Loaded(settings, events, deps.dataSources().observeCalendarSources().first(), ledger, ddays)
        }
        val data = loaded ?: return

        Column(
            Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(stringResource(R.string.widget_config_title), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.widget_config_preview), style = MaterialTheme.typography.labelLarge)
            Preview(data, config, previewSize)

            // DD-03·04: 위젯마다 보여 줄 디데이(고르지 않으면 가장 가까운 디데이).
            if (kind == Kind.DDAY) {
                Section(stringResource(R.string.widget_config_dday))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = config.ddayPageId == null, onClick = { config = config.copy(ddayPageId = null) })
                    Text(stringResource(R.string.widget_config_dday_nearest))
                }
                data.ddays.forEach { dday ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = config.ddayPageId == dday.pageId, onClick = { config = config.copy(ddayPageId = dday.pageId) })
                        dday.label?.let { Text(it.text, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 8.dp)) }
                        Text(dday.title.orEmpty())
                    }
                }
                if (data.ddays.isEmpty()) {
                    Text(stringResource(R.string.widget_config_dday_none), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }

            if (kind in CALENDAR_KINDS) {
                Section(stringResource(R.string.widget_config_sources))
                SourcePicker(data.sources, config.dataSourceIds) { config = config.copy(dataSourceIds = it) }
            }

            Section(stringResource(R.string.widget_config_theme))
            Choice(
                listOf(
                    WidgetTheme.SYSTEM to R.string.widget_config_theme_system,
                    WidgetTheme.LIGHT to R.string.widget_config_theme_light,
                    WidgetTheme.DARK to R.string.widget_config_theme_dark,
                    WidgetTheme.MATERIAL_YOU to R.string.widget_config_theme_material_you,
                ),
                config.theme,
            ) { config = config.copy(theme = it) }

            Section(stringResource(R.string.widget_config_opacity, config.opacity))
            Slider(
                value = config.opacity.toFloat(),
                onValueChange = { config = config.copy(opacity = (it / 10).roundToInt() * 10) },
                valueRange = 0f..100f,
                steps = 9,
            )

            Section(stringResource(R.string.widget_config_font, config.fontScale))
            Slider(
                value = config.fontScale.toFloat(),
                onValueChange = { config = config.copy(fontScale = (it / 10).roundToInt() * 10) },
                valueRange = WidgetConfig.FONT_SCALE_RANGE.first.toFloat()..WidgetConfig.FONT_SCALE_RANGE.last.toFloat(),
                steps = (WidgetConfig.FONT_SCALE_RANGE.last - WidgetConfig.FONT_SCALE_RANGE.first) / 10 - 1,
            )

            if (kind == Kind.MONTH) {
                Section(stringResource(R.string.widget_config_lanes))
                Choice(
                    WidgetConfig.MAX_PER_CELL_RANGE.map { it to if (it == 0) R.string.widget_config_lanes_auto else null },
                    config.maxPerCell,
                ) { config = config.copy(maxPerCell = it) }

                Section(stringResource(R.string.widget_config_spacing))
                Choice(
                    listOf(
                        LineSpacing.COMPACT to R.string.widget_config_spacing_compact,
                        LineSpacing.NORMAL to R.string.widget_config_spacing_normal,
                        LineSpacing.WIDE to R.string.widget_config_spacing_wide,
                    ),
                    config.lineSpacing,
                ) { config = config.copy(lineSpacing = it) }
            }

            // WX-04·05: 월간·'달력 + 일정' 위젯 머리에 오늘 날씨(위젯별 켜기). 날씨가 보이는 설정 화면이라 출처를 둔다.
            if (kind == Kind.MONTH || kind == Kind.SPLIT) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.widget_config_weather))
                        Text(
                            stringResource(if (data.settings.weatherEnabled) R.string.widget_config_weather_note else R.string.widget_config_weather_off),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = config.showWeather, onCheckedChange = { config = config.copy(showWeather = it) })
                }
                if (config.showWeather) WeatherAttribution()
            }
            if (kind in CALENDAR_KINDS) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.widget_config_hide_done), Modifier.weight(1f))
                    Switch(checked = config.hideDone, onCheckedChange = { config = config.copy(hideDone = it) })
                }
            }
            // LD-06: 가계부 위젯·통합 위젯은 금액을 가릴 수 있다(●●●, 막대만).
            if (kind == Kind.LEDGER || tabbed) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.widget_config_hide_amounts))
                        Text(
                            stringResource(R.string.widget_config_hide_amounts_note),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = config.hideAmounts, onCheckedChange = { config = config.copy(hideAmounts = it) })
                }
            }

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
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.widget_config_save)) }
        }
    }

    private data class Loaded(
        val settings: AppSettings,
        val events: List<CalendarEvent>,
        val sources: List<DataSourceEntity>,
        val ledger: LedgerBoard? = null,
        val ddays: List<Dday> = emptyList(),
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
                Kind.DDAY, Kind.DDAY_LIST -> {
                    val state = DdayWidgetState(data.ddays, config, today)
                    GlanceRemoteViews().compose(this@WidgetConfigActivity, size) {
                        if (kind == Kind.DDAY) DdayWidgetContent(state) else DdayListWidgetContent(state)
                    }.remoteViews
                }
            }
        }
        AndroidView(
            factory = { FrameLayout(it) },
            update = { frame ->
                views?.let { remote ->
                    frame.removeAllViews()
                    frame.addView(remote.apply(frame.context, frame))
                }
            },
            modifier = Modifier.width(size.width).height(size.height),
        )
    }

    private companion object {
        /** 일정 DB 고르기·완료 숨김을 쓰는 위젯. */
        val CALENDAR_KINDS = setOf(Kind.MONTH, Kind.AGENDA, Kind.SPLIT)
    }

    @Composable
    private fun Section(title: String) {
        // 구역 제목은 먹색 굵은 글자(화면 틀에 강조색을 쓰지 않음, DESIGN.md).
        Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
    }

    @Composable
    private fun SourcePicker(sources: List<DataSourceEntity>, selected: Set<String>, onChange: (Set<String>) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = selected.isEmpty(), onCheckedChange = { if (it) onChange(emptySet()) })
            Text(stringResource(R.string.widget_config_sources_all))
        }
        sources.forEach { source ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = source.dataSourceId in selected,
                    onCheckedChange = { checked ->
                        onChange(if (checked) selected + source.dataSourceId else selected - source.dataSourceId)
                    },
                )
                Text(source.name)
            }
        }
    }

    @Composable
    private fun <T> Choice(options: List<Pair<T, Int?>>, selected: T, onSelect: (T) -> Unit) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            options.forEach { (value, label) ->
                FilterChip(
                    selected = value == selected,
                    onClick = { onSelect(value) },
                    label = { Text(label?.let { stringResource(it) } ?: value.toString()) },
                )
            }
        }
    }
}
