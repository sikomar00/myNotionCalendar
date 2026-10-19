package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.ledger.DayMoney
import com.ysdoing.notioncal.core.data.ledger.LedgerBoard
import com.ysdoing.notioncal.core.data.ledger.MoneyFormat
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** LD-03 가계부 위젯 한 개가 그릴 것. Room에서 읽은 값만 담는다. */
data class LedgerWidgetState(val board: LedgerBoard, val config: WidgetConfig)

/** 크기 단계: 6주가 들어가면 달력 그리드, 아니면 이번 주가 맨 아래인 최근 몇 주(4×2는 보통 1~2주). */
enum class LedgerWidgetMode { LARGE, COMPACT }

fun ledgerWidgetMode(size: DpSize): LedgerWidgetMode = if (size.height >= 250.dp) LedgerWidgetMode.LARGE else LedgerWidgetMode.COMPACT

/**
 * 가계부 위젯 치수. 줄 높이는 다른 위젯과 같은 실측 규칙(글자 칸 = 글자 크기 × [WidgetMetrics.TEXT_BOX], 기기 글꼴 상한
 * [WidgetMetrics.MAX_SYSTEM_TEXT_SCALE])으로 정한다. 6주가 칸 안에 들어가지 않으면 들어가는 만큼의 주를 이번 주가 맨 아래에
 * 오도록 보이고(최근 쓴 돈부터), 한 주도 모자라면 요약 줄·요일 줄을 뺀다(칸이 잘리지 않게, LD-03 '글꼴 최대에서 칸 넘침 0').
 */
data class LedgerMetrics(
    val mode: LedgerWidgetMode,
    val headerHeight: Dp,
    val lineHeight: Dp,
    val weekdayHeight: Dp,
    val cellHeight: Dp,
    val contentWidth: Dp,
    val rows: Int,
    val showSummary: Boolean,
    val showWeekdays: Boolean,
    val headerFontSp: Float,
    val smallFontSp: Float,
    val dateFontSp: Float,
    val amountFontSp: Float,
) {
    companion object {
        val PADDING_H = 8.dp
        val PADDING_V = 4.dp
        val BAR = 6.dp
        /** LD-07 머리줄 '+' 버튼 너비(누르기 쉬운 폭). */
        val ADD_WIDTH = 40.dp

        fun of(size: DpSize, config: WidgetConfig, systemFontScale: Float): LedgerMetrics {
            val scale = config.fontScale / 100f
            val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            val spFactor = textScale / systemFontScale
            fun box(fontSp: Float) = (fontSp * textScale * WidgetMetrics.TEXT_BOX).dp
            val header = 13f
            val small = 10f * scale
            val date = 10f * scale
            val amount = 9f * scale
            val headerHeight = maxOf(28.dp, box(header) + 4.dp)
            val line = box(small) + 2.dp
            val weekday = box(small)
            val cellNeeded = box(date) + box(amount) + 2.dp
            val available = size.height - PADDING_V * 2
            fun content(summary: Boolean, weekdays: Boolean) =
                available - headerHeight - (if (summary) line else 0.dp) - line - BAR - 4.dp - (if (weekdays) weekday else 0.dp)

            var mode = ledgerWidgetMode(size)
            var summary = true
            var weekdays = true
            if (content(summary, weekdays) < cellNeeded) summary = false
            if (content(summary, weekdays) < cellNeeded) weekdays = false
            val fit = (content(summary, weekdays) / cellNeeded).toInt().coerceIn(1, 6)
            val rows = if (mode == LedgerWidgetMode.LARGE && fit == 6) 6 else minOf(fit, 5)
            if (rows < 6) mode = LedgerWidgetMode.COMPACT
            val cell = maxOf(cellNeeded, content(summary, weekdays) / rows)
            return LedgerMetrics(
                mode = mode,
                headerHeight = headerHeight,
                lineHeight = line,
                weekdayHeight = weekday,
                cellHeight = cell,
                contentWidth = size.width - PADDING_H * 2,
                rows = rows,
                showSummary = summary,
                showWeekdays = weekdays,
                headerFontSp = header * spFactor,
                smallFontSp = small * spFactor,
                dateFontSp = date * spFactor,
                amountFontSp = amount * spFactor,
            )
        }
    }
}

