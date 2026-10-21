package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.glance.ImageProvider
import androidx.glance.Image
import androidx.glance.ColorFilter
import androidx.compose.ui.unit.Dp
import androidx.annotation.DrawableRes
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
import com.ysdoing.notioncal.core.data.DesignTokens
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTheme
import com.ysdoing.notioncal.core.data.calendar.gridStart
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
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
    /** EX-02 공휴일(날짜 → 이름). */
    val holidays: Map<LocalDate, String> = emptyMap(),
    /** WX-04 머리의 오늘 날씨(위젯 설정에서 켠 위젯만). */
    val weather: WidgetWeather = WidgetWeather(),
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
    /** false면 바깥 배경·모서리를 그리지 않는다(통합 위젯 WG-11 안에 넣을 때 배경이 두 번 겹치지 않게). */
    framed: Boolean = true,
) {
    val context = LocalContext.current
    val metrics = WidgetMetrics.of(LocalSize.current, state.config, systemFontScale)
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val start = gridStart(state.month, state.weekStart)
    val outer = if (framed) {
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius).background(palette.background)
    } else {
        GlanceModifier.fillMaxSize()
    }

    Column(modifier = outer.padding(WidgetMetrics.PADDING)) {
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
                    SyncBadge(state.sync, state.today, metrics.chipFontSp, palette, showTime = false)
                    todayWeather(state)?.let { WeatherChip(it, state.weather.stale, palette, metrics.chipFontSp, showTemps = false) }
                }
            }
        } else {
            // 제목을 누르면: 다른 달을 보고 있으면 오늘로, 이번 달이면 앱 열기(오류 안내는 앱 배너가 맡는다).
            // A11Y-02: 머리 버튼을 48dp로 넓히느라 따로 있던 "오늘" 버튼은 제목이 맡는다.
            val atToday = state.config.monthOffset == 0
            Row(
                modifier = GlanceModifier
                    .defaultWeight()
                    .height(metrics.headerHeight)
                    .padding(start = 4.dp)
                    .clickable(if (atToday) actionStartActivity(appIntent(context)) else navAction(0))
                    .semantics {
                        contentDescription = context.getString(if (atToday) R.string.widget_open_app else R.string.widget_today)
                        testTag = "today"
                    },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.getString(R.string.widget_month_title, state.month.year, state.month.monthValue),
                    style = TextStyle(color = palette.text, fontSize = metrics.headerFontSp.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                SyncBadge(state.sync, state.today, metrics.chipFontSp, palette, showTime = metrics.cellWidth * 7 >= SYNC_TIME_MIN_WIDTH * metrics.textScale)
                todayWeather(state)?.let { WeatherChip(it, state.weather.stale, palette, metrics.chipFontSp, showTemps = metrics.mode == WidgetMode.LARGE) }
            }
        }
        // WI-02 퀵 추가: 오늘 날짜로 입력칸이 열린다.
        HeaderButton(R.drawable.ic_widget_add, context.getString(R.string.widget_add), "add", actionStartActivity(quickSheetIntent(context, AppIntents.MODE_ADD, state.today)), metrics, palette)
        // NS-06 위젯 새로고침: 동기화를 요청만 한다(위젯 코드는 네트워크를 부르지 않음). 48dp 버튼 4개가 들어가는 큼에서만 보인다.
        if (metrics.mode == WidgetMode.LARGE) {
            HeaderButton(R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "refresh", actionRunCallback<RefreshAction>(), metrics, palette)
        }
        HeaderButton(R.drawable.ic_widget_prev, context.getString(R.string.widget_prev), "prev", navAction(-1), metrics, palette)
        HeaderButton(R.drawable.ic_widget_next, context.getString(R.string.widget_next), "next", navAction(+1), metrics, palette)
    }
}

/** WX-04: 위젯 설정에서 날씨를 켰고 오늘 예보가 있으면. */
private fun todayWeather(state: WidgetUiState) = state.weather.days[state.today]?.takeIf { state.config.showWeather }

