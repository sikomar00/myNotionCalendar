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
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.dday.Dday
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/** 디데이 위젯이 그릴 것. [ddays]는 보이는 디데이(가까운 순, 숨김 제외). */
data class DdayWidgetState(val ddays: List<Dday>, val config: WidgetConfig, val today: LocalDate) {
    /** DD-03·04: 위젯에서 고른 디데이. 없거나(해제·숨김) 고르지 않았으면 가장 가까운 디데이. */
    val chosen: Dday? get() = ddays.firstOrNull { it.pageId == config.ddayPageId } ?: ddays.firstOrNull()
}

/** 디데이 줄을 누르면 그 일정의 편집 시트(WI-05). 일정마다 PendingIntent가 구분되도록 data URI에 페이지 ID를 넣는다. */
internal fun ddayEditIntent(context: Context, pageId: String, date: LocalDate): Intent =
    quickSheetIntent(context, AppIntents.MODE_EDIT, date)
        .setData("notioncal://edit/$date/$pageId".toUri())
        .putExtra(AppIntents.EXTRA_PAGE_ID, pageId)

/** DD-05 '+'·빈 디데이 위젯: 앱의 디데이 화면(EX-12 링크 `notioncal://dday`). */
internal fun ddayScreenIntent(context: Context): Intent =
    Intent(Intent.ACTION_VIEW, "notioncal://dday".toUri())
        .setPackage(context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** '10/31 토' */
internal fun ddayDateText(date: LocalDate): String =
    "${date.monthValue}/${date.dayOfMonth} ${date.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, Locale.KOREAN)}"

private fun Dday.description(context: Context): String = listOfNotNull(
    title ?: context.getString(R.string.dday_widget_untitled),
    label?.text,
    start?.let { context.getString(R.string.dday_widget_date, it.monthValue, it.dayOfMonth) },
).joinToString(", ")

/**
 * DD-03·04 치수. 1×1은 숫자(크게) + 제목 한 줄, 2×1(폭 [WIDE_MIN] 이상)은 왼쪽 숫자 + 오른쪽 제목·날짜.
 * 글자 칸은 다른 위젯과 같은 실측 규칙(글자 크기 × [WidgetMetrics.TEXT_BOX], 기기 글꼴 상한 [WidgetMetrics.MAX_SYSTEM_TEXT_SCALE]).
 * 숫자는 높이와 폭(글자 수) 둘 다에 맞추고, 네 자리('D-1234')는 한 단계 작은 상한을 쓴다. 글꼴 최대에서도 잘리지 않는다.
 */
internal data class DdayMetrics(
    val wide: Boolean,
    val numberSp: Float,
    val titleSp: Float,
    val subSp: Float,
    val textScale: Float,
    val spFactor: Float,
) {
    private fun box(sp: Float) = sp * textScale * WidgetMetrics.TEXT_BOX

    fun fitsHeight(size: DpSize): Boolean {
        val h = (size.height - PADDING * 2).value + 0.5f
        return if (wide) box(numberSp) <= h && box(titleSp) + box(subSp) <= h else box(numberSp) + box(titleSp) <= h
    }

    fun fitsWidth(size: DpSize, label: String): Boolean {
        val w = (size.width - PADDING * 2).value * (if (wide) NUMBER_SHARE else 1f) + 0.5f
        return numberSp * textScale * emWidth(label) <= w
    }

    companion object {
        val PADDING = 6.dp
        val WIDE_MIN = 110.dp
        private const val NUMBER_SHARE = 0.5f
        private const val NUMBER_MAX = 34f
        private const val NUMBER_MAX_FOUR_DIGITS = 26f
        private const val TITLE = 11f
        private const val SUB = 10f

        /** 글자 폭(글자 크기 배수): 한글 1, 숫자·영문 0.62, 부호 0.6, 가운뎃점·빈칸 0.35. 굵은 숫자를 넉넉히 잡았다. */
        fun emWidth(text: String): Float = text.sumOf { c ->
            when {
                c in '가'..'힣' -> 1.0
                c.isLetterOrDigit() -> 0.62
                c == '-' || c == '+' -> 0.6
                c == ' ' || c == '·' -> 0.35
                else -> 1.0
            }
        }.toFloat()

        fun of(size: DpSize, config: WidgetConfig, systemFontScale: Float, label: String): DdayMetrics {
            val scale = config.fontScale / 100f
            val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            val unit = textScale * WidgetMetrics.TEXT_BOX // 글자 1sp가 차지하는 칸 높이(dp)
            val w = (size.width - PADDING * 2).value
            val h = (size.height - PADDING * 2).value
            val wide = size.width >= WIDE_MIN
            val digits = label.count(Char::isDigit)
            val max = (if (digits >= 4) NUMBER_MAX_FOUR_DIGITS else NUMBER_MAX) * scale
            val em = emWidth(label).coerceAtLeast(1f)
            val number: Float
            var title: Float
            var sub: Float
            if (wide) {
                number = minOf(max, h / unit, w * NUMBER_SHARE / (textScale * em))
                title = TITLE * scale
                sub = SUB * scale
                val need = (title + sub) * unit
                if (need > h) {
                    title *= h / need
                    sub *= h / need
                }
            } else {
                // 제목은 높이의 34%까지만 쓰고 나머지를 숫자에 준다(글꼴을 키워도 숫자가 먼저 보이게).
                title = minOf(TITLE * scale, h * 0.34f / unit)
                sub = 0f
                number = minOf(max, (h - title * unit) / unit, w / (textScale * em))
            }
            val spFactor = textScale / systemFontScale
            return DdayMetrics(wide, number, title, sub, textScale, spFactor)
        }
    }
}

/** DD-03·04 디데이 하나(1×1·2×1). 위젯·미리보기가 같은 코드를 쓴다. 비트맵 없이 그린다(PF-07). */
@Composable
fun DdayWidgetContent(
    state: DdayWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
) {
    val context = LocalContext.current
    val size = LocalSize.current
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val dday = state.chosen
    val label = dday?.label
    val start = dday?.start
    val outer = GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
        .background(palette.background)
    if (dday == null || label == null || start == null) {
        Box(
            outer.clickable(actionStartActivity(ddayScreenIntent(context))).semantics { testTag = "dday" },
            contentAlignment = Alignment.Center,
        ) {
            Text(context.getString(R.string.dday_widget_empty), style = TextStyle(color = palette.subtle, fontSize = 11.sp, textAlign = TextAlign.Center), maxLines = 2)
        }
        return
    }
    val m = DdayMetrics.of(size, state.config, systemFontScale, label.main)
    val numberStyle = TextStyle(
        color = if (label.inProgress || label.main == "D-Day") palette.accent else palette.text,
        fontSize = (m.numberSp * m.spFactor).sp,
        fontWeight = FontWeight.Bold,
        textAlign = TextAlign.Center,
    )
    val title = dday.title ?: context.getString(R.string.dday_widget_untitled)
    val description = dday.description(context)
    val tap = outer.padding(DdayMetrics.PADDING).clickable(actionStartActivity(ddayEditIntent(context, dday.pageId, start))).semantics {
        contentDescription = description
        testTag = "dday"
    }
    if (m.wide) {
        Row(tap, verticalAlignment = Alignment.CenterVertically) {
            Box(GlanceModifier.width((size.width - DdayMetrics.PADDING * 2) * 0.5f), contentAlignment = Alignment.Center) {
                Text(label.main, style = numberStyle, maxLines = 1)
            }
            Column(GlanceModifier.defaultWeight().padding(start = 4.dp)) {
                Text(title, style = TextStyle(color = palette.text, fontSize = (m.titleSp * m.spFactor).sp, fontWeight = FontWeight.Medium), maxLines = 1)
                Text(
                    label.sub ?: ddayDateText(start),
                    style = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp),
                    maxLines = 1,
                )
            }
        }
    } else {
        Column(tap, horizontalAlignment = Alignment.CenterHorizontally, verticalAlignment = Alignment.CenterVertically) {
            Text(label.main, style = numberStyle, maxLines = 1)
            Text(title, style = TextStyle(color = palette.subtle, fontSize = (m.titleSp * m.spFactor).sp, textAlign = TextAlign.Center), maxLines = 1)
        }
    }
}