/** LD-03 가계부 달력 위젯. Room만 읽는다(WG-02). 금액 숨김·테마·글자 배율은 위젯 설정(WG-04·LD-06). */
class LedgerWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** WG-08 위젯 선택 화면 미리보기: 4×4에 예시 거래(개인 금액 아님). */
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetPreviews.MONTH_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.ledgerState()
        provideContent { LedgerWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initial = LedgerSession.load(deps)
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            LedgerSession.Content(deps, initial, config, fontScale ?: SystemFontScale.current(context))
        }
    }
}

/** 가계부 위젯과 통합 위젯(💰 탭)이 함께 쓰는 세션. 첫 프레임부터 내용이 보이도록 초기값을 먼저 읽는다. */
internal object LedgerSession {
    suspend fun load(deps: WidgetDependencies): LedgerBoard {
        val today = LocalDate.now(APP_ZONE)
        return deps.ledger().observeBoard(YearMonth.from(today), today).first()
    }

    @Composable
    fun Content(deps: WidgetDependencies, initial: LedgerBoard, config: WidgetConfig, fontScale: Float, framed: Boolean = true) {
        // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4).
        val today = LocalDate.now(APP_ZONE)
        val board by remember(today) { deps.ledger().observeBoard(YearMonth.from(today), today) }
            .collectAsState(initial.takeIf { it.today == today } ?: initial)
        LedgerWidgetContent(LedgerWidgetState(board, config), fontScale, framed)
    }
}

/** 💰 탭을 여는 인텐트(위젯 머리줄). */
internal fun ledgerTabIntent(context: Context): Intent =
    appIntent(context)
        .setData("notioncal://tab/ledger".toUri())
        .putExtra(AppIntents.EXTRA_TAB, AppIntents.TAB_LEDGER)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/**
 * LD-07 입력 시트 인텐트. :widget은 :feature:ledger에 의존할 수 없어 action 문자열로 연다.
 * 날짜마다 다른 data URI를 붙여 PendingIntent가 서로 덮어쓰지 않게 한다.
 */
