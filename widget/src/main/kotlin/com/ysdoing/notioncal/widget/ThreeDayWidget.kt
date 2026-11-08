package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth

/**
 * WG-13 3일 위젯(2026-11-08 사용자 요청): 오늘·내일·모레를 세 칸으로 나눠 칸마다 일정을 쌓는다. 시간표는 Glance가 절대 위치를
 * 그릴 수 없어 하지 않고(사용자 선택), 월 위젯의 주 한 줄([WeekRow])을 세 칸·한 줄로 크게 그린다. 그래서 여러 날 일정은 칸을 걸친
 * 막대, 하루 일정은 '9:00 회의' 칩, 넘치면 +N, 칸을 누르면 그날 퀵 시트(WI-01)로 월 위젯과 똑같이 동작한다. Room만 읽는다(WG-02).
 */
class ThreeDayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** WG-08 위젯 선택 화면 미리보기: 4×2 크기에 예시 일정(개인 일정 아님). */
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetPreviews.AGENDA_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.threeDayState()
        provideContent { ThreeDayWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialToday = LocalDate.now(APP_ZONE)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initialEvents = deps.events().observeRange(initialToday, lastDay(initialToday)).first()
        val initialSync = WidgetSync.observe(deps.syncStatus()).first()
        val initialHolidays = deps.holidays().observe(initialToday, lastDay(initialToday)).first()

        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            val sync by remember { WidgetSync.observe(deps.syncStatus()) }.collectAsState(initialSync)
            // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4). 자정 알람이 위젯을 다시 그리게 한다.
            val today = LocalDate.now(APP_ZONE)
            val events by remember(today) { deps.events().observeRange(today, lastDay(today)) }
                .collectAsState(if (today == initialToday) initialEvents else emptyList())
            val holidays by remember(today) { deps.holidays().observe(today, lastDay(today)) }
                .collectAsState(if (today == initialToday) initialHolidays else emptyMap())
            ThreeDayWidgetContent(uiState(today, config, events, sync, holidays), fontScale ?: SystemFontScale.current(context))
        }
    }

    companion object {
        const val DAYS = 3

        fun lastDay(today: LocalDate): LocalDate = today.plusDays(DAYS - 1L)

        /** 위젯별 DB 필터·완료 숨김(WG-04)을 건 3일 상태. 달이 바뀌는 사흘도 흐리게 그리지 않는다. */
        fun uiState(
            today: LocalDate,
            config: WidgetConfig,
            events: List<CalendarEvent>,
            sync: WidgetSync = WidgetSync(),
            holidays: Map<LocalDate, String> = emptyMap(),
        ) = WidgetUiState(
            month = YearMonth.from(today),
            today = today,
            weekStart = today.dayOfWeek,
            events = events.shownIn(config),
            config = config,
            sync = sync,
            holidays = holidays,
            dimOutsideMonth = false,
        )

        /** 머리줄을 뺀 면에 세 칸·한 줄을 그리는 치수. 주 한 줄이 높으니 크기 단계는 큼에서 시작하고, 글자가 넘치면 월 위젯처럼 내린다. */
        fun gridMetrics(size: DpSize, config: WidgetConfig, systemFontScale: Float, headerHeight: Dp): WidgetMetrics {
            val area = GridArea(
                width = size.width - WidgetMetrics.PADDING * 2,
                height = (size.height - WidgetMetrics.PADDING * 2 - headerHeight).coerceAtLeast(0.dp),
                weeks = 1,
                days = DAYS,
                startMode = WidgetMode.LARGE,
            )
            return WidgetMetrics.of(size, config, systemFontScale, grid = area)
        }
    }
}

@Composable
fun ThreeDayWidgetContent(
    state: WidgetUiState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
) {
    val context = LocalContext.current
    val header = AgendaMetrics.of(state.config, systemFontScale)
    val headerHeight = if (state.sync.failed) header.headerWithSub else header.headerHeight
    val metrics = ThreeDayWidget.gridMetrics(LocalSize.current, state.config, systemFontScale, headerHeight)
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    // 자식 3개: 머리 + 요일 줄 + 세 칸 한 줄.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background)
            .padding(WidgetMetrics.PADDING),
    ) {
        ThreeDayHeader(state, header, headerHeight, palette)
        WeekdayRow(state.today.dayOfWeek, metrics, palette)
        WeekRow(state.today, state, metrics, palette, context)
    }
}

/** 머리(공통 [WidgetHeader], 일정 목록 위젯과 같은 모양): '11/10 – 11/12'(누르면 앱) + 동기화 실패 글자 + 새로고침 + 채운 '+'. */
@Composable
private fun ThreeDayHeader(state: WidgetUiState, metrics: AgendaMetrics, height: Dp, palette: WidgetPalette) {
    val context = LocalContext.current
    val today = state.today
    val last = ThreeDayWidget.lastDay(today)
    WidgetHeader(
        icon = R.drawable.ic_widget_calendar,
        title = context.getString(R.string.three_day_widget_title, today.monthValue, today.dayOfMonth, last.monthValue, last.dayOfMonth),
        // 실패는 색 점이 아니라 글자로 알린다(WS-3, A11Y-03). 실패해도 일정은 캐시 그대로 그린다(PF-05).
        subtitle = if (state.sync.failed) context.getString(R.string.widget_sync_error_short) else null,
        palette = palette,
        height = height,
        titleSp = metrics.headerFontSp,
        subSp = metrics.timeFontSp,
        titleModifier = GlanceModifier.clickable(actionStartActivity(appIntent(context))).semantics { testTag = "threeDayTitle" },
    ) {
        if (LocalSize.current.width >= REFRESH_MIN_WIDTH) {
            WidgetIconButton(
                R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "refresh", actionRunCallback<RefreshAction>(), palette,
                visual = (height - 4.dp).coerceAtMost(32.dp), height = height,
            )
        }
        WidgetIconButton(
            R.drawable.ic_widget_add, context.getString(R.string.widget_add), "add",
            actionStartActivity(quickSheetIntent(context, AppIntents.MODE_ADD, today)), palette,
            style = IconButtonStyle.FILLED, visual = (height - 4.dp).coerceAtMost(32.dp), height = height,
        )
    }
}