/** 동기화 시각을 보일 최소 내용 폭(글자 배율 1 기준, 글자가 커지면 비례해 늘림). 모자라면 시각 없이 오류 점만 보인다. */
private val SYNC_TIME_MIN_WIDTH = 340.dp
private val syncTodayFormat = DateTimeFormatter.ofPattern("H:mm")
private val syncDateFormat = DateTimeFormatter.ofPattern("M/d")

/** WG-07: 마지막 동기화 시각(오늘이면 시:분, 아니면 월/일)과 실패 점. */
@Composable
internal fun SyncBadge(sync: WidgetSync, today: LocalDate, fontSp: Float, palette: WidgetPalette, showTime: Boolean) {
    val context = LocalContext.current
    val at = sync.lastSuccessAt?.let { Instant.ofEpochMilli(it).atZone(APP_ZONE) }
    if (showTime && at != null) {
        val text = if (at.toLocalDate() == today) at.format(syncTodayFormat) else at.format(syncDateFormat)
        Text(
            text = text,
            modifier = GlanceModifier.padding(start = 6.dp).semantics {
                contentDescription = context.getString(R.string.widget_last_sync, text)
                testTag = "syncTime"
            },
            style = TextStyle(color = palette.subtle, fontSize = fontSp.sp),
            maxLines = 1,
        )
    }
    if (sync.failed) {
        Text(
            text = "●",
            modifier = GlanceModifier.padding(start = 4.dp).semantics {
                contentDescription = context.getString(R.string.widget_sync_error)
                testTag = "syncError"
            },
            style = TextStyle(color = palette.error, fontSize = fontSp.sp),
            maxLines = 1,
        )
    }
}

/** 앱 첫 화면 열기(런처 실행과 같다). */
internal fun appIntent(context: Context): Intent =
    (context.packageManager.getLaunchIntentForPackage(context.packageName) ?: Intent())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

private val HEADER_BUTTON_WIDTH = 48.dp

private fun navAction(delta: Int) = actionRunCallback<MonthNavAction>(actionParametersOf(MonthNavAction.DeltaKey to delta))

/**
 * 머리줄 버튼(WI-04 월 이동, WI-02 추가, NS-06 새로고침). A11Y-02: 중간·큼은 폭 48dp(큼은 머리줄도 48dp라 48×48).
 * 작음(2×2)은 폭·높이가 모자라 48dp를 줄 수 없어 글자 폭 그대로 둔다(예외).
 */
@Composable
private fun HeaderButton(@DrawableRes icon: Int, description: String, tag: String, action: Action, metrics: WidgetMetrics, palette: WidgetPalette) {
    Box(
        modifier = GlanceModifier
            .height(metrics.headerHeight)
            .then(if (metrics.mode == WidgetMode.SMALL) GlanceModifier.padding(horizontal = 5.dp) else GlanceModifier.width(HEADER_BUTTON_WIDTH))
            .clickable(action)
            .semantics {
                contentDescription = description
                testTag = tag
            },
        contentAlignment = Alignment.Center,
    ) {
        WidgetIcon(icon, palette.accent, (metrics.headerHeight * 0.55f).coerceAtMost(24.dp))
    }
}

/**
 * 머리줄 아이콘(앱과 같은 Material 아이콘 모양). 벡터 리소스라 런처가 리소스로 그리고 비트맵을 넘기지 않는다(PF-07).
 * 설명은 감싼 버튼이 갖는다.
 */