internal fun ledgerInputIntent(context: Context, date: LocalDate): Intent =
    Intent(AppIntents.ACTION_LEDGER_INPUT)
        .setPackage(context.packageName)
        .setData("notioncal://ledger/add/$date".toUri())
        .putExtra(AppIntents.EXTRA_DATE, date.toString())
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/** 가계부 위젯 화면. 위젯·통합 위젯·미리보기가 같은 코드를 쓴다. 비트맵 없이 레이아웃·배경색만 쓴다(PF-07). */
@Composable
fun LedgerWidgetContent(
    state: LedgerWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
    framed: Boolean = true,
) {
    val context = LocalContext.current
    val metrics = LedgerMetrics.of(LocalSize.current, state.config, systemFontScale)
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val hidden = state.config.hideAmounts
    val board = state.board
    val s = board.summary
    val outer = if (framed) {
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius).background(palette.background)
    } else {
        GlanceModifier.fillMaxSize()
    }
    // 자식: 위쪽 묶음 1 + 요일 1 + 주 줄 최대 6 = 8개(Glance 10개 제한).
    Column(outer.padding(horizontal = LedgerMetrics.PADDING_H, vertical = LedgerMetrics.PADDING_V)) {
        Column(GlanceModifier.fillMaxWidth()) {
            Row(
                GlanceModifier.fillMaxWidth().height(metrics.headerHeight).clickable(actionStartActivity(ledgerTabIntent(context)))
                    .semantics { testTag = "ledgerTitle" },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = context.getString(R.string.ledger_widget_title, board.month.monthValue, MoneyFormat.short(s.monthSpend, hidden)),
                    modifier = GlanceModifier.defaultWeight(),
                    style = TextStyle(color = palette.text, fontSize = metrics.headerFontSp.sp, fontWeight = FontWeight.Bold),
                    maxLines = 1,
                )
                Text(
                    text = s.asOf?.let { context.getString(R.string.ledger_widget_as_of, it.monthValue, it.dayOfMonth) }
                        ?: context.getString(R.string.ledger_widget_as_of_none),
                    style = TextStyle(color = palette.subtle, fontSize = metrics.smallFontSp.sp),
                    maxLines = 1,
                )
                val add = context.getString(R.string.ledger_widget_add)
                Box(
                    GlanceModifier.width(LedgerMetrics.ADD_WIDTH).height(metrics.headerHeight)
                        .clickable(actionStartActivity(ledgerInputIntent(context, board.today)))
                        .semantics {
                            contentDescription = add
                            testTag = "ledgerAdd"
                        },
                    contentAlignment = Alignment.CenterEnd,
                ) {
                    WidgetIcon(R.drawable.ic_widget_add, palette.accent, (metrics.headerHeight * 0.7f).coerceAtMost(24.dp))
                }
            }
            if (metrics.showSummary) {
                Text(
                    text = summaryLine(context, board, hidden),
                    modifier = GlanceModifier.fillMaxWidth().height(metrics.lineHeight),
                    style = TextStyle(color = palette.subtle, fontSize = metrics.smallFontSp.sp),
                    maxLines = 1,
                )
            }
            WeekLine(board, metrics, palette, hidden)
        }
        if (metrics.showWeekdays) {
            Row(GlanceModifier.fillMaxWidth().height(metrics.weekdayHeight)) {
                repeat(7) { i ->
                    Text(
                        text = board.weekStart.plus(i.toLong()).getDisplayName(JavaTextStyle.NARROW, Locale.KOREAN),
                        modifier = GlanceModifier.defaultWeight(),
                        style = TextStyle(color = palette.subtle, fontSize = metrics.smallFontSp.sp, textAlign = TextAlign.Center),
                        maxLines = 1,
                    )
                }
            }
        }
        // 6주면 달력 그리드, 아니면 이번 주가 맨 아래인 최근 몇 주.
        val firstDay = if (metrics.rows == 6) board.gridStart else s.weekStart.minusWeeks(metrics.rows - 1L)
        repeat(metrics.rows) { week ->
            Row(GlanceModifier.fillMaxWidth().height(metrics.cellHeight)) {
                repeat(7) { col ->
                    val date = firstDay.plusDays(week * 7L + col)
                    DayCell(date, board, s.days[date] ?: DayMoney(), metrics, palette, hidden)
                }
            }
        }
    }
}

private fun summaryLine(context: Context, board: LedgerBoard, hidden: Boolean): String {
    val s = board.summary
    val budget = s.monthRemaining?.let { r ->
        if (r >= 0) context.getString(R.string.ledger_widget_month_left, MoneyFormat.short(r, hidden))
        else context.getString(R.string.ledger_widget_month_over, MoneyFormat.short(-r, hidden))
    } ?: context.getString(R.string.ledger_widget_no_budget)
    return listOf(
        context.getString(R.string.ledger_widget_planned, MoneyFormat.short(s.monthPlanned, hidden)),
        context.getString(R.string.ledger_widget_income, MoneyFormat.short(s.monthIncome, hidden)),
        budget,
    ).joinToString(" · ")
}

