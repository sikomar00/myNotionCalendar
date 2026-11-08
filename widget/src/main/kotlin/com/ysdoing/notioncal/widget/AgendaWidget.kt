package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
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
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.calendar.Agenda
import com.ysdoing.notioncal.core.data.calendar.AgendaDay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** WG-06 보조 위젯이 보여 주는 기간(오늘 포함 7일). */
const val AGENDA_DAYS = 7L

data class AgendaUiState(
    val today: LocalDate,
    /** 일정이 있는 날만, 오늘은 일정이 없어도 들어 있다. */
    val days: List<AgendaDay>,
    val config: WidgetConfig,
    val sync: WidgetSync = WidgetSync(),
    /** EX-02 공휴일(날짜 → 이름). */
    val holidays: Map<LocalDate, String> = emptyMap(),
)

/**
 * WG-06 보조 위젯: 오늘부터 7일 동안의 일정 목록. Room만 읽는다(WG-02).
 * 설정(DB 선택·완료 숨김·테마·투명도·글자 배율)은 월 위젯과 같은 [WidgetConfig]를 쓴다.
 */
class AgendaWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** WG-08 위젯 선택 화면 미리보기: 4×2 크기에 예시 일정(개인 일정 아님). */
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetPreviews.AGENDA_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.agendaState()
        provideContent { AgendaWidgetContent(state, systemFontScale = 1f) }
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
            AgendaWidgetContent(uiState(today, config, events, sync, holidays), fontScale ?: SystemFontScale.current(context))
        }
    }

    companion object {
        fun lastDay(today: LocalDate): LocalDate = today.plusDays(AGENDA_DAYS - 1)

        /** 위젯별 DB 필터·완료 숨김(WG-04)을 건 뒤 날짜별로 묶는다. */
        fun uiState(
            today: LocalDate,
            config: WidgetConfig,
            events: List<CalendarEvent>,
            sync: WidgetSync = WidgetSync(),
            holidays: Map<LocalDate, String> = emptyMap(),
        ): AgendaUiState {
            val shown = events.filter { event ->
                (config.dataSourceIds.isEmpty() || event.dataSourceId in config.dataSourceIds) && !(config.hideDone && event.done)
            }
            // 공휴일만 있는 날도 목록에 넣는다(EX-02).
            return AgendaUiState(today, Agenda.build(shown, today, lastDay(today), today, holidays = holidays.keys), config, sync, holidays)
        }
    }
}

/**
 * 보조 위젯 치수. 줄 높이는 월 위젯과 같은 실측 규칙(글자 칸 = 글자 크기 × [WidgetMetrics.TEXT_BOX], 기기 글꼴 상한
 * [WidgetMetrics.MAX_SYSTEM_TEXT_SCALE])으로 정해 2×2~4×2에서도 글자 위아래가 잘리지 않는다. 넘치는 줄은 목록 스크롤로 본다.
 */
internal data class AgendaMetrics(
    val headerHeight: Dp,
    /** 동기화 실패 글자 부제가 붙는 머리. */
    val headerWithSub: Dp,
    val dateHeight: Dp,
    val itemHeight: Dp,
    val timeWidth: Dp,
    val headerFontSp: Float,
    val dateFontSp: Float,
    val itemFontSp: Float,
    val timeFontSp: Float,
) {
    companion object {
        fun of(config: WidgetConfig, systemFontScale: Float): AgendaMetrics {
            val scale = config.fontScale / 100f
            val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            val spFactor = textScale / systemFontScale
            fun box(fontSp: Float) = (fontSp * textScale * WidgetMetrics.TEXT_BOX).dp
            val header = 13f
            val date = 11f * scale
            val item = 12f * scale
            val time = 10f * scale
            return AgendaMetrics(
                headerHeight = maxOf(32.dp, box(header) + 6.dp),
                headerWithSub = maxOf(40.dp, box(header) + box(time) + 4.dp),
                dateHeight = box(date) + 4.dp,
                itemHeight = box(item) + 6.dp,
                timeWidth = (time * textScale * 4.4f).dp,
                headerFontSp = header * spFactor,
                dateFontSp = date * spFactor,
                itemFontSp = item * spFactor,
                timeFontSp = time * spFactor,
            )
        }
    }
}

