package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
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
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxHeight
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.AppSettings
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.calendar.GRID_DAYS
import com.ysdoing.notioncal.core.data.calendar.gridStart
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.temporal.WeekFields
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * WG-09 '달력 + 일정' 위젯이 그릴 내용. 왼쪽 미니 월 달력(주차 번호), 오른쪽 오늘부터 7일 일정 목록.
 * 일정 목록은 보조 위젯(WG-06)과 같은 [AgendaUiState]를 쓴다.
 */
data class SplitUiState(
    val today: LocalDate,
    val weekStart: DayOfWeek,
    val agenda: AgendaUiState,
    /** EX-02 공휴일(달력 두 달 범위). */
    val holidays: Map<LocalDate, String> = emptyMap(),
) {
    val config: WidgetConfig get() = agenda.config
}

/**
 * WG-09 '달력 + 일정' 위젯(아이폰 노션 캘린더 위젯 참고). Room만 읽는다(WG-02).
 * 크기는 Exact: 실제 폭·높이로 글자 크기를 정하고, 높으면 이번 달과 다음 달을 함께 그린다.
 */
class SplitWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** WG-08 위젯 선택 화면 미리보기: 4×2 크기에 예시 일정(개인 일정 아님). */
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetPreviews.SPLIT_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.splitState()
        provideContent { SplitWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        // 첫 프레임부터 내용이 보이도록 초기값을 먼저 읽는다. 이후 변경은 아래 Flow가 반영한다.
        val initialToday = LocalDate.now(APP_ZONE)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initialSettings = deps.settings().settings.first()
        val initialEvents = deps.events().observeRange(initialToday, AgendaWidget.lastDay(initialToday)).first()
        val initialSync = WidgetSync.observe(deps.syncStatus()).first()
        val (hFrom, hTo) = holidayRange(initialToday, initialSettings.weekStart)
        val initialHolidays = deps.holidays().observe(hFrom, hTo).first()

        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val settings by remember { deps.settings().settings }.collectAsState(initialSettings)
            val fontScale by SystemFontScale.flow.collectAsState()
            val sync by remember { WidgetSync.observe(deps.syncStatus()) }.collectAsState(initialSync)
            // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4). 자정 알람이 위젯을 다시 그리게 한다.
            val today = LocalDate.now(APP_ZONE)
            val events by remember(today) { deps.events().observeRange(today, AgendaWidget.lastDay(today)) }
                .collectAsState(if (today == initialToday) initialEvents else emptyList())
            val holidays by remember(today, settings.weekStart) {
                val (from, to) = holidayRange(today, settings.weekStart)
                deps.holidays().observe(from, to)
            }.collectAsState(if (today == initialToday && settings.weekStart == initialSettings.weekStart) initialHolidays else emptyMap())
            SplitWidgetContent(uiState(today, settings, config, events, sync, holidays), fontScale ?: SystemFontScale.current(context))
        }
    }

    companion object {
        /** 달력에 그리는 두 달(이번 달·다음 달) 격자 전체. */
        fun holidayRange(today: LocalDate, weekStart: DayOfWeek): Pair<LocalDate, LocalDate> {
            val month = YearMonth.from(today)
            return gridStart(month, weekStart) to gridStart(month.plusMonths(1), weekStart).plusDays(GRID_DAYS - 1)
        }

        /** 위젯별 DB 필터·완료 숨김(WG-04)은 일정 목록에 건다. 달력은 날짜만 그린다. */
        fun uiState(
            today: LocalDate,
            settings: AppSettings,
            config: WidgetConfig,
            events: List<CalendarEvent>,
            sync: WidgetSync = WidgetSync(),
            holidays: Map<LocalDate, String> = emptyMap(),
        ) = SplitUiState(
            today = today,
            weekStart = settings.weekStart,
            agenda = AgendaWidget.uiState(today, config, events, sync, holidays),
            holidays = holidays,
        )

        /** 그 주 첫날 기준 주차. 주 시작 요일 설정을 따르고, 1월 1일이 든 주가 1주차다(2026년 10월 = 40~45주). */
        fun weekNumber(weekFirstDay: LocalDate, weekStart: DayOfWeek): Int =
            weekFirstDay.get(WeekFields.of(weekStart, 1).weekOfWeekBasedYear())
    }
}

/**
 * WG-09 치수. 달력 칸 높이에서 날짜 글자 크기를 정한다(월 위젯과 같은 글자 칸 규칙 [WidgetMetrics.TEXT_BOX],
 * 기기 글꼴 상한 [WidgetMetrics.MAX_SYSTEM_TEXT_SCALE]). 높이가 [TWO_MONTHS_MIN_HEIGHT] 이상이면 두 달을 그린다.
 */
