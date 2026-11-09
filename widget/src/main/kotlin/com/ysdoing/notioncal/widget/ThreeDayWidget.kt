package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * WG-13 3일 위젯(2026-11-08 사용자 요청): 오늘·내일·모레를 세 칸으로 나눠 칸마다 일정을 쌓는다. 시간표는 Glance가 절대 위치를
 * 그릴 수 없어 하지 않고(사용자 선택), 월 위젯의 주 한 줄([WeekRow])을 세 칸·한 줄로 크게 그린다. 그래서 여러 날 일정은 칸을 걸친
 * 막대, 하루 일정은 '9:00 회의' 칩, 넘치면 +N, 칸을 누르면 그날 퀵 시트(WI-01)로 월 위젯과 똑같이 동작한다. Room만 읽는다(WG-02).
 * ‹ ›는 사흘씩 넘기고(이동값은 `timelineOffset`, 자정에 오늘로), 날씨를 켜면 요일 줄에 날짜마다 아이콘을 붙인다.
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
        val initialStart = startOf(initialToday, initialConfig)
        val initialEvents = deps.events().observeRange(initialStart, lastDay(initialStart)).first()
        val initialSync = WidgetSync.observe(deps.syncStatus()).first()
        val initialHolidays = deps.holidays().observe(initialStart, lastDay(initialStart)).first()

        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            val sync by remember { WidgetSync.observe(deps.syncStatus()) }.collectAsState(initialSync)
            // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4). 자정에 살아 있는 세션도 다시 그리게 관찰하는 오늘(WidgetToday).
            val today = widgetToday()
            val start = startOf(today, config)
            val events = rememberLatest(start, if (start == initialStart) initialEvents else emptyList()) { deps.events().observeRange(start, lastDay(start)) }
            val holidays by remember(start) { deps.holidays().observe(start, lastDay(start)) }
                .collectAsState(if (start == initialStart) initialHolidays else emptyMap())
            val weather by remember(today, config.showWeather) {
                if (config.showWeather) WidgetWeather.observe(deps.weather(), today) else flowOf(WidgetWeather())
            }.collectAsState(WidgetWeather())
            val state = uiState(today, config, events, sync, holidays).copy(weather = weather)
            ThreeDayWidgetContent(state, fontScale ?: SystemFontScale.current(context))
        }
    }

    companion object {
        const val DAYS = 3

        fun lastDay(start: LocalDate): LocalDate = start.plusDays(DAYS - 1L)

        /** 보는 사흘의 첫날: 오늘에서 ‹ ›로 넘긴 만큼(사흘 단위). */
        fun startOf(today: LocalDate, config: WidgetConfig): LocalDate = today.plusDays(DAYS.toLong() * config.timelineOffset)

        /**
         * 위젯별 DB 필터·완료 숨김(WG-04)을 건 3일 상태. 요일 줄·칸은 넘긴 사흘의 첫날부터, [WidgetUiState.today]는 실제 오늘이라
         * 다른 사흘을 볼 때는 오늘 네모가 없다. 달이 바뀌는 사흘도 흐리게 그리지 않는다.
         */
        fun uiState(
            today: LocalDate,
            config: WidgetConfig,
            events: List<CalendarEvent>,
            sync: WidgetSync = WidgetSync(),
            holidays: Map<LocalDate, String> = emptyMap(),
        ): WidgetUiState {
            val start = startOf(today, config)
            return WidgetUiState(
                month = YearMonth.from(start),
                today = today,
                weekStart = start.dayOfWeek,
                events = events.shownIn(config),
                config = config,
                sync = sync,
                holidays = holidays,
                dimOutsideMonth = false,
            )
        }

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

        /**
         * 머리줄 구성(제목 자리 약 80dp를 남긴다): 넓으면 칩·새로고침·‹ ›·+, 기본 4×2 폭이면 칩·새로고침을 빼고 ‹ ›·+(넘기기가 더 자주 쓰인다),
         * 그보다 좁으면 새로고침·+, 아주 좁으면 +만.
         */
        internal fun headerParts(width: Dp): HeaderParts = when {
            width >= FULL_HEADER_WIDTH -> HeaderParts(chip = true, refresh = true, nav = true)
            width >= NAV_MIN_WIDTH -> HeaderParts(chip = false, refresh = false, nav = true)
            width >= REFRESH_MIN_WIDTH -> HeaderParts(chip = false, refresh = true, nav = false)
            else -> HeaderParts(chip = false, refresh = false, nav = false)
        }

        private val FULL_HEADER_WIDTH = 340.dp
        private val NAV_MIN_WIDTH = 250.dp
    }
}