@Composable
fun AgendaWidgetContent(
    state: AgendaUiState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
) {
    val context = LocalContext.current
    val metrics = AgendaMetrics.of(state.config, systemFontScale)
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AgendaHeader(state, metrics, palette)
        val entries = buildList {
            state.days.forEach { day ->
                add(ListEntry(day.date.toEpochDay()) { DateRow(day.date, state.today, state.holidays[day.date], metrics, palette) })
                if (day.items.isEmpty() && day.date !in state.holidays) {
                    add(ListEntry(-day.date.toEpochDay() - 1) {
                        WidgetEmptyRow(metrics.itemHeight, metrics.itemFontSp, palette, tag = "agendaEmpty", startPadding = 12.dp)
                    })
                }
                day.items.forEach { (event, continues) ->
                    add(ListEntry((event.pageId + day.date).hashCode().toLong()) {
                        WidgetEventRow(
                            event, day.date, metrics.itemHeight, metrics.itemFontSp, metrics.timeFontSp, palette,
                            tag = "agendaItem", dotWidth = 14.dp, timeColumn = metrics.timeWidth, continues = continues,
                        )
                    })
                }
            }
        }
        WidgetList(entries, GlanceModifier.fillMaxWidth().defaultWeight())
    }
}

/** 머리(공통 [WidgetHeader]): 달력 아이콘 + 오늘 날짜(누르면 앱) + 동기화 실패면 글자 부제 + 채운 '+'(WI-02, 오늘 날짜로 입력칸). */
@Composable
private fun AgendaHeader(state: AgendaUiState, metrics: AgendaMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val today = state.today
    val failed = state.sync.failed
    val height = if (failed) metrics.headerWithSub else metrics.headerHeight
    WidgetHeader(
        icon = R.drawable.ic_widget_calendar,
        title = context.getString(R.string.agenda_widget_title, today.monthValue, today.dayOfMonth, today.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN)),
        // 실패는 색 점이 아니라 글자로 알린다(WS-3, A11Y-03). 실패해도 일정은 캐시 그대로 그린다(PF-05).
        subtitle = if (failed) context.getString(R.string.widget_sync_error_short) else null,
        palette = palette,
        height = height,
        titleSp = metrics.headerFontSp,
        subSp = metrics.timeFontSp,
        titleModifier = GlanceModifier.clickable(actionStartActivity(appIntent(context))).semantics { testTag = "agendaTitle" },
    ) {
        // NS-06 새로고침: 노션 보기·습관 위젯과 같은 자리(+ 앞)·같은 폭 기준.
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

@Composable
private fun DateRow(date: LocalDate, today: LocalDate, holiday: String?, metrics: AgendaMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val day = if (date == today) {
        context.getString(R.string.agenda_widget_today)
    } else {
        context.getString(
            R.string.agenda_widget_date,
            date.monthValue,
            date.dayOfMonth,
            date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
        )
    }
    // EX-02: 공휴일은 날짜 줄에 이름을 붙이고 빨강으로.
    val label = listOfNotNull(day, holiday).joinToString(" ")
    val isToday = date == today
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(metrics.dateHeight)
            .clickable(actionStartActivity(quickSheetIntent(context, AppIntents.MODE_DAY, date)))
            .semantics { testTag = "agendaDate" },
        contentAlignment = Alignment.CenterStart,
    ) {
        // 오늘은 월간과 같은 칠한 네모(DESIGN.md 원칙 3). 다른 날은 요일 색 글자.
        Text(
            text = label,
            modifier = if (isToday) GlanceModifier.background(palette.todayBackground).cornerRadius(WidgetShape.TODAY).padding(horizontal = 4.dp) else GlanceModifier,
            style = TextStyle(
                color = when {
                    isToday -> palette.todayText
                    holiday != null -> palette.holiday
                    else -> palette.dayColor(date.dayOfWeek)
                },
                fontSize = metrics.dateFontSp.sp,
                fontWeight = FontWeight.Bold,
            ),
            maxLines = 1,
        )
    }
}
