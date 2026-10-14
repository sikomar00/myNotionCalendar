package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
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
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.SyncStatusRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import java.time.Instant
import java.time.format.DateTimeFormatter
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.calendar.WeekLayout
import com.ysdoing.notioncal.core.data.calendar.chipLabel
import com.ysdoing.notioncal.core.data.calendar.gridStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale
import androidx.glance.color.ColorProvider as DayNightColor

/** 위젯 한 개가 그릴 내용. Room에서 읽은 값만 담는다(WG-02). */
@Immutable
data class WidgetUiState(
    val month: YearMonth,
    val today: LocalDate,
    val weekStart: DayOfWeek,
    /** DB 필터·완료 숨김을 적용한 뒤의 일정. */
    val events: List<CalendarEvent>,
    val config: WidgetConfig,
    /** WG-07 마지막 동기화 시각·오류. */
    val sync: WidgetSync = WidgetSync(),
)

/**
 * WG-07 위젯 머리줄의 동기화 상태. [lastSuccessAt]은 마지막 성공 시각(ms), [failed]는 마지막 동기화가 실패했는지.
 * 실패해도 일정은 캐시 그대로 그린다(PF-05). 자세한 안내는 앱 배너가 맡는다.
 */
data class WidgetSync(val lastSuccessAt: Long? = null, val failed: Boolean = false) {
    companion object {
        fun observe(repo: SyncStatusRepository): Flow<WidgetSync> =
            combine(repo.latest, repo.latestSuccess) { latest, success ->
                WidgetSync(lastSuccessAt = success?.at, failed = latest != null && latest.result != "OK")
            }.distinctUntilChanged()
    }
}

/** 위젯 화면. 위젯과 설정 화면 미리보기가 같은 코드를 쓴다. 비트맵 없이 레이아웃·배경색만 쓴다(PF-07). */
@Composable
fun WidgetContent(
    state: WidgetUiState,
    /** 기기 글꼴 크기. 위젯에서는 [SystemFontScale]이 알려 준 값을 넘긴다(세션 컨텍스트 값은 오래된 값일 수 있음). */
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
) {
    val context = LocalContext.current
    val metrics = WidgetMetrics.of(LocalSize.current, state.config, systemFontScale)
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val start = gridStart(state.month, state.weekStart)

    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .appWidgetBackground()
            .cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background)
            .padding(WidgetMetrics.PADDING),
    ) {
        Header(state, metrics, palette)
        WeekdayRow(state.weekStart, metrics, palette)
        repeat(6) { week ->
            WeekRow(
                weekStart = start.plusWeeks(week.toLong()),
                state = state,
                metrics = metrics,
                palette = palette,
                context = context,
            )
        }
    }
}

@Composable
private fun Header(state: WidgetUiState, metrics: WidgetMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val small = metrics.mode == WidgetMode.SMALL
    Row(
        modifier = GlanceModifier.fillMaxWidth().height(metrics.headerHeight),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (small) {
            // 작음: 폭이 좁아 "오늘" 버튼 대신 달 제목을 누르면 오늘로 돌아간다.
            Box(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(metrics.headerHeight)
                    .padding(start = 2.dp)
                    .clickable(navAction(0))
                    .semantics {
                        contentDescription = context.getString(R.string.widget_today)
                        testTag = "today"
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = context.getString(R.string.widget_month_short, state.month.monthValue),
                        style = TextStyle(color = palette.text, fontSize = metrics.headerFontSp.sp, fontWeight = FontWeight.Bold),
                        maxLines = 1,
                    )
                    SyncBadge(state, metrics, palette, showTime = false)
                }
            }
        } else {
            // 제목과 동기화 상태를 누르면 앱이 열린다(오류 안내는 앱 배너가 맡는다).
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(metrics.headerHeight)
                    .padding(start = 4.dp)
                    .clickable(actionStartActivity(appIntent(context))),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.getString(R.string.widget_month_title, state.month.year, state.month.monthValue),
                    style = TextStyle(color = palette.text, fontSize = metrics.headerFontSp.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                SyncBadge(state, metrics, palette, showTime = metrics.cellWidth * 7 >= SYNC_TIME_MIN_WIDTH)
            }
        }
        // WI-02 퀵 추가: 오늘 날짜로 입력칸이 열린다.
        HeaderButton("+", context.getString(R.string.widget_add), "add", actionStartActivity(quickSheetIntent(context, AppIntents.MODE_ADD, state.today)), metrics, palette)
        // NS-06 위젯 새로고침: 동기화를 요청만 한다(위젯 코드는 네트워크를 부르지 않음). 작음은 폭이 좁아 뺀다.
        if (!small) HeaderButton("↻", context.getString(R.string.widget_refresh), "refresh", actionRunCallback<RefreshAction>(), metrics, palette)
        HeaderButton("‹", context.getString(R.string.widget_prev), "prev", navAction(-1), metrics, palette)
        if (!small) {
            HeaderButton(context.getString(R.string.widget_today), context.getString(R.string.widget_today), "today", navAction(0), metrics, palette)
        }
        HeaderButton("›", context.getString(R.string.widget_next), "next", navAction(+1), metrics, palette)
    }
}