internal data class SplitMetrics(
    val calendarWidth: Dp,
    val cellWidth: Dp,
    val weekNumberWidth: Dp,
    val months: Int,
    val titleHeight: Dp,
    val weekdayHeight: Dp,
    val rowHeight: Dp,
    val titleFontSp: Float,
    val weekdayFontSp: Float,
    val dateFontSp: Float,
    val weekNumberFontSp: Float,
    val dayHeaderHeight: Dp,
    val itemHeight: Dp,
    val dayHeaderFontSp: Float,
    val itemFontSp: Float,
    val timeFontSp: Float,
) {
    companion object {
        val PADDING = 12.dp
        val GAP = 10.dp
        val MONTH_GAP = 8.dp
        val TWO_MONTHS_MIN_HEIGHT = 300.dp

        fun of(size: DpSize, config: WidgetConfig, systemFontScale: Float): SplitMetrics {
            val scale = config.fontScale / 100f
            val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            // 위젯 글자는 sp라 기기 글꼴을 그대로 따른다. 상한을 넘는 만큼은 sp 값을 줄여 맞춘다.
            val spFactor = textScale / systemFontScale
            fun box(fontSp: Float) = (fontSp * textScale * WidgetMetrics.TEXT_BOX).dp
            fun fit(height: Dp, max: Float) = minOf(max * scale, (height.value - 1f) / (textScale * WidgetMetrics.TEXT_BOX)).coerceAtLeast(6f)

            val innerWidth = size.width - PADDING * 2
            val innerHeight = size.height - PADDING * 2
            val calendarWidth = innerWidth * 0.52f
            // 주차 칸은 날짜 칸의 0.8배.
            val cellWidth = calendarWidth / 7.8f
            val months = if (size.height >= TWO_MONTHS_MIN_HEIGHT) 2 else 1
            val monthHeight = (innerHeight - if (months == 2) MONTH_GAP else 0.dp) / months
            // 한 달 = 제목 1.6줄 + 요일 1줄 + 6주(각 1줄)을 같은 비율로 나눈다.
            val unit = monthHeight / 8.6f
            val titleHeight = unit * 1.6f
            val rowHeight = unit
            val titleFont = fit(titleHeight, 17f)
            val dateFont = fit(rowHeight, 13f)
            val dayHeader = 13f * scale
            val item = 13f * scale
            return SplitMetrics(
                calendarWidth = calendarWidth,
                cellWidth = cellWidth,
                weekNumberWidth = calendarWidth - cellWidth * 7,
                months = months,
                titleHeight = titleHeight,
                weekdayHeight = rowHeight,
                rowHeight = rowHeight,
                titleFontSp = titleFont * spFactor,
                weekdayFontSp = dateFont * 0.85f * spFactor,
                dateFontSp = dateFont * spFactor,
                weekNumberFontSp = dateFont * 0.8f * spFactor,
                dayHeaderHeight = box(dayHeader) + 6.dp,
                itemHeight = box(item) + 6.dp,
                dayHeaderFontSp = dayHeader * spFactor,
                itemFontSp = item * spFactor,
                timeFontSp = 11f * scale * spFactor,
            )
        }
    }
}

private val splitTimeFormat = DateTimeFormatter.ofPattern("H:mm")

/** 위젯 화면. 위젯·설정 화면 미리보기·위젯 선택 미리보기가 같은 코드를 쓴다. 비트맵 없이 그린다(PF-07). */
@Composable
fun SplitWidgetContent(
    state: SplitUiState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
) {
    val context = LocalContext.current
    val metrics = SplitMetrics.of(LocalSize.current, state.config, systemFontScale)
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val month = YearMonth.from(state.today)
    Row(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background)
            .padding(SplitMetrics.PADDING),
    ) {
        Column(GlanceModifier.width(metrics.calendarWidth).fillMaxHeight()) {
            MiniMonth(month, state, metrics, palette, showWeek = true)
            if (metrics.months == 2) {
                Spacer(GlanceModifier.height(SplitMetrics.MONTH_GAP))
                MiniMonth(month.plusMonths(1), state, metrics, palette, showWeek = false)
            }
        }
        Spacer(GlanceModifier.width(SplitMetrics.GAP))
        Box(GlanceModifier.defaultWeight().fillMaxHeight(), contentAlignment = Alignment.BottomEnd) {
            DayList(state, metrics, palette)
            AddButton(state.today, palette)
        }
    }
}

