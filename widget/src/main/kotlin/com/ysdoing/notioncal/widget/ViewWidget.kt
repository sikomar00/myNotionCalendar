package com.ysdoing.notioncal.widget

import android.appwidget.AppWidgetManager
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
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import java.time.Instant
import java.time.format.DateTimeFormatter

/** [board]가 null이고 [config]에 보기가 있으면 등록을 해제한 보기다(VW-06). 노션에서 지워진 보기는 [ViewBoard.notFound]로 온다. */
data class ViewWidgetState(val board: ViewBoard?, val config: WidgetConfig, val nowMillis: Long)

/** 표(넓을 때)에 넣을 열 수. 열 하나에 [COLUMN_MIN]dp는 있어야 글자가 읽힌다. */
internal fun tableColumnCount(width: Dp, columns: Int): Int =
    ((width - PADDING_H * 2) / COLUMN_MIN).toInt().coerceIn(1, maxOf(columns, 1))

internal val COLUMN_MIN = 72.dp
private val PADDING_H = 10.dp
internal val VIEW_PADDING_V = 4.dp
private val TABLE_MIN_WIDTH = 220.dp
private const val STALE_MILLIS = 12 * 60 * 60 * 1000L

/**
 * 위젯 안 치수. 글자 배율(위젯별)과 시스템 글꼴(상한 [WidgetMetrics.MAX_SYSTEM_TEXT_SCALE])에 맞춰 줄 높이를 잡아 글자가 잘리지 않게 한다.
 * [spFactor]는 시스템 글꼴을 상한에서 자른 만큼 sp를 되돌린다(위젯 글자는 시스템 배율을 또 곱해 그려진다).
 */
internal data class ViewMetrics(
    val spFactor: Float,
    val titleSp: Float,
    val subSp: Float,
    val headerSp: Float,
    /** 제목만 있는 머리와, 제목 아래 부제('완료 n/m')가 있는 머리의 높이. 눌림 버튼이 들어가도록 40dp 이상이다. */
    val header: Dp,
    val headerWithSub: Dp,
    val tableHeader: Dp,
    val listRow: Dp,
    val tableRow: Dp,
)

internal fun viewMetrics(userScale: Float, systemFontScale: Float): ViewMetrics {
    val textScale = minOf(systemFontScale, WidgetMetrics.MAX_SYSTEM_TEXT_SCALE)
    fun box(sp: Float) = (sp * textScale * WidgetMetrics.TEXT_BOX).dp
    val titleSp = 12f * userScale
    val subSp = 10f * userScale
    val headerSp = 13f * userScale
    return ViewMetrics(
        spFactor = textScale / systemFontScale,
        titleSp = titleSp,
        subSp = subSp,
        headerSp = headerSp,
        header = maxOf(40.dp, box(headerSp) + 8.dp),
        headerWithSub = maxOf(44.dp, box(headerSp) + box(subSp) + 4.dp),
        tableHeader = box(subSp) + 4.dp,
        // 행은 타일이라 위아래 여백을 조금 더 둔다(틈 4dp는 행 밖).
        listRow = box(titleSp) + box(subSp) + 10.dp,
        tableRow = box(titleSp) + 10.dp,
    )
}

/** VW-06 보기를 다시 고르러 위젯 설정을 연다. 설정 액티비티는 위젯 ID를 `EXTRA_APPWIDGET_ID`로 받는다(길게 눌러 다시 설정할 때와 같다). */
internal fun viewConfigIntent(context: Context, appWidgetId: Int): Intent =
    Intent(context, WidgetConfigActivity::class.java)
        .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

/** VW-06 머리 아래 상태 한 줄. 색이 아니라 글자로 구분한다(A11Y-03). */
internal fun viewStatusText(context: Context, board: ViewBoard, nowMillis: Long): String? = when {
    board.notFound -> context.getString(R.string.view_widget_not_found)
    board.syncedAt == 0L -> context.getString(R.string.view_widget_not_synced)
    board.lastError != null -> context.getString(R.string.view_widget_failed, timeText(board.syncedAt))
    nowMillis - board.syncedAt > STALE_MILLIS -> context.getString(R.string.view_widget_stale, timeText(board.syncedAt))
    else -> null
}

