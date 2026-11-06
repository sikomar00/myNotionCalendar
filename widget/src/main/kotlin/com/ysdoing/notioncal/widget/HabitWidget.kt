package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
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
import androidx.glance.action.ActionParameters
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.action.actionRunCallback
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
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.habit.DayMark
import com.ysdoing.notioncal.core.data.habit.HabitBoard
import com.ysdoing.notioncal.core.data.habit.HabitSummary
import kotlinx.coroutines.flow.first
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** HB-03 습관 위젯 한 개가 그릴 것. Room에서 읽은 값만 담는다. */
data class HabitWidgetState(
    val board: HabitBoard,
    val config: WidgetConfig,
    /** 이번 달 잔디의 요일 줄(MV-01 주 시작). */
    val weekStart: DayOfWeek,
)

/** HB-03 크기 단계: 2×2 오늘 체크만 / 4×2 7일 / 4×4 7일 + 이번 달 잔디. */
enum class HabitWidgetMode { SMALL, MEDIUM, LARGE }

fun habitWidgetMode(size: DpSize): HabitWidgetMode = when {
    size.width < 250.dp -> HabitWidgetMode.SMALL
    size.height >= 250.dp -> HabitWidgetMode.LARGE
    else -> HabitWidgetMode.MEDIUM
}

/**
 * 습관 위젯 치수. 줄 높이는 월·목록 위젯과 같은 실측 규칙(글자 칸 = 글자 크기 × [WidgetMetrics.TEXT_BOX], 기기 글꼴 상한
 * [WidgetMetrics.MAX_SYSTEM_TEXT_SCALE])으로 정해 글꼴을 키워도 글자가 잘리지 않는다. 습관이 높이를 넘치면 목록을 스크롤한다.
 */
data class HabitMetrics(
    val mode: HabitWidgetMode,
    val headerHeight: Dp,
    val rowHeight: Dp,
    val dayLabelHeight: Dp,
    /** 칸 하나가 차지하는 폭(누르는 영역). */
    val cellWidth: Dp,
    /** 칠하는 네모 한 변. */
    val cellSize: Dp,
    val nameWidth: Dp,
    val countWidth: Dp,
    val grassHeight: Dp,
    val headerFontSp: Float,
    val nameFontSp: Float,
    val smallFontSp: Float,
    val heroFontSp: Float,
    val heroHeight: Dp,
    /**
     * 이름 아래 알약('연속 12일' 등, 약 76dp)을 그릴지. 줄이 한 줄 더 높아지므로 높이가 넉넉한 4×4만, 그리고 이름 칸에 들어갈 때만 그린다
     * (밀도 우선, 사용자 결정 2026-11-03: 2×2·4×2는 한 화면에 보이는 습관 수를 지킨다).
     */
    val showMessage: Boolean,
) {
    companion object {
        val PADDING_H = 8.dp
        val PADDING_V = 4.dp
        /** 2×2 오늘 칸: 눌림 영역을 48dp로(WL-4.2). 4×2·4×4는 폭이 모자라 칸 폭이 이보다 좁다(위젯 머리 버튼과 같은 예외). */
        private val SMALL_CELL = 28.dp

        /** 이름 칸 안쪽 여백(왼쪽 10dp + 오른쪽 4dp)과 알약이 들어갈 폭. */
        private val NAME_INSET = 14.dp

        /** '이번 주 n/목표' 글자가 타일 오른쪽 가장자리에 붙지 않게 두는 여백(글자 칸 안쪽). */
        val COUNT_INSET = 6.dp
        private val MESSAGE_PILL_WIDTH = 76.dp

        fun of(size: DpSize, config: WidgetConfig, systemFontScale: Float): HabitMetrics {
            val mode = habitWidgetMode(size)
            val scale = config.fontScale / 100f
            val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            val spFactor = textScale / systemFontScale
            fun box(fontSp: Float) = (fontSp * textScale * WidgetMetrics.TEXT_BOX).dp
            val header = 13f
            val name = 12f * scale
            val small = 10f * scale
            val hero = 30f * scale
            val contentWidth = size.width - PADDING_H * 2
            val countWidth = (small * textScale * 2.4f).dp + 4.dp + COUNT_INSET
            val smallCellWidth = TAP_TARGET
            val nameWidth = if (mode == HabitWidgetMode.SMALL) contentWidth - smallCellWidth - 8.dp else (contentWidth - countWidth) * 0.32f
            val cellWidth = if (mode == HabitWidgetMode.SMALL) smallCellWidth else (contentWidth - nameWidth - countWidth) / 7
            val cellSize = if (mode == HabitWidgetMode.SMALL) SMALL_CELL else (cellWidth - 4.dp).coerceIn(14.dp, 28.dp)
            val showMessage = mode == HabitWidgetMode.LARGE && nameWidth - NAME_INSET >= MESSAGE_PILL_WIDTH
            return HabitMetrics(
                mode = mode,
                // 머리: 아이콘 원 + 제목 + 부제 두 줄.
                headerHeight = maxOf(40.dp, box(header) + box(small) + 4.dp),
                // 줄: 이름 한 줄(4×4는 연속 알약까지 두 줄), 칸 원형 아이콘. 밀도 우선이라 눌림 높이를 48dp로 늘리지 않는다.
                rowHeight = maxOf(cellSize + 6.dp, box(name) + (if (showMessage) box(small) else 0.dp) + 8.dp),
                dayLabelHeight = box(small) + 2.dp,
                cellWidth = cellWidth,
                cellSize = cellSize,
                nameWidth = nameWidth,
                countWidth = countWidth,
                grassHeight = 12.dp,
                headerFontSp = header * spFactor,
                nameFontSp = name * spFactor,
                smallFontSp = small * spFactor,
                heroFontSp = hero * spFactor,
                heroHeight = box(hero),
                showMessage = showMessage,
            )
        }
    }
}

