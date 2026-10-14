package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
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
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.calendar.Agenda
import com.ysdoing.notioncal.core.data.calendar.AgendaDay
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.DateTimeFormatter
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

private val agendaTimeFormat = DateTimeFormatter.ofPattern("H:mm")

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
        LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
            state.days.forEach { day ->
                item(itemId = day.date.toEpochDay()) { DateRow(day.date, state.today, state.holidays[day.date], metrics, palette) }
                if (day.items.isEmpty() && day.date !in state.holidays) {
                    item(itemId = -day.date.toEpochDay() - 1) { EmptyRow(metrics, palette) }
                }
                items(day.items, itemId = { (event, _) -> (event.pageId + day.date).hashCode().toLong() }) { (event, continues) ->
                    EventRow(event, continues, day.date, metrics, palette)
                }
            }
        }
    }
}

@Composable
private fun AgendaHeader(state: AgendaUiState, metrics: AgendaMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val today = state.today
    Row(GlanceModifier.fillMaxWidth().height(metrics.headerHeight), verticalAlignment = Alignment.CenterVertically) {
        Row(
            modifier = GlanceModifier
                .defaultWeight()
                .height(metrics.headerHeight)
                .clickable(actionStartActivity(appIntent(context)))
                .semantics { testTag = "agendaTitle" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(
                    R.string.agenda_widget_title,
                    today.monthValue,
                    today.dayOfMonth,
                    today.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
                ),
                style = TextStyle(color = palette.text, fontSize = metrics.headerFontSp.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            SyncBadge(state.sync, today, metrics.timeFontSp, palette, showTime = false)
        }
        // WI-02 퀵 추가: 오늘 날짜로 입력칸이 열린다. 터치 영역은 48dp(A11Y-02).
        Box(
            modifier = GlanceModifier
                .width(48.dp)
                .height(metrics.headerHeight)
                .clickable(actionStartActivity(quickSheetIntent(context, AppIntents.MODE_ADD, today)))
                .semantics {
                    contentDescription = context.getString(R.string.widget_add)
                    testTag = "add"
                },
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = TextStyle(color = palette.accent, fontSize = metrics.headerFontSp.sp, fontWeight = FontWeight.Medium))
        }
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
    Text(
        text = label,
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(metrics.dateHeight)
            .padding(top = 2.dp)
            .clickable(actionStartActivity(quickSheetIntent(context, AppIntents.MODE_DAY, date)))
            .semantics { testTag = "agendaDate" },
        style = TextStyle(
            color = when {
                holiday != null -> palette.holiday
                date == today -> palette.accent
                else -> palette.dayColor(date.dayOfWeek)
            },
            fontSize = metrics.dateFontSp.sp,
            fontWeight = FontWeight.Bold,
        ),
        maxLines = 1,
    )
}

@Composable
private fun EmptyRow(metrics: AgendaMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Text(
        text = context.getString(R.string.agenda_widget_empty),
        modifier = GlanceModifier.fillMaxWidth().height(metrics.itemHeight).padding(start = 12.dp).semantics { testTag = "agendaEmpty" },
        style = TextStyle(color = palette.subtle, fontSize = metrics.itemFontSp.sp),
        maxLines = 1,
    )
}

@Composable
private fun EventRow(event: CalendarEvent, continues: Boolean, date: LocalDate, metrics: AgendaMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val color = ColorProvider(Color(NotionColors.argb(event.colorHex)))
    val time = when {
        event.isMultiDay -> context.getString(if (continues) R.string.agenda_widget_continues else R.string.agenda_widget_multi_day)
        event.startTime == null -> context.getString(R.string.agenda_widget_all_day)
        else -> event.startTime!!.atZone(APP_ZONE).format(agendaTimeFormat)
    }
    val title = event.title.ifBlank { context.getString(R.string.agenda_widget_untitled) }
    // 일정을 누르면 그 일정의 편집 시트(WI-05). PendingIntent가 일정마다 구분되도록 데이터 URI에 페이지 ID를 넣는다.
    val edit = quickSheetIntent(context, AppIntents.MODE_EDIT, date)
        .setData("notioncal://edit/$date/${event.pageId}".toUri())
        .putExtra(AppIntents.EXTRA_PAGE_ID, event.pageId)
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(metrics.itemHeight)
            .clickable(actionStartActivity(edit))
            .semantics {
                contentDescription = listOfNotNull(
                    title,
                    time,
                    context.getString(R.string.agenda_widget_done).takeIf { event.done },
                ).joinToString(", ")
                testTag = "agendaItem"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A11Y-03: 완료는 색 점 대신 체크 + 취소선(색만으로 구분하지 않음).
        Text(
            text = if (event.done) "✓" else "●",
            modifier = GlanceModifier.width(14.dp),
            style = TextStyle(color = color, fontSize = metrics.timeFontSp.sp),
            maxLines = 1,
        )
        Text(
            text = time,
            modifier = GlanceModifier.width(metrics.timeWidth),
            style = TextStyle(color = palette.subtle, fontSize = metrics.timeFontSp.sp),
            maxLines = 1,
        )
        Text(
            text = title,
            modifier = GlanceModifier.defaultWeight(),
            style = TextStyle(
                color = if (event.done) palette.subtle else palette.text,
                fontSize = metrics.itemFontSp.sp,
                textDecoration = if (event.done) TextDecoration.LineThrough else null,
            ),
            maxLines = 1,
        )
    }
}