private fun timeText(millis: Long): String = DateTimeFormatter.ofPattern("M/d HH:mm").format(Instant.ofEpochMilli(millis).atZone(APP_ZONE))

/**
 * VW-03 보기 위젯. 넓으면 표(열 머리 + 행), 좁으면 목록(제목 + 둘째 줄에 다음 속성 1개). 머리를 누르면 새로고침.
 * 위젯·미리보기가 같은 코드를 쓴다. 비트맵 없이 그린다(PF-07).
 * [staticRows]는 설정 화면 미리보기용이다. 그 화면은 변환 결과를 View에 직접 적용하는데 목록(LazyColumn)의 행이 그려지지 않아,
 * 목록 대신 고정 Column으로 앞쪽 [PREVIEW_ROWS]행만 그린다. 실제 위젯은 항상 목록이다.
 */
@Composable
fun ViewWidgetContent(
    state: ViewWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
    staticRows: Boolean = false,
) {
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, LocalContext.current)
    val metrics = viewMetrics(state.config.fontScale / 100f, systemFontScale)
    val board = state.board
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background).padding(horizontal = PADDING_H, vertical = VIEW_PADDING_V),
    ) {
        ViewHeader(board, metrics, palette)
        if (board == null) {
            val message = if (state.config.viewId == null) R.string.view_widget_choose else R.string.view_widget_missing
            ViewNote(message, state.config.appWidgetId, metrics, palette)
        } else {
            ViewBody(board, state, metrics, palette, staticRows)
        }
    }
}

/** VW-08 새 항목 입력 시트 열기. 보기마다 다른 data URI를 달아 PendingIntent가 서로 덮지 않게 한다(:widget은 :feature에 의존하지 않아 action 문자열로 연다). */
internal fun viewAddIntent(context: Context, viewId: String): Intent =
    Intent(AppIntents.ACTION_VIEW_ADD)
        .setPackage(context.packageName)
        .setData("notioncal://view/add/$viewId".toUri())
        .putExtra(AppIntents.EXTRA_VIEW_ID, viewId)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)

/**
 * 머리(공통 [WidgetHeader]): 체크리스트 아이콘 + 보기 이름(누르면 새로고침) + 부제 '완료 n/m'(높이가 넉넉하고 진행을 셀 수 있을 때),
 * 오른쪽에 새로고침(넓을 때)과, 새 항목을 만들 수 있는 보기(제목 열 정보가 있음)면 '+'.
 */
@Composable
private fun ViewHeader(board: ViewBoard?, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    // 이름 없는 보기도 노션에서 허용된다. 접근성 설명이 글자를 대신하므로 보기 이름을 함께 읽게 한다.
    val title = board?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.view_widget_label)
    val description = context.getString(R.string.view_widget_header_description, title)
    val size = LocalSize.current
    val progress = board?.takeIf { size.height >= SUBTITLE_MIN_HEIGHT }?.let(::viewProgress)
    val height = if (progress != null) m.headerWithSub else m.header
    WidgetHeader(
        icon = R.drawable.ic_widget_checklist,
        title = title,
        subtitle = progress?.let { context.getString(R.string.view_widget_progress, it.first, it.second) },
        palette = palette,
        height = height,
        titleSp = m.headerSp * m.spFactor,
        subSp = m.subSp * m.spFactor,
        titleModifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()).semantics {
            contentDescription = description
            testTag = "viewHeader"
        },
    ) {
        if (board != null && size.width >= REFRESH_MIN_WIDTH) {
            WidgetIconButton(
                R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "viewRefresh", actionRunCallback<RefreshAction>(), palette, height = height,
            )
        }
        if (board?.canCreate == true) {
            WidgetIconButton(
                R.drawable.ic_widget_add, context.getString(R.string.view_widget_add_description, title), "viewAdd",
                actionStartActivity(viewAddIntent(context, board.viewId)), palette, style = IconButtonStyle.FILLED, height = height,
            )
        }
    }
}

/** 부제는 3줄 높이쯤부터, 새로고침 버튼은 4칸 폭쯤부터 둔다(2×2에서 머리와 한 줄이 함께 보여야 한다). */
private val SUBTITLE_MIN_HEIGHT = 140.dp
private val REFRESH_MIN_WIDTH = 200.dp