/** DD-05 목록 줄 치수. 줄 높이는 표기·제목·날짜 글자 칸에서 정한다(글꼴 최대에서 잘림 0). */
internal data class DdayListMetrics(val rowHeight: Dp, val headerHeight: Dp, val labelWidth: Dp, val labelSp: Float, val titleSp: Float, val dateSp: Float) {
    companion object {
        fun of(config: WidgetConfig, systemFontScale: Float): DdayListMetrics {
            val scale = config.fontScale / 100f
            val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
            val spFactor = textScale / systemFontScale
            fun box(sp: Float) = (sp * textScale * WidgetMetrics.TEXT_BOX).dp
            val label = 14f * scale
            val title = 12f * scale
            val date = 10f * scale
            return DdayListMetrics(
                rowHeight = maxOf(box(label), box(title) + box(date)) + 6.dp,
                headerHeight = maxOf(36.dp, box(13f) + 8.dp),
                labelWidth = (label * textScale * DdayMetrics.emWidth("D-123") + 8f).dp,
                labelSp = label * spFactor,
                titleSp = title * spFactor,
                dateSp = date * spFactor,
            )
        }
    }
}

/** DD-05 디데이 목록 위젯(2×2 이상): 가까운 순 스크롤. 줄 → 편집 시트, '+' → 디데이 화면, 비면 안내. */
@Composable
fun DdayListWidgetContent(
    state: DdayWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
) {
    val context = LocalContext.current
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val m = DdayListMetrics.of(state.config, systemFontScale)
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background).padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Row(GlanceModifier.fillMaxWidth().height(m.headerHeight), verticalAlignment = Alignment.CenterVertically) {
            Text(
                context.getString(R.string.dday_widget_title),
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = palette.text, fontSize = 13.sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
            val add = context.getString(R.string.dday_widget_add)
            Box(
                GlanceModifier.width(40.dp).height(m.headerHeight).clickable(actionStartActivity(ddayScreenIntent(context))).semantics {
                    contentDescription = add
                    testTag = "ddayAdd"
                },
                contentAlignment = Alignment.CenterEnd,
            ) {
                WidgetIcon(R.drawable.ic_widget_add, palette.accent, 22.dp)
            }
        }
        if (state.ddays.isEmpty()) {
            Text(
                context.getString(R.string.dday_widget_list_empty),
                modifier = GlanceModifier.fillMaxWidth().padding(top = 8.dp),
                style = TextStyle(color = palette.subtle, fontSize = 12.sp),
            )
            return@Column
        }
        LazyColumn(GlanceModifier.fillMaxSize()) {
            items(state.ddays, itemId = { it.pageId.hashCode().toLong() }) { dday ->
                val label = dday.label
                val start = dday.start
                if (label != null && start != null) {
                    val description = dday.description(context)
                    Row(
                        GlanceModifier.fillMaxWidth().height(m.rowHeight)
                            .clickable(actionStartActivity(ddayEditIntent(context, dday.pageId, start)))
                            .semantics {
                                contentDescription = description
                                testTag = "ddayRow"
                            },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            label.main,
                            modifier = GlanceModifier.width(m.labelWidth),
                            style = TextStyle(
                                color = if (label.inProgress || label.main == "D-Day") palette.accent else palette.text,
                                fontSize = m.labelSp.sp,
                                fontWeight = FontWeight.Bold,
                            ),
                            maxLines = 1,
                        )
                        Column(GlanceModifier.defaultWeight()) {
                            Text(dday.title ?: context.getString(R.string.dday_widget_untitled), style = TextStyle(color = palette.text, fontSize = m.titleSp.sp), maxLines = 1)
                            Text(label.sub ?: ddayDateText(start), style = TextStyle(color = palette.subtle, fontSize = m.dateSp.sp), maxLines = 1)
                        }
                    }
                }
            }
        }
    }
}