/** LD-04 주 예산 줄과 막대. 넘으면 경고색 + '초과' 글자. 금액을 숨겨도 막대는 남긴다(LD-06). */
@Composable
private fun WeekLine(board: LedgerBoard, metrics: LedgerMetrics, palette: WidgetPalette, hidden: Boolean) {
    val context = LocalContext.current
    val s = board.summary
    val remaining = s.weekRemaining
    val budget = s.weekBudget
    val over = remaining != null && remaining < 0
    Text(
        text = when {
            remaining == null -> context.getString(R.string.ledger_widget_week_spend, MoneyFormat.short(s.weekSpend, hidden))
            over -> context.getString(R.string.ledger_widget_week_over, MoneyFormat.short(-remaining, hidden), s.weekDaysLeft)
            else -> context.getString(R.string.ledger_widget_week_left, MoneyFormat.short(remaining, hidden), s.weekDaysLeft)
        },
        modifier = GlanceModifier.fillMaxWidth().height(metrics.lineHeight),
        style = TextStyle(color = if (over) palette.error else palette.text, fontSize = metrics.smallFontSp.sp, fontWeight = FontWeight.Medium),
        maxLines = 1,
    )
    val used = if (budget != null && budget > 0 && remaining != null) ((budget - remaining).toFloat() / budget).coerceIn(0f, 1f) else 0f
    Box(
        GlanceModifier.fillMaxWidth().height(LedgerMetrics.BAR).cornerRadius(3.dp).background(palette.track).semantics { testTag = "ledgerBar" },
    ) {
        if (used > 0f) {
            Box(GlanceModifier.width(metrics.contentWidth * used).height(LedgerMetrics.BAR).cornerRadius(3.dp).background(if (over) palette.error else palette.accent)) {}
        }
    }
    Spacer(GlanceModifier.height(4.dp))
}

/** LD-03 하루 칸: 지난 날·오늘은 지출(진하게), 앞날은 예정(흐리게 + 밑줄), 수입이 있으면 금액 대신 초록 '+'(지출이 없을 때). */
@Composable
private fun androidx.glance.layout.RowScope.DayCell(
    date: LocalDate, board: LedgerBoard, money: DayMoney, metrics: LedgerMetrics, palette: WidgetPalette, hidden: Boolean,
) {
    val context = LocalContext.current
    val future = date.isAfter(board.today)
    val amount = if (future) money.planned else money.spend
    val inMonth = YearMonth.from(date) == board.month
    val description = listOfNotNull(
        context.getString(R.string.ledger_widget_day, date.monthValue, date.dayOfMonth),
        amount.takeIf { it != 0L && !hidden }?.let {
            context.getString(if (future) R.string.ledger_widget_day_planned else R.string.ledger_widget_day_spend, MoneyFormat.full(it))
        },
        money.income.takeIf { it != 0L && !hidden }?.let { context.getString(R.string.ledger_widget_day_income, MoneyFormat.full(it)) },
    ).joinToString(", ")
    Column(
        GlanceModifier.defaultWeight().height(metrics.cellHeight).clickable(actionStartActivity(ledgerInputIntent(context, date))).semantics {
            contentDescription = description
            testTag = "ledgerDay"
        },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val isToday = date == board.today
        Text(
            text = date.dayOfMonth.toString(),
            style = TextStyle(
                color = when {
                    isToday -> palette.accent
                    inMonth -> palette.text
                    else -> palette.outside
                },
                fontSize = metrics.dateFontSp.sp,
                fontWeight = if (isToday) FontWeight.Bold else null,
            ),
            maxLines = 1,
        )
        when {
            amount != 0L -> Text(
                text = MoneyFormat.short(amount, hidden),
                style = TextStyle(
                    color = if (future) palette.subtle else palette.text,
                    fontSize = metrics.amountFontSp.sp,
                    fontWeight = if (future) null else FontWeight.Medium,
                    textDecoration = if (future) TextDecoration.Underline else null,
                ),
                maxLines = 1,
            )
            money.income != 0L -> Text(
                text = "+" + MoneyFormat.short(money.income, hidden),
                style = TextStyle(color = palette.income, fontSize = metrics.amountFontSp.sp),
                maxLines = 1,
            )
        }
    }
}