/** 미니 월 달력: 제목('10월 42주차'), 요일 줄, 6주(주차 + 7일). 한 줄 자식은 8개(Glance 10개 제한 안). */
@Composable
private fun MiniMonth(month: YearMonth, state: SplitUiState, metrics: SplitMetrics, palette: WidgetPalette, showWeek: Boolean) {
    val context = LocalContext.current
    val start = gridStart(month, state.weekStart)
    Column {
        Row(
            modifier = GlanceModifier
                .fillMaxWidth()
                .height(metrics.titleHeight)
                .clickable(actionStartActivity(appIntent(context)))
                .semantics { testTag = "splitMonthTitle" },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = context.getString(R.string.widget_month_short, month.monthValue),
                style = TextStyle(
                    color = if (showWeek) palette.accent else palette.text,
                    fontSize = metrics.titleFontSp.sp,
                    fontWeight = FontWeight.Bold,
                ),
                maxLines = 1,
            )
            if (showWeek) {
                val todayRow = state.today.minusDays(((state.today.dayOfWeek.value - state.weekStart.value + 7) % 7).toLong())
                Text(
                    text = context.getString(R.string.split_widget_week, SplitWidget.weekNumber(todayRow, state.weekStart)),
                    modifier = GlanceModifier.padding(start = 6.dp).semantics { testTag = "splitWeek" },
                    style = TextStyle(color = palette.subtle, fontSize = (metrics.titleFontSp * 0.65f).sp),
                    maxLines = 1,
                )
                SyncBadge(state.agenda.sync, state.today, metrics.titleFontSp * 0.65f, palette, showTime = false)
            }
        }
        Row(GlanceModifier.fillMaxWidth().height(metrics.weekdayHeight), verticalAlignment = Alignment.CenterVertically) {
            Spacer(GlanceModifier.width(metrics.weekNumberWidth))
            repeat(7) { i ->
                val day = state.weekStart.plus(i.toLong())
                Text(
                    text = day.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
                    modifier = GlanceModifier.width(metrics.cellWidth),
                    style = TextStyle(color = palette.subtle, fontSize = metrics.weekdayFontSp.sp, textAlign = TextAlign.Center),
                    maxLines = 1,
                )
            }
        }
        repeat(6) { week ->
            val first = start.plusWeeks(week.toLong())
            Row(GlanceModifier.fillMaxWidth().height(metrics.rowHeight), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = SplitWidget.weekNumber(first, state.weekStart).toString(),
                    modifier = GlanceModifier.width(metrics.weekNumberWidth).semantics { testTag = "splitWeekNumber" },
                    style = TextStyle(color = palette.subtle, fontSize = metrics.weekNumberFontSp.sp, textAlign = TextAlign.Start),
                    maxLines = 1,
                )
                repeat(7) { col ->
                    val date = first.plusDays(col.toLong())
                    DayCell(date, YearMonth.from(date) == month, state, metrics, palette)
                }
            }
        }
    }
}