/** 동기화 시각을 보일 최소 내용 폭. 이보다 좁으면 버튼이 밀리므로 오류 점만 보인다. */
private val SYNC_TIME_MIN_WIDTH = 290.dp
private val syncTodayFormat = DateTimeFormatter.ofPattern("H:mm")
private val syncDateFormat = DateTimeFormatter.ofPattern("M/d")

/** WG-07: 마지막 동기화 시각(오늘이면 시:분, 아니면 월/일)과 실패 점. */
@Composable
private fun SyncBadge(state: WidgetUiState, metrics: WidgetMetrics, palette: WidgetPalette, showTime: Boolean) {
    val context = LocalContext.current
    val at = state.sync.lastSuccessAt?.let { Instant.ofEpochMilli(it).atZone(APP_ZONE) }
    if (showTime && at != null) {
        val text = if (at.toLocalDate() == state.today) at.format(syncTodayFormat) else at.format(syncDateFormat)
        Text(
            text = text,
            modifier = GlanceModifier.padding(start = 6.dp).semantics {
                contentDescription = context.getString(R.string.widget_last_sync, text)
                testTag = "syncTime"
            },
            style = TextStyle(color = palette.subtle, fontSize = metrics.chipFontSp.sp),
            maxLines = 1,
        )
    }
    if (state.sync.failed) {
        Text(
            text = "●",
            modifier = GlanceModifier.padding(start = 4.dp).semantics {
                contentDescription = context.getString(R.string.widget_sync_error)
                testTag = "syncError"
            },
            style = TextStyle(color = palette.error, fontSize = metrics.chipFontSp.sp),
            maxLines = 1,
        )
    }
}

/** 앱 첫 화면 열기(런처 실행과 같다). */
private fun appIntent(context: Context): Intent =
    (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private fun navAction(delta: Int) = actionRunCallback<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to delta))

/** 머리줄 버튼(WI-04 월 이동, WI-02 추가, NS-06 새로고침). 큰 크기가 아니면 여백을 줄여 한 줄에 들어가게 한다. */
@Composable
private fun HeaderButton(label: String, description: String, tag: String, action: Action, metrics: WidgetMetrics, palette: WidgetPalette) {
    Box(
        modifier = GlanceModifier
            .height(metrics.headerHeight)
            .padding(horizontal = if (metrics.mode == WidgetMode.LARGE) 8.dp else 5.dp)
            .clickable(action)
            .semantics {
                contentDescription = description
                testTag = tag
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(color = palette.accent, fontSize = metrics.headerFontSp.sp, fontWeight = FontWeight.Medium))
    }
}

@Composable
private fun WeekdayRow(weekStart: DayOfWeek, metrics: WidgetMetrics, palette: WidgetPalette) {
    Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.weekdayHeight)) {
        repeat(7) { i ->
            val day = weekStart.plus(i.toLong())
            Text(
                text = day.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN),
                modifier = GlanceModifier.width(metrics.cellWidth),
                style = TextStyle(
                    color = palette.dayColor(day),
                    fontSize = metrics.weekdayFontSp.sp,
                    textAlign = TextAlign.Center,
                ),
            )
        }
    }
}