/** 디데이 위젯 공용 세션: 첫 프레임부터 내용이 보이도록 초기값을 먼저 읽고, 그릴 때마다 오늘을 다시 계산한다(PRD §7-4). */
internal object DdaySession {
    suspend fun load(deps: WidgetDependencies): List<Dday> = deps.ddays().observeVisible(LocalDate.now(APP_ZONE)).first()

    @Composable
    fun state(deps: WidgetDependencies, initial: List<Dday>, config: WidgetConfig): DdayWidgetState {
        val today = LocalDate.now(APP_ZONE)
        val ddays by remember(today) { deps.ddays().observeVisible(today) }.collectAsState(initial)
        return DdayWidgetState(ddays, config, today)
    }
}

/** DD-03·04 디데이 위젯(1×1·2×1 공용, 실제 폭으로 모양을 정한다). Room만 읽는다(WG-02). */
class DdayWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(150.dp, 70.dp)))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.ddayState()
        provideContent { DdayWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initial = DdaySession.load(deps)
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            DdayWidgetContent(DdaySession.state(deps, initial, config), fontScale ?: SystemFontScale.current(context))
        }
    }
}

/** DD-05 디데이 목록 위젯. Room만 읽는다(WG-02). */
class DdayListWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 180.dp)))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.ddayState()
        provideContent { DdayListWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initial = DdaySession.load(deps)
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val fontScale by SystemFontScale.flow.collectAsState()
            DdayListWidgetContent(DdaySession.state(deps, initial, config), fontScale ?: SystemFontScale.current(context))
        }
    }
}
