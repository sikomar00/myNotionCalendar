package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.provideContent
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.calendar.GRID_DAYS
import com.ysdoing.notioncal.core.data.calendar.gridStart
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** 월 위젯과 통합 위젯(📅 탭, WG-11)이 함께 쓰는 세션. 첫 프레임부터 내용이 보이도록 초기값을 먼저 읽는다. */
internal object MonthSession {
    class Initial(
        val settings: AppSettings,
        val month: YearMonth,
        val events: List<CalendarEvent>,
        val sync: WidgetSync,
        val holidays: Map<LocalDate, String>,
    )

    suspend fun load(deps: WidgetDependencies, config: WidgetConfig): Initial {
        val settings = deps.settings().settings.first()
        val month = NotionCalWidget.monthFor(config)
        val from = NotionCalWidget.gridFrom(month, settings.weekStart)
        val to = NotionCalWidget.gridTo(month, settings.weekStart)
        return Initial(
            settings = settings,
            month = month,
            events = deps.events().observeRange(from, to).first(),
            sync = WidgetSync.observe(deps.syncStatus()).first(),
            holidays = deps.holidays().observe(from, to).first(),
        )
    }

    /** 이후 변경은 Flow가 반영한다. */
    @Composable
    fun Content(deps: WidgetDependencies, initial: Initial, config: WidgetConfig, fontScale: Float, framed: Boolean = true) {
        val settings by remember { deps.settings().settings }.collectAsState(initial.settings)
        val sync by remember { WidgetSync.observe(deps.syncStatus()) }.collectAsState(initial.sync)
        // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4).
        val today = LocalDate.now(APP_ZONE)
        val month = YearMonth.from(today).plusMonths(config.monthOffset.toLong())
        val sameGrid = month == initial.month && settings.weekStart == initial.settings.weekStart
        val events by remember(month, settings.weekStart) {
            deps.events().observeRange(NotionCalWidget.gridFrom(month, settings.weekStart), NotionCalWidget.gridTo(month, settings.weekStart))
        }.collectAsState(if (sameGrid) initial.events else emptyList())
        val holidays by remember(month, settings.weekStart) {
            deps.holidays().observe(NotionCalWidget.gridFrom(month, settings.weekStart), NotionCalWidget.gridTo(month, settings.weekStart))
        }.collectAsState(if (sameGrid) initial.holidays else emptyMap())
        WidgetContent(NotionCalWidget.uiState(month, today, settings, config, events, sync).copy(holidays = holidays), fontScale, framed)
    }
}

/**
 * 월간 위젯(WG-01). Room만 읽는다(WG-02).
 *
 * 크기 모드는 Exact를 쓴다. Responsive는 실제 크기보다 작은 구간 크기로 그려서, 칸(실제 폭)과
 * 여러 날 막대(구간 폭 기준 dp 폭)가 어긋난다. 작음·중간·큼 3단계(WS-03)는 실제 크기로 나눈다.
 */
class NotionCalWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** WG-08 위젯 선택 화면 미리보기: 4×4 크기에 예시 일정(개인 일정 아님). */
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetPreviews.MONTH_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.monthState()
        provideContent { WidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initial = MonthSession.load(deps, initialConfig)
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            MonthSession.Content(deps, initial, config, fontScale ?: SystemFontScale.current(context))
        }
    }

    companion object {
        fun monthFor(config: WidgetConfig): YearMonth = YearMonth.now(APP_ZONE).plusMonths(config.monthOffset.toLong())

        fun gridFrom(month: YearMonth, weekStart: DayOfWeek): LocalDate = gridStart(month, weekStart)

        fun gridTo(month: YearMonth, weekStart: DayOfWeek): LocalDate = gridStart(month, weekStart).plusDays(GRID_DAYS - 1)

        /** 위젯별 DB 필터(빈 값 = 전체)와 완료 숨김을 적용한다(WG-04). */
        fun uiState(
            month: YearMonth,
            today: LocalDate,
            settings: AppSettings,
            config: WidgetConfig,
            events: List<CalendarEvent>,
            sync: WidgetSync = WidgetSync(),
        ) = WidgetUiState(
            month = month,
            today = today,
            weekStart = settings.weekStart,
            events = events.filter { event ->
                (config.dataSourceIds.isEmpty() || event.dataSourceId in config.dataSourceIds) && !(config.hideDone && event.done)
            },
            config = config,
            sync = sync,
        )
    }
}