@Composable
private fun WeekRow(
    weekStart: LocalDate,
    state: WidgetUiState,
    metrics: WidgetMetrics,
    palette: WidgetPalette,
    context: Context,
) {
    Box(modifier = GlanceModifier.fillMaxWidth().height(metrics.weekHeight)) {
        Column(modifier = GlanceModifier.fillMaxSize()) {
            Spacer(GlanceModifier.fillMaxWidth().height(1.dp).background(palette.divider))
            Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.dateLabelHeight)) {
                repeat(7) { col ->
                    val date = weekStart.plusDays(col.toLong())
                    DateLabel(date, YearMonth.from(date) == state.month, date == state.today, metrics, palette)
                }
            }
            if (metrics.mode == WidgetMode.SMALL) {
                if (metrics.showDots) DotRow(weekStart, state.events, metrics)
            } else if (metrics.lanes == 1) {
                SingleLineRow(weekStart, state.events, metrics, palette)
            } else {
                val layout = WeekLayout.layout(weekStart, state.events, metrics.lanes)
                repeat(metrics.lanes) { lane ->
                    Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.laneHeight)) {
                        laneSegments(layout, lane).forEach { segment -> Segment(segment, metrics, palette) }
                    }
                }
            }
        }
        // 칸 어디를 눌러도 그 날짜로 앱을 연다. 퀵 시트(WI-01)는 Phase 3에서 이 자리를 쓴다.
        Row(modifier = GlanceModifier.fillMaxSize()) {
            repeat(7) { col ->
                val date = weekStart.plusDays(col.toLong())
                val count = state.events.count { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }
                // Spacer에는 클릭이 붙지 않아(실기기 확인) 빈 Box로 터치 영역을 만든다.
                Box(
                    GlanceModifier
                        .width(metrics.cellWidth)
                        .fillMaxHeight()
                        // WI-01: 그날 퀵 시트(목록·체크·추가)가 홈 화면 위에 뜬다.
                        .clickable(actionStartActivity(quickSheetIntent(context, AppIntents.MODE_DAY, date)))
                        .semantics {
                            contentDescription = "${date.monthValue}월 ${date.dayOfMonth}일, 일정 ${count}개"
                            testTag = "day"
                        },
                ) {}
            }
        }
    }
}

@Composable
private fun DateLabel(date: LocalDate, inMonth: Boolean, isToday: Boolean, metrics: WidgetMetrics, palette: WidgetPalette) {
    Box(
        modifier = GlanceModifier.width(metrics.cellWidth).height(metrics.dateLabelHeight),
        contentAlignment = Alignment.TopCenter,
    ) {
        val text = date.dayOfMonth.toString()
        val color = if (inMonth) palette.dayColor(date.dayOfWeek) else palette.outside
        if (isToday) {
            Box(
                modifier = GlanceModifier
                    .padding(horizontal = if (metrics.mode == WidgetMode.SMALL) 0.dp else 4.dp)
                    .background(palette.todayBackground)
                    .cornerRadius(8.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text,
                    style = TextStyle(color = palette.todayText, fontSize = metrics.dateFontSp.sp, fontWeight = FontWeight.Bold),
                )
            }
        } else {
            Text(text, style = TextStyle(color = color, fontSize = metrics.dateFontSp.sp))
        }
    }
}

/** 작음 단계: 일정 색 점만(칸당 최대 3개). */
@Composable
private fun DotRow(weekStart: LocalDate, events: List<CalendarEvent>, metrics: WidgetMetrics) {
    Row(modifier = GlanceModifier.fillMaxWidth()) {
        repeat(7) { col ->
            val date = weekStart.plusDays(col.toLong())
            val colors = events.filter { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }.take(3)
            Row(
                modifier = GlanceModifier.width(metrics.cellWidth),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                colors.forEach { event ->
                    Box(
                        GlanceModifier
                            .padding(horizontal = 1.dp)
                            .size(5.dp)
                            .cornerRadius(2.5.dp)
                            .background(ColorProvider(Color(NotionColors.argb(event.colorHex))))
                            .semantics { testTag = "dot" },
                    ) {}
                }
            }
        }
    }
}

/**
 * 한 줄만 들어갈 때(중간 크기·칸당 1줄): 날짜마다 첫 일정 제목과 나머지 개수(+N)를 함께 보여준다.
 * 제목은 남는 폭을 쓰고 +N은 항상 보인다. 여러 날 일정은 걸친 날마다 표시된다.
 */