/**
 * HB-03 습관 위젯. Room만 읽고(WG-02), 칸을 누르면 [HabitToggleAction]이 아웃박스로 기록을 만들거나 지운다.
 * 크기 모드는 월 위젯처럼 Exact를 쓴다. 설정 화면은 없고 테마는 기기 다크 모드를 따른다.
 */
class HabitWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact

    /** WG-08 위젯 선택 화면 미리보기: 4×2 크기에 예시 습관(개인 기록 아님). */
    override val previewSizeMode = SizeMode.Responsive(setOf(WidgetPreviews.HABIT_SIZE))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.habitState()
        provideContent { HabitWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initial = HabitSession.load(deps)
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            HabitSession.Content(deps, initial, config, fontScale ?: SystemFontScale.current(context))
        }
    }
}

/** 습관 위젯과 통합 위젯(✅ 탭)이 함께 쓰는 세션. 첫 프레임부터 내용이 보이도록 초기값을 먼저 읽는다. */
internal object HabitSession {
    class Initial(val board: HabitBoard, val weekStart: DayOfWeek)

    suspend fun load(deps: WidgetDependencies): Initial {
        val today = LocalDate.now(APP_ZONE)
        return Initial(deps.habits().observeBoard(today).first(), deps.settings().settings.first().weekStart)
    }

    @Composable
    fun Content(deps: WidgetDependencies, initial: Initial, config: WidgetConfig, fontScale: Float, framed: Boolean = true, showHeader: Boolean = true) {
        // 그릴 때마다 오늘을 다시 계산한다(PRD §7-4). 자정 알람이 위젯을 다시 그리게 한다.
        val today = LocalDate.now(APP_ZONE)
        val board by remember(today) { deps.habits().observeBoard(today) }
            .collectAsState(if (initial.board.today == today) initial.board else HabitBoard(today, emptyList()))
        val weekStart by remember { deps.settings().settings }.collectAsState(null)
        HabitWidgetContent(HabitWidgetState(board, config, weekStart?.weekStart ?: initial.weekStart), fontScale, framed, showHeader)
    }
}

/** 습관 위젯 화면. 위젯·통합 위젯·미리보기가 같은 코드를 쓴다. 비트맵 없이 레이아웃·배경색만 쓴다(PF-07). */
@Composable
fun HabitWidgetContent(
    state: HabitWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
    /** false면 바깥 배경·모서리를 그리지 않는다(통합 위젯 안에 넣을 때). */
    framed: Boolean = true,
    showHeader: Boolean = true,
) {
    val context = LocalContext.current
    val metrics = HabitMetrics.of(LocalSize.current, state.config, systemFontScale)
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val outer = if (framed) {
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius).background(palette.background)
    } else {
        GlanceModifier.fillMaxSize()
    }
    val board = state.board
    Column(outer.padding(horizontal = HabitMetrics.PADDING_H, vertical = HabitMetrics.PADDING_V)) {
        if (showHeader) HabitHeader(board, metrics, palette)
        if (showHeader && metrics.mode == HabitWidgetMode.LARGE && board.habits.isNotEmpty()) HabitHero(board, metrics, palette)
        if (board.habits.isEmpty()) {
            WidgetEmpty(
                R.drawable.ic_widget_habit, context.getString(R.string.habit_widget_empty), palette, metrics.nameFontSp,
                ctaText = context.getString(R.string.habit_widget_empty_cta), ctaAction = actionStartActivity(habitTabIntent(context)), ctaTag = "habitEmpty",
            )
        } else {
            LazyColumn(GlanceModifier.fillMaxWidth().defaultWeight()) {
                if (metrics.mode == HabitWidgetMode.LARGE) {
                    item(itemId = GRASS_ITEM) { MonthGrass(state, metrics, palette) }
                }
                if (metrics.mode != HabitWidgetMode.SMALL) {
                    item(itemId = DAYS_ITEM) { DayLabels(board.today, metrics, palette) }
                }
                // LazyColumn 항목 ID는 page ID 해시(음수는 위의 머리 항목 몫).
                items(board.habits, itemId = { it.habit.pageId.hashCode().toLong() and 0xFFFFFFFFL }) { summary ->
                    HabitRow(summary, board.today, metrics, palette)
                }
            }
        }
    }
}