/** 고르지 않았거나 등록이 지워진 보기: 글자로 알리고, 누르면 위젯 설정에서 다시 고른다. */
@Composable
private fun ViewNote(message: Int, appWidgetId: Int, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Text(
        context.getString(message),
        modifier = GlanceModifier.fillMaxWidth().tile(palette).padding(12.dp)
            .clickable(actionStartActivity(viewConfigIntent(context, appWidgetId))).semantics { testTag = "viewNote" },
        style = TextStyle(color = palette.text, fontSize = (12f * m.spFactor).sp),
    )
}

@Composable
private fun ViewBody(board: ViewBoard, state: ViewWidgetState, m: ViewMetrics, palette: WidgetPalette, staticRows: Boolean) {
    ViewStatusLine(board, state, m, palette)
    if (board.rows.isEmpty()) {
        ViewEmpty(board, m, palette)
        return
    }
    val width = LocalSize.current.width
    val columns = tableColumnCount(width, board.columns.size)
    val tableColumns = columns.takeIf { width >= TABLE_MIN_WIDTH && it > 1 }
    if (tableColumns != null) ViewTableHeader(board.columns.take(tableColumns), m, palette)
    ViewRows(board, tableColumns, ViewLook(m, palette), staticRows)
}

/** 빈 보기: 높이가 넉넉하면 아이콘 + 안내 + '새 항목 만들기'(만들 수 있는 보기), 낮으면 글자만(작은 위젯에서 잘리지 않게). */
@Composable
private fun ViewEmpty(board: ViewBoard, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val message = context.getString(R.string.view_widget_empty)
    val fontSp = 12f * m.spFactor
    if (LocalSize.current.height < EMPTY_CARD_MIN_HEIGHT) {
        Text(message, style = TextStyle(color = palette.subtle, fontSize = fontSp.sp))
        return
    }
    val canAdd = board.canCreate
    WidgetEmpty(
        R.drawable.ic_widget_checklist, message, palette, fontSp,
        ctaText = if (canAdd) context.getString(R.string.view_widget_empty_cta) else null,
        ctaAction = if (canAdd) actionStartActivity(viewAddIntent(context, board.viewId)) else null,
        ctaTag = "viewEmpty",
    )
}

private val EMPTY_CARD_MIN_HEIGHT = 170.dp

/** 정상이면 나오지 않는다. 노션에서 보기를 찾을 수 없을 때만 눌러서 설정을 연다(다른 오류는 머리를 눌러 새로고침). */
@Composable
private fun ViewStatusLine(board: ViewBoard, state: ViewWidgetState, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val text = viewStatusText(context, board, state.nowMillis) ?: return
    val base = GlanceModifier.fillMaxWidth().padding(vertical = 2.dp)
    val modifier = if (board.notFound) base.clickable(actionStartActivity(viewConfigIntent(context, state.config.appWidgetId))) else base
    WidgetStatusLine(
        text, error = board.notFound || board.lastError != null, palette = palette, fontSp = m.subSp * m.spFactor,
        modifier = modifier.semantics { testTag = "viewStatus" },
    )
}

@Composable
private fun ViewTableHeader(names: List<String>, m: ViewMetrics, palette: WidgetPalette) {
    Row(GlanceModifier.fillMaxWidth().height(m.tableHeader).padding(horizontal = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        names.forEach { name ->
            Text(
                name,
                modifier = GlanceModifier.defaultWeight(),
                style = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp, fontWeight = FontWeight.Bold),
                maxLines = 1,
            )
        }
    }
}

/** VW-03 노션 보기 위젯. Room 스냅샷만 읽는다(WG-02). */
class ViewWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 180.dp)))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.viewState()
        provideContent { ViewWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initialBoard = initialConfig.viewId?.let { deps.views().observe(it).first() }
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val board by remember(config.viewId) { config.viewId?.let { deps.views().observe(it) } ?: flowOf(null) }
                .collectAsState(initialBoard)
            val fontScale by SystemFontScale.flow.collectAsState()
            ViewWidgetContent(ViewWidgetState(board, config, System.currentTimeMillis()), fontScale ?: SystemFontScale.current(context))
        }
    }
}