@Composable
private fun SingleLineRow(weekStart: LocalDate, events: List<CalendarEvent>, metrics: WidgetMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Row(modifier = GlanceModifier.fillMaxWidth().height(metrics.laneHeight)) {
        repeat(7) { col ->
            val date = weekStart.plusDays(col.toLong())
            val dayEvents = events.filter { !it.startDate.isAfter(date) && !it.lastDate.isBefore(date) }
            val first = dayEvents.firstOrNull()
            Row(
                modifier = GlanceModifier.width(metrics.cellWidth).height(metrics.laneHeight).padding(start = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (first != null) {
                    Box(GlanceModifier.size(4.dp).cornerRadius(2.dp).background(ColorProvider(Color(NotionColors.argb(first.colorHex))))) {}
                    Text(
                        text = chipLabel(first, continuesBefore = first.startDate.isBefore(date)),
                        modifier = GlanceModifier.defaultWeight().padding(start = 2.dp).semantics { testTag = "chip" },
                        style = chipStyle(first, metrics, palette),
                        maxLines = 1,
                    )
                    if (dayEvents.size > 1) {
                        Text(
                            text = context.getString(R.string.widget_more, dayEvents.size - 1),
                            modifier = GlanceModifier.semantics { testTag = "more" },
                            style = TextStyle(color = palette.subtle, fontSize = (metrics.chipFontSp * 0.9f).sp),
                            maxLines = 1,
                        )
                    }
                }
            }
        }
    }
}

private fun chipStyle(event: CalendarEvent, metrics: WidgetMetrics, palette: WidgetPalette) = TextStyle(
    color = if (event.done) palette.subtle else palette.text,
    fontSize = metrics.chipFontSp.sp,
    textDecoration = if (event.done) TextDecoration.LineThrough else null,
)

@Composable
private fun Segment(segment: LaneSegment, metrics: WidgetMetrics, palette: WidgetPalette) {
    val width = metrics.cellWidth * segment.span
    val placed = segment.placed
    when {
        placed != null -> {
            val event = placed.event
            val color = Color(NotionColors.argb(event.colorHex))
            val style = chipStyle(event, metrics, palette)
            val label = chipLabel(event, placed.continuesBefore)
            if (event.isMultiDay) {
                Box(
                    modifier = GlanceModifier.width(width).height(metrics.laneHeight).padding(horizontal = 1.dp, vertical = 0.5.dp),
                ) {
                    Box(
                        modifier = GlanceModifier
                            .fillMaxSize()
                            .background(ColorProvider(color.copy(alpha = 0.28f)))
                            .cornerRadius(3.dp)
                            .padding(horizontal = 3.dp)
                            .semantics { testTag = "chip" },
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        Text(label, style = style, maxLines = 1)
                    }
                }
            } else {
                Row(
                    modifier = GlanceModifier.width(width).height(metrics.laneHeight).padding(start = 2.dp).semantics { testTag = "chip" },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(GlanceModifier.size(4.dp).cornerRadius(2.dp).background(ColorProvider(color))) {}
                    Text(label, modifier = GlanceModifier.padding(start = 2.dp), style = style, maxLines = 1)
                }
            }
        }
        segment.hiddenCount > 0 -> Text(
            text = LocalContext.current.getString(R.string.widget_more, segment.hiddenCount),
            modifier = GlanceModifier.width(width).padding(start = 3.dp).semantics { testTag = "more" },
            style = TextStyle(color = palette.subtle, fontSize = metrics.chipFontSp.sp),
            maxLines = 1,
        )
        else -> Spacer(GlanceModifier.width(width))
    }
}

/**
 * 퀵 시트 인텐트. :widget은 :feature:quicksheet에 의존할 수 없어 action 문자열로 연다.
 * 모드·날짜마다 다른 data URI를 붙여 PendingIntent가 서로 덮어쓰지 않게 한다.
 */
internal fun quickSheetIntent(context: Context, mode: String, date: LocalDate): Intent =
    Intent(AppIntents.ACTION_QUICK_SHEET)
        .setPackage(context.packageName)
        .setData("notioncal://$mode/$date".toUri())
        .putExtra(AppIntents.EXTRA_MODE, mode)
        .putExtra(AppIntents.EXTRA_DATE, date.toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** 위젯 색. SYSTEM·MATERIAL_YOU는 기기 다크 모드를 따르고, 배경에만 불투명도를 적용한다(WG-04·WS-04). */
class WidgetPalette private constructor(
    val background: ColorProvider,
    val text: ColorProvider,
    val subtle: ColorProvider,
    val outside: ColorProvider,
    val divider: ColorProvider,
    val accent: ColorProvider,
    val todayBackground: ColorProvider,
    val todayText: ColorProvider,
    /** WG-07 동기화 오류 점. */
    val error: ColorProvider,
    private val sunday: ColorProvider,
    private val saturday: ColorProvider,
) {
    fun dayColor(day: DayOfWeek): ColorProvider = when (day) {
        DayOfWeek.SUNDAY -> sunday
        DayOfWeek.SATURDAY -> saturday
        else -> text
    }

    private class Scheme(
        val background: Color,
        val text: Color,
        val subtle: Color,
        val outside: Color,
        val divider: Color,
        val accent: Color,
    )

    companion object {
        private val LIGHT = Scheme(
            Color(0xFFFFFFFF), Color(0xFF37352F), Color(0xFF787774), Color(0xFFB4B4B0), Color(0xFFE9E9E7), Color(0xFF2F80ED),
        )
        private val DARK = Scheme(
            Color(0xFF191919), Color(0xFFE6E6E4), Color(0xFF9B9A97), Color(0xFF5A5A58), Color(0xFF2F2F2F), Color(0xFF529CCA),
        )
        private val SUNDAY = Color(0xFFE03E3E)
        private val SATURDAY = Color(0xFF2F80ED)

        /**
         * Material You 색은 그릴 때마다 시스템 색 자원(android.R.color.system_*)에서 읽는다. 배경화면이 바뀌면
         * 시스템이 위젯 호스트를 다시 그리게 하고, 앱도 설정 변경 때 위젯 갱신을 요청한다(NotionCalApp).
         */
        private fun materialYou(context: Context, dark: Boolean): Scheme {
            fun c(id: Int) = Color(context.getColor(id))
            return if (dark) {
                Scheme(
                    c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral1_50), c(android.R.color.system_neutral2_200),
                    c(android.R.color.system_neutral2_500), c(android.R.color.system_neutral2_800), c(android.R.color.system_accent1_200),
                )
            } else {
                Scheme(
                    c(android.R.color.system_neutral1_50), c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_700),
                    c(android.R.color.system_neutral2_400), c(android.R.color.system_neutral2_100), c(android.R.color.system_accent1_600),
                )
            }
        }

        fun of(theme: WidgetTheme, opacity: Int, context: Context): WidgetPalette {
            val alpha = opacity.coerceIn(0, 100) / 100f
            val (lightScheme, darkScheme) = when (theme) {
                WidgetTheme.MATERIAL_YOU -> materialYou(context, dark = false) to materialYou(context, dark = true)
                else -> LIGHT to DARK
            }
            fun pick(select: (Scheme) -> Color, bg: Boolean = false): ColorProvider {
                val light = select(lightScheme).let { if (bg) it.copy(alpha = alpha) else it }
                val dark = select(darkScheme).let { if (bg) it.copy(alpha = alpha) else it }
                return when (theme) {
                    WidgetTheme.LIGHT -> ColorProvider(light)
                    WidgetTheme.DARK -> ColorProvider(dark)
                    WidgetTheme.SYSTEM, WidgetTheme.MATERIAL_YOU -> DayNightColor(day = light, night = dark)
                }
            }
            return WidgetPalette(
                background = pick({ it.background }, bg = true),
                text = pick({ it.text }),
                subtle = pick({ it.subtle }),
                outside = pick({ it.outside }),
                divider = pick({ it.divider }),
                accent = pick({ it.accent }),
                todayBackground = pick({ it.accent }),
                todayText = if (theme == WidgetTheme.MATERIAL_YOU) pick({ it.background }) else ColorProvider(Color.White),
                error = ColorProvider(SUNDAY),
                sunday = ColorProvider(SUNDAY),
                saturday = ColorProvider(SATURDAY),
            )
        }
    }
}