@Composable
internal fun WidgetIcon(@DrawableRes icon: Int, tint: ColorProvider, size: Dp) {
    Image(
        provider = ImageProvider(icon),
        contentDescription = null,
        modifier = GlanceModifier.size(size),
        colorFilter = ColorFilter.tint(tint),
    )
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
    /** HB-03 습관 칸의 빈 네모(안 한 날), LD-04 예산 막대 바탕. */
    val track: ColorProvider,
    /** LD-03 수입 초록(노션 초록, 라이트 5.4:1). */
    val income: ColorProvider,
    /** 가계부 지출 금액(파랑, '-' 표기와 함께). */
    val expense: ColorProvider,
    /** HB-03 잔디 농도(낮음·중간·모두). */
    private val grass: List<ColorProvider>,
) {
    /** HB-03 잔디: 그날 한 비율 [ratio](0~1)를 4단계(없음·절반 미만·모두 미만·모두)로. */
    fun grassLevel(ratio: Float): ColorProvider = when {
        ratio <= 0f -> track
        ratio < 0.5f -> grass[0]
        ratio < 1f -> grass[1]
        else -> grass[2]
    }

    /** EX-02 공휴일 색(일요일과 같다). */
    val holiday: ColorProvider get() = sunday

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
        val track: Color,
    )

    companion object {
        // 앱과 같은 화면 틀 색(DesignTokens). 다른 달 날짜(outside)만 위젯 전용 값(3:1 이상).
        private val LIGHT = with(DesignTokens.Light) {
            Scheme(Color(PAPER), Color(INK), Color(GRAPHITE), Color(0xFF808080), Color(RULE), Color(ACCENT), Color(SURFACE_HIGHEST))
        }
        private val DARK = with(DesignTokens.Dark) {
            Scheme(Color(PAPER), Color(INK), Color(GRAPHITE), Color(0xFF808083), Color(RULE), Color(ACCENT), Color(0xFF3A3A3A))
        }
        // 일요일·공휴일·토요일 글자색. 작은 글자도 4.5:1 이상(흰 배경 위 5.6·5.8, #191919 위 6.3·6.3).
        // 다른 달 날짜(outside)는 3:1 이상(흰 배경 3.9, #191919 4.5). 앱(CalendarColors.kt)과 같은 값이다.
        private val SUNDAY_LIGHT = Color(0xFFC62828)
        private val SUNDAY_DARK = Color(0xFFFF6B6B)
        private val SATURDAY_LIGHT = Color(0xFF1565C0)
        private val SATURDAY_DARK = Color(0xFF5B9BF8)

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
                    c(android.R.color.system_neutral2_700),
                )
            } else {
                Scheme(
                    c(android.R.color.system_neutral1_50), c(android.R.color.system_neutral1_900), c(android.R.color.system_neutral2_700),
                    c(android.R.color.system_neutral2_400), c(android.R.color.system_neutral2_100), c(android.R.color.system_accent1_600),
                    c(android.R.color.system_neutral2_200),
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
            /** 테마와 상관없이 정해진 밝은·어두운 값 쌍(요일색). */
            fun pair(light: Color, dark: Color): ColorProvider = when (theme) {
                WidgetTheme.LIGHT -> ColorProvider(light)
                WidgetTheme.DARK -> ColorProvider(dark)
                WidgetTheme.SYSTEM, WidgetTheme.MATERIAL_YOU -> DayNightColor(day = light, night = dark)
            }
            return WidgetPalette(
                background = pick({ it.background }, bg = true),
                text = pick({ it.text }),
                subtle = pick({ it.subtle }),
                outside = pick({ it.outside }),
                divider = pick({ it.divider }),
                accent = pick({ it.accent }),
                todayBackground = pick({ it.accent }),
                // 오늘 칸 글자: 라이트는 흰색, 다크는 밝은 강조 위라 밤색(흰색은 2.7:1).
                todayText = if (theme == WidgetTheme.MATERIAL_YOU) {
                    pick({ it.background })
                } else {
                    pair(Color(DesignTokens.Light.ON_ACCENT), Color(DesignTokens.Dark.ON_ACCENT))
                },
                error = pair(SUNDAY_LIGHT, SUNDAY_DARK),
                sunday = pair(SUNDAY_LIGHT, SUNDAY_DARK),
                saturday = pair(SATURDAY_LIGHT, SATURDAY_DARK),
                track = pick({ it.track }),
                income = pair(Color(DesignTokens.Light.INCOME), Color(DesignTokens.Dark.INCOME)),
                expense = pair(Color(DesignTokens.Light.EXPENSE), Color(DesignTokens.Dark.EXPENSE)),
                grass = listOf(0.35f, 0.65f, 1f).map { a -> pick({ it.accent.copy(alpha = a) }) },
            )
        }
    }
}