@Composable
private fun DayCell(date: LocalDate, inMonth: Boolean, state: SplitUiState, metrics: SplitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val isToday = date == state.today
    val holiday = state.holidays[date]
    Box(
        modifier = GlanceModifier
            .width(metrics.cellWidth)
            .height(metrics.rowHeight)
            // WI-01: 그날 날짜 시트(목록·체크·추가)가 홈 화면 위에 뜬다.
            .clickable(actionStartActivity(quickSheetIntent(context, AppIntents.MODE_DAY, date)))
            .semantics {
                contentDescription = listOfNotNull("${date.monthValue}월 ${date.dayOfMonth}일", holiday).joinToString(", ")
                testTag = if (isToday) "splitToday" else "splitDay"
            },
        contentAlignment = Alignment.Center,
    ) {
        val text = date.dayOfMonth.toString()
        if (isToday) {
            Box(
                modifier = GlanceModifier.fillMaxSize().padding(horizontal = 1.dp).background(palette.todayBackground).cornerRadius(6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(text, style = TextStyle(color = palette.todayText, fontSize = metrics.dateFontSp.sp, fontWeight = FontWeight.Bold), maxLines = 1)
            }
        } else {
            val color = when {
                !inMonth -> palette.outside
                holiday != null -> palette.holiday
                else -> palette.dayColor(date.dayOfWeek)
            }
            Text(text, style = TextStyle(color = color, fontSize = metrics.dateFontSp.sp, textAlign = TextAlign.Center), maxLines = 1)
        }
    }
}

/** 오른쪽 일정 목록: '목 15일' 머리 아래 일정(색 점·제목). 넘치면 스크롤한다. */
@Composable
private fun DayList(state: SplitUiState, metrics: SplitMetrics, palette: WidgetPalette) {
    val days = state.agenda.days
    LazyColumn(GlanceModifier.fillMaxSize()) {
        days.forEach { day ->
            item(itemId = day.date.toEpochDay()) { DayHeader(day.date, state.holidays[day.date], metrics, palette) }
            if (day.items.isEmpty() && day.date !in state.holidays) {
                item(itemId = -day.date.toEpochDay() - 1) { EmptyItem(metrics, palette) }
            }
            items(day.items, itemId = { (event, _) -> (event.pageId + day.date).hashCode().toLong() }) { (event, _) ->
                EventItem(event, day.date, metrics, palette)
            }
        }
        // 마지막 일정이 '+' 버튼에 가리지 않게 아래 여백을 둔다.
        item(itemId = Long.MIN_VALUE) { Spacer(GlanceModifier.height(ADD_BUTTON_SIZE)) }
    }
}

@Composable
private fun DayHeader(date: LocalDate, holiday: String?, metrics: SplitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Row(
        modifier = GlanceModifier
            .fillMaxWidth()
            .height(metrics.dayHeaderHeight)
            .clickable(actionStartActivity(quickSheetIntent(context, AppIntents.MODE_DAY, date)))
            .semantics { testTag = "splitDayHeader" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
            style = TextStyle(color = palette.text, fontSize = metrics.dayHeaderFontSp.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        Text(
            text = context.getString(R.string.split_widget_day, date.dayOfMonth),
            modifier = GlanceModifier.padding(start = 4.dp),
            style = TextStyle(color = if (holiday != null) palette.holiday else palette.accent, fontSize = metrics.dayHeaderFontSp.sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (holiday != null) {
            Text(
                text = holiday,
                modifier = GlanceModifier.padding(start = 4.dp),
                style = TextStyle(color = palette.holiday, fontSize = metrics.timeFontSp.sp),
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun EmptyItem(metrics: SplitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Text(
        text = context.getString(R.string.agenda_widget_empty),
        modifier = GlanceModifier.fillMaxWidth().height(metrics.itemHeight).semantics { testTag = "splitEmpty" },
        style = TextStyle(color = palette.subtle, fontSize = metrics.itemFontSp.sp),
        maxLines = 1,
    )
}

@Composable
private fun EventItem(event: CalendarEvent, date: LocalDate, metrics: SplitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val title = event.title.ifBlank { context.getString(R.string.agenda_widget_untitled) }
    val time = event.startTime?.takeIf { !event.isMultiDay }?.atZone(APP_ZONE)?.format(splitTimeFormat)
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
                contentDescription = listOfNotNull(title, time, context.getString(R.string.agenda_widget_done).takeIf { event.done }).joinToString(", ")
                testTag = "splitItem"
            },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // A11Y-03: 완료는 색 점 대신 체크 + 취소선(색만으로 구분하지 않음).
        Text(
            text = if (event.done) "✓" else "●",
            modifier = GlanceModifier.width(16.dp),
            style = TextStyle(color = ColorProvider(Color(NotionColors.argb(event.colorHex))), fontSize = metrics.timeFontSp.sp),
            maxLines = 1,
        )
        if (time != null) {
            Text(
                text = time,
                modifier = GlanceModifier.padding(end = 4.dp),
                style = TextStyle(color = palette.subtle, fontSize = metrics.timeFontSp.sp),
                maxLines = 1,
            )
        }
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

/** 터치 영역 48dp(A11Y-02) 안의 둥근 '+'. */
private val ADD_BUTTON_SIZE = 48.dp

/** WI-02 퀵 추가: 오늘 날짜로 입력칸이 열린다. 일정 목록 오른쪽 아래에 겹친다. */
@Composable
private fun AddButton(today: LocalDate, palette: WidgetPalette) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .size(ADD_BUTTON_SIZE)
            .clickable(actionStartActivity(quickSheetIntent(context, AppIntents.MODE_ADD, today)))
            .semantics {
                contentDescription = context.getString(R.string.widget_add)
                testTag = "add"
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = GlanceModifier.size(40.dp).cornerRadius(20.dp).background(palette.accent),
            contentAlignment = Alignment.Center,
        ) {
            Text("+", style = TextStyle(color = palette.todayText, fontSize = 22.sp, fontWeight = FontWeight.Medium))
        }
    }
}