private const val GRASS_ITEM = -1L
private const val DAYS_ITEM = -2L

/** 습관 탭을 여는 인텐트. 앱이 떠 있으면 onNewIntent로 받는다(singleTop). */
internal fun habitTabIntent(context: Context): Intent =
    appIntent(context)
        .setData("notioncal://tab/habit".toUri())
        .putExtra(AppIntents.EXTRA_TAB, AppIntents.TAB_HABIT)
        .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** 머리: 새싹 아이콘 + '습관' + 오늘 n/m 완료. 제목을 누르면 습관 탭, 4×2 이상은 새로고침 버튼. */
@Composable
private fun HabitHeader(board: HabitBoard, metrics: HabitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    WidgetHeader(
        icon = R.drawable.ic_widget_habit,
        title = context.getString(R.string.habit_widget_label_short),
        subtitle = board.habits.takeIf { it.isNotEmpty() }?.let { context.getString(R.string.habit_widget_subtitle, board.doneToday, it.size) },
        palette = palette,
        height = metrics.headerHeight,
        titleSp = metrics.headerFontSp,
        subSp = metrics.smallFontSp,
        titleModifier = GlanceModifier.clickable(actionStartActivity(habitTabIntent(context))).semantics { testTag = "habitTitle" },
    ) {
        if (metrics.mode != HabitWidgetMode.SMALL) {
            WidgetIconButton(
                R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "habitRefresh", actionRunCallback<RefreshAction>(),
                palette, visual = 32.dp, height = metrics.headerHeight,
            )
        }
    }
}


/** 7일 칸 위의 요일 줄. 오늘은 굵게. 자식: 이름 자리 + 7 + 숫자 자리 = 9개(Glance 10개 제한). */
@Composable
private fun DayLabels(today: LocalDate, metrics: HabitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Row(GlanceModifier.fillMaxWidth().height(metrics.dayLabelHeight), verticalAlignment = Alignment.CenterVertically) {
        Spacer(GlanceModifier.width(metrics.nameWidth))
        for (back in 6 downTo 0) {
            val date = today.minusDays(back.toLong())
            val label = if (back == 0) context.getString(R.string.widget_today) else date.dayOfWeek.getDisplayName(JavaTextStyle.NARROW, Locale.KOREAN)
            Text(
                text = label,
                modifier = GlanceModifier.width(metrics.cellWidth),
                style = TextStyle(
                    color = if (back == 0) palette.text else palette.subtle,
                    fontSize = metrics.smallFontSp.sp,
                    fontWeight = if (back == 0) FontWeight.Bold else null,
                    textAlign = TextAlign.Center,
                ),
                maxLines = 1,
            )
        }
        Spacer(GlanceModifier.width(metrics.countWidth))
    }
}

/** 습관 한 줄: 타일 사이 간격(위아래 2dp씩)을 바깥 상자의 여백으로 두고 타일을 그린다. */
@Composable
private fun HabitRow(summary: HabitSummary, today: LocalDate, metrics: HabitMetrics, palette: WidgetPalette) {
    Box(GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)) { HabitRowTile(summary, today, metrics, palette) }
}