internal data class HeaderParts(val chip: Boolean, val refresh: Boolean, val nav: Boolean)

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
    val start = ThreeDayWidget.startOf(state.today, state.config)
    // 자식 3개: 머리 + 요일 줄 + 세 칸 한 줄.
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background)
            .padding(WidgetMetrics.PADDING),
    ) {
        ThreeDayHeader(state, start, HeaderLook(header, headerHeight, palette))
        ThreeDayWeekdays(start, if (state.config.showWeather) state.weather else WidgetWeather(), metrics, palette)
        WeekRow(start, state, metrics, palette, context)
    }
}

/**
 * 머리(공통 [WidgetHeader], 일정 목록 위젯과 같은 모양): '11/10 – 11/12' + 동기화 실패 글자 + 새로고침 + ‹ › + 채운 '+'.
 * 제목은 오늘 사흘이면 앱을 열고, 넘겨 둔 사흘이면 오늘로 돌아온다(월 위젯처럼 '오늘' 버튼 대신 제목이 맡는다).
 */
private class HeaderLook(val metrics: AgendaMetrics, val height: Dp, val palette: WidgetPalette)

@Composable
private fun ThreeDayHeader(state: WidgetUiState, start: LocalDate, look: HeaderLook) {
    val context = LocalContext.current
    val metrics = look.metrics
    val height = look.height
    val palette = look.palette
    val last = ThreeDayWidget.lastDay(start)
    val title = context.getString(R.string.three_day_widget_title, start.monthValue, start.dayOfMonth, last.monthValue, last.dayOfMonth)
    val parked = state.config.timelineOffset != 0
    val parts = ThreeDayWidget.headerParts(LocalSize.current.width)
    val visual = (height - 4.dp).coerceAtMost(32.dp)
    WidgetHeader(
        icon = R.drawable.ic_widget_calendar,
        title = title,
        // 실패는 색 점이 아니라 글자로 알린다(WS-3, A11Y-03). 실패해도 일정은 캐시 그대로 그린다(PF-05).
        subtitle = if (state.sync.failed) context.getString(R.string.widget_sync_error_short) else null,
        palette = palette,
        height = height,
        titleSp = metrics.headerFontSp,
        subSp = metrics.timeFontSp,
        titleModifier = if (parked) {
            GlanceModifier.clickable(timelineNavAction(0)).semantics {
                contentDescription = context.getString(R.string.view_calendar_title_back, title)
                testTag = "threeDayTitle"
            }
        } else {
            GlanceModifier.clickable(actionStartActivity(appIntent(context))).semantics { testTag = "threeDayTitle" }
        },
        showChip = parts.chip,
    ) {
        if (parts.refresh) {
            WidgetIconButton(R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "refresh", actionRunCallback<RefreshAction>(), palette, visual = visual, height = height)
        }
        if (parts.nav) {
            WidgetIconButton(R.drawable.ic_widget_prev, context.getString(R.string.three_day_widget_prev), "prev", timelineNavAction(-1), palette, visual = visual, height = height)
            WidgetIconButton(R.drawable.ic_widget_next, context.getString(R.string.three_day_widget_next), "next", timelineNavAction(1), palette, visual = visual, height = height)
        }
        WidgetIconButton(
            R.drawable.ic_widget_add, context.getString(R.string.widget_add), "add",
            actionStartActivity(quickSheetIntent(context, AppIntents.MODE_ADD, state.today)), palette,
            style = IconButtonStyle.FILLED, visual = visual, height = height,
        )
    }
}

/** 요일 줄(월 위젯의 [WeekdayRow]와 같은 색)에 날씨를 켜면 날짜마다 작은 아이콘(예보가 있는 날만, 기온은 칸이 좁아 뺀다). 자식 3개. */
@Composable
private fun ThreeDayWeekdays(start: LocalDate, weather: WidgetWeather, metrics: WidgetMetrics, palette: WidgetPalette) {
    Row(GlanceModifier.fillMaxWidth().height(metrics.weekdayHeight)) {
        repeat(ThreeDayWidget.DAYS) { i ->
            val date = start.plusDays(i.toLong())
            Row(
                GlanceModifier.width(metrics.cellWidth).height(metrics.weekdayHeight),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
                    style = TextStyle(color = palette.dayColor(date.dayOfWeek), fontSize = metrics.weekdayFontSp.sp, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
                weather.days[date]?.let { day ->
                    WeatherChip(day, weather.stale, palette, metrics.weekdayFontSp, showTemps = false, iconSize = (metrics.weekdayHeight - 2.dp).coerceIn(8.dp, 12.dp))
                }
            }
        }
    }
}
