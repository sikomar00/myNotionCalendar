package com.ysdoing.notioncal.widget

import android.content.Context
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

/**
 * 월간 위젯(WG-01). Room만 읽는다(WG-02).
 *
 * 크기 모드는 Exact를 쓴다. Responsive는 실제 크기보다 작은 구간 크기로 그려서, 칸(실제 폭)과
 * 여러 날 막대(구간 폭 기준 dp 폭)가 어긋난다. 작음·중간·큼 3단계(WS-03)는 실제 크기로 나눈다.
 */
class NotionCalWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        // 첫 프레임부터 내용이 보이도록 초기값을 먼저 읽는다. 이후 변경은 아래 Flow가 반영한다.
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initialSettings = deps.settings().settings.first()
        val initialMonth = monthFor(initialConfig)
        val initialEvents = deps.events().observeRange(gridFrom(initialMonth, initialSettings.weekStart), gridTo(initialMonth, initialSettings.weekStart)).first()
        val initialSync = WidgetSync.observe(deps.syncStatus()).first()

        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val settings by remember { deps.settings().settings }.collectAsState(initialSettings)
            val fontScale by SystemFontScale.flow.collectAsState()
            val sync by remember { WidgetSync.observe(deps.syncStatus()) }.collectAsState(initialSync)
            // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4).
            val today = LocalDate.now(APP_ZONE)
            val month = YearMonth.from(today).plusMonths(config.monthOffset.toLong())
            val initial = if (month == initialMonth && settings.weekStart == initialSettings.weekStart) initialEvents else emptyList()
            val events by remember(month, settings.weekStart) {
                deps.events().observeRange(gridFrom(month, settings.weekStart), gridTo(month, settings.weekStart))
            }.collectAsState(initial)
            WidgetContent(uiState(month, today, settings, config, events, sync), fontScale ?: SystemFontScale.current(context))
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