/** 타일 안: 작음은 이름 + 오늘 칸, 중간·큼은 이름 + 7일 칸 + 이번 주 n/목표. */
@Composable
private fun HabitRowTile(summary: HabitSummary, today: LocalDate, metrics: HabitMetrics, palette: WidgetPalette) {
    val habit = summary.habit
    val status = summary.status
    val name = listOfNotNull(habit.emoji, habit.name).joinToString(" ")
    Row(
        GlanceModifier.fillMaxWidth().height(metrics.rowHeight).tile(palette).semantics { testTag = "habitRow" },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(GlanceModifier.width(metrics.nameWidth).padding(start = 10.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = name,
                style = TextStyle(color = palette.text, fontSize = metrics.nameFontSp.sp, fontWeight = FontWeight.Medium),
                maxLines = 1,
            )
            HabitMessagePill(status.message, metrics, palette)
        }
        if (metrics.mode == HabitWidgetMode.SMALL) {
            DayCell(habit.pageId, habit.name, status.last7.last(), isToday = true, highlight = status.highlightToday, metrics, palette)
        } else {
            status.last7.forEach { mark ->
                DayCell(habit.pageId, habit.name, mark, isToday = mark.date == today, highlight = mark.date == today && status.highlightToday, metrics, palette)
            }
            Text(
                text = "${status.weekCount}/${status.weekTarget}",
                modifier = GlanceModifier.width(metrics.countWidth).padding(end = HabitMetrics.COUNT_INSET),
                style = TextStyle(
                    color = if (status.weekAchieved) palette.text else palette.subtle,
                    fontSize = metrics.smallFontSp.sp,
                    fontWeight = if (status.weekAchieved) FontWeight.Bold else null,
                    textAlign = TextAlign.End,
                ),
                maxLines = 1,
            )
        }
    }
}

/**
 * 하루 칸. 누르면 그날을 토글한다(HB-02, 오늘과 지난 6일). 한 날은 채운 초록 원 + 체크(색만으로 구분하지 않음, A11Y-03),
 * 하루 놓친 매일 습관의 오늘 칸은 강조색 빈 원(HB-04), 안 한 날은 흐린 빈 원, 시작 전은 빈칸.
 * 2×2·4×2는 칸이 작아 48dp 터치 영역을 줄 수 없다(A11Y-02 예외, 위젯 머리 버튼과 같음).
 */
@Composable
private fun DayCell(habitId: String, habitName: String, mark: DayMark, isToday: Boolean, highlight: Boolean, metrics: HabitMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val day = if (isToday) {
        context.getString(R.string.widget_today)
    } else {
        context.getString(R.string.habit_widget_date, mark.date.monthValue, mark.date.dayOfMonth, mark.date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN))
    }
    val description = listOf(habitName, day, context.getString(if (mark.done) R.string.habit_widget_done else R.string.habit_widget_not_done)).joinToString(", ")
    val action = actionRunCallback<HabitToggleAction>(
        actionParametersOf(HabitToggleAction.HabitKey to habitId, HabitToggleAction.DayKey to mark.date.toEpochDay()),
    )
    Box(
        modifier = GlanceModifier
            .width(metrics.cellWidth)
            .height(metrics.rowHeight)
            .clickable(action)
            .semantics {
                contentDescription = description
                testTag = if (isToday) "habitToday" else "habitCell"
            },
        contentAlignment = Alignment.Center,
    ) {
        val size = metrics.cellSize
        // 한 날은 채운 초록 원(체크 모양), 놓친 뒤 오늘은 강조 테두리 원, 안 한 날은 테두리 원. 모양과 낭독으로도 구분한다(A11Y-03).
        when {
            mark.done -> WidgetIcon(R.drawable.ic_widget_check_circle, palette.green, size)
            highlight -> WidgetIcon(R.drawable.ic_widget_circle, palette.accent, size)
            mark.beforeStart -> Spacer(GlanceModifier.size(size))
            else -> WidgetIcon(R.drawable.ic_widget_circle, palette.subtle, size)
        }
    }
}

/** HB-02 위젯 체크: 아웃박스로 기록을 만들거나 지우고, 누른 위젯만 바로 다시 그린다(§3 0.5초). 네트워크는 부르지 않는다. */
class HabitToggleAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val started = SystemClock.elapsedRealtime()
        val habitId = parameters[HabitKey] ?: return
        val day = parameters[DayKey] ?: return
        val result = WidgetDependencies.from(context).habits().toggle(habitId, LocalDate.ofEpochDay(day))
        WidgetKinds.update(context, glanceId)
        Log.d(GlanceWidgetRefresher.TAG, "습관 $result 반영: ${SystemClock.elapsedRealtime() - started}ms")
    }

    companion object {
        val HabitKey = ActionParameters.Key<String>("habitId")

        /** 날짜(epochDay). ActionParameters에는 기본 타입만 넣는다. */
        val DayKey = ActionParameters.Key<Long>("epochDay")
    }
}
