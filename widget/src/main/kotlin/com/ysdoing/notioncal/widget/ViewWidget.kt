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
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.appWidgetBackground
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Box
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
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.AppIntents
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
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
    val header: Dp,
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
        header = maxOf(32.dp, box(headerSp) + 8.dp),
        tableHeader = box(subSp) + 4.dp,
        listRow = box(titleSp) + box(subSp) + 6.dp,
        tableRow = box(titleSp) + 6.dp,
    )
}

/** 행을 누르면 그 노션 페이지를 연다. 주소가 없으면 누를 수 없다. */
private fun rowAction(row: ViewRow): Action? = row.url.takeIf { it.isNotBlank() }?.let {
    actionStartActivity(Intent(Intent.ACTION_VIEW, it.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
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

/** 머리: 보기 이름(누르면 새로고침)과, 새 항목을 만들 수 있는 보기(제목 열 정보가 있음)면 오른쪽 '+'. */
@Composable
private fun ViewHeader(board: ViewBoard?, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    // 이름 없는 보기도 노션에서 허용된다. 접근성 설명이 글자를 대신하므로 보기 이름을 함께 읽게 한다.
    val title = board?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.view_widget_label)
    val description = context.getString(R.string.view_widget_header_description, title)
    val canAdd = board?.canCreate == true
    Row(GlanceModifier.fillMaxWidth().height(m.header), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title,
            modifier = GlanceModifier.defaultWeight().height(m.header).clickable(actionRunCallback<RefreshAction>()).semantics {
                contentDescription = description
                testTag = "viewHeader"
            },
            style = TextStyle(color = palette.text, fontSize = (m.headerSp * m.spFactor).sp, fontWeight = FontWeight.Bold),
            maxLines = 1,
        )
        if (canAdd && board != null) {
            val add = context.getString(R.string.view_widget_add_description, title)
            Box(
                GlanceModifier.width(ADD_WIDTH).height(m.header).clickable(actionStartActivity(viewAddIntent(context, board.viewId))).semantics {
                    contentDescription = add
                    testTag = "viewAdd"
                },
                contentAlignment = Alignment.CenterEnd,
            ) {
                WidgetIcon(R.drawable.ic_widget_add, palette.accent, 22.dp)
            }
        }
    }
}

/** 머리 '+' 버튼 너비(누르기 쉬운 폭). */
private val ADD_WIDTH = 40.dp

/** 고르지 않았거나 등록이 지워진 보기: 글자로 알리고, 누르면 위젯 설정에서 다시 고른다. */
@Composable
private fun ViewNote(message: Int, appWidgetId: Int, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    Text(
        context.getString(message),
        modifier = GlanceModifier.fillMaxWidth().clickable(actionStartActivity(viewConfigIntent(context, appWidgetId))).semantics { testTag = "viewNote" },
        style = TextStyle(color = palette.subtle, fontSize = (12f * m.spFactor).sp),
    )
}

@Composable
private fun ViewBody(board: ViewBoard, state: ViewWidgetState, m: ViewMetrics, palette: WidgetPalette, staticRows: Boolean) {
    ViewStatusLine(board, state, m, palette)
    if (board.rows.isEmpty()) {
        Text(LocalContext.current.getString(R.string.view_widget_empty), style = TextStyle(color = palette.subtle, fontSize = (12f * m.spFactor).sp))
        return
    }
    val width = LocalSize.current.width
    val columns = tableColumnCount(width, board.columns.size)
    val tableColumns = columns.takeIf { width >= TABLE_MIN_WIDTH && it > 1 }
    if (tableColumns != null) ViewTableHeader(board.columns.take(tableColumns), m, palette)
    ViewRows(board, tableColumns, m, palette, staticRows)
}

/** 정상이면 나오지 않는다. 노션에서 보기를 찾을 수 없을 때만 눌러서 설정을 연다(다른 오류는 머리를 눌러 새로고침). */
@Composable
private fun ViewStatusLine(board: ViewBoard, state: ViewWidgetState, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val text = viewStatusText(context, board, state.nowMillis) ?: return
    val base = GlanceModifier.fillMaxWidth()
    val modifier = if (board.notFound) base.clickable(actionStartActivity(viewConfigIntent(context, state.config.appWidgetId))) else base
    Text(
        text,
        modifier = modifier.semantics { testTag = "viewStatus" },
        style = TextStyle(color = palette.text, fontSize = (m.subSp * m.spFactor).sp),
        maxLines = 1,
    )
}

@Composable
private fun ViewTableHeader(names: List<String>, m: ViewMetrics, palette: WidgetPalette) {
    Row(GlanceModifier.fillMaxWidth().height(m.tableHeader), verticalAlignment = Alignment.CenterVertically) {
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

/** 설정 화면 미리보기에 그리는 최대 행 수. Glance 한 컨테이너의 자식은 10개 이하여야 한다. */
internal const val PREVIEW_ROWS = 8

/** [tableColumns]가 null이면 목록(제목 + 둘째 줄), 아니면 그 열 수의 표. [staticRows]면 목록 대신 고정 Column이다. */
@Composable
private fun ViewRows(board: ViewBoard, tableColumns: Int?, m: ViewMetrics, palette: WidgetPalette, staticRows: Boolean) {
    if (staticRows) {
        Column(GlanceModifier.fillMaxSize()) { board.rows.take(PREVIEW_ROWS).forEach { ViewRowItem(board, it, tableColumns, m, palette) } }
        return
    }
    LazyColumn(GlanceModifier.fillMaxSize()) {
        itemsIndexed(board.rows, itemId = { _, row -> row.pageId.hashCode().toLong() }) { _, row -> ViewRowItem(board, row, tableColumns, m, palette) }
    }
}

@Composable
private fun ViewRowItem(board: ViewBoard, row: ViewRow, tableColumns: Int?, m: ViewMetrics, palette: WidgetPalette) {
    // 눌러 바꿀 칸이 있는 보기는 행을 TAP_MIN 이상으로 높여 누르기 쉽게 한다.
    val base = if (tableColumns != null) m.tableRow else m.listRow
    val height = if (board.columnInfo.any { it.tappable }) maxOf(base, TAP_MIN) else base
    val modifier = rowModifier(row, height)
    if (tableColumns != null) ViewTableRow(board, row, tableColumns, modifier, height, m, palette) else ViewListRow(board, row, modifier, height, m, palette)
}

private fun rowModifier(row: ViewRow, height: Dp): GlanceModifier {
    val base = GlanceModifier.fillMaxWidth().height(height)
    return (rowAction(row)?.let { base.clickable(it) } ?: base).semantics { testTag = "viewRow" }
}

@Composable
private fun ViewTableRow(board: ViewBoard, row: ViewRow, columns: Int, modifier: GlanceModifier, height: Dp, m: ViewMetrics, palette: WidgetPalette) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until columns) {
            ViewCell(board, row, i, GlanceModifier.defaultWeight(), TextStyle(color = palette.text, fontSize = (m.titleSp * m.spFactor).sp), height)
        }
    }
}

/**
 * 목록: 눌러 바꿀 체크박스 열이 있으면 맨 앞에 두고, 제목 아래 둘째 줄은 제목·체크박스 다음 열이다(그 열도 눌러 바꿀 수 있으면 눌러진다).
 * 제목과 체크박스뿐인 보기는 둘째 줄이 없다.
 */
@Composable
private fun ViewListRow(board: ViewBoard, row: ViewRow, modifier: GlanceModifier, height: Dp, m: ViewMetrics, palette: WidgetPalette) {
    val check = board.columns.indices.firstOrNull { board.tappableAt(it)?.type == "checkbox" }
    val second = board.columns.indices.firstOrNull { it != 0 && it != check }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (check != null) {
            ViewCell(board, row, check, GlanceModifier.width(CHECK_WIDTH), TextStyle(color = palette.text, fontSize = (m.titleSp * m.spFactor).sp), height)
        }
        Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.cells.firstOrNull().orEmpty(), style = TextStyle(color = palette.text, fontSize = (m.titleSp * m.spFactor).sp), maxLines = 1)
            if (second != null) {
                ViewCell(board, row, second, GlanceModifier.fillMaxWidth(), TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp), height / 2)
            }
        }
    }
}

/** 눌러 바꿀 칸의 폭(목록의 체크박스)과, 눌러 바꿀 칸이 있는 행의 최소 높이. */
private val CHECK_WIDTH = 32.dp
private val TAP_MIN = 32.dp

/**
 * 칸 하나. 눌러 바꿀 체크·상태·선택 열이면 [tapHeight] 높이의 눌림 영역이고(글자는 세로 가운데, 체크박스는 ☐·☑),
 * 아니면 글자만이다(행을 누르면 노션 페이지).
 */
@Composable
private fun ViewCell(board: ViewBoard, row: ViewRow, index: Int, modifier: GlanceModifier, style: TextStyle, tapHeight: Dp) {
    val column = board.tappableAt(index)
    val raw = row.cells.getOrElse(index) { "" }
    if (column == null) {
        Text(raw, modifier = modifier, style = style, maxLines = 1)
        return
    }
    val context = LocalContext.current
    val checkbox = column.type == "checkbox"
    val text = if (checkbox) (if (raw.isNotEmpty()) CHECKED else UNCHECKED) else raw
    val description = tapDescription(context, row.cells.firstOrNull().orEmpty(), column, raw)
    val tap = actionRunCallback<ViewCellAction>(
        actionParametersOf(
            ViewCellAction.ViewKey to board.viewId, ViewCellAction.PageKey to row.pageId,
            ViewCellAction.PropKey to column.id, ViewCellAction.TypeKey to column.type,
        ),
    )
    Box(
        modifier.height(tapHeight).clickable(tap).semantics {
            contentDescription = description
            testTag = "viewCellTap"
        },
        contentAlignment = Alignment.CenterStart,
    ) {
        Text(text, style = style, maxLines = 1)
    }
}

private const val CHECKED = "☑"
private const val UNCHECKED = "☐"

/** 눌러 바꾸는 칸의 낭독: 행 제목, 열 이름, 지금 값, 누르면 어떻게 되는지. */
internal fun tapDescription(context: Context, title: String, column: ViewColumn, value: String): String = when {
    column.type == "checkbox" && value.isNotEmpty() -> context.getString(R.string.view_widget_cell_checked, title, column.name)
    column.type == "checkbox" -> context.getString(R.string.view_widget_cell_unchecked, title, column.name)
    else -> context.getString(R.string.view_widget_cell_option, title, column.name, value.ifEmpty { context.getString(R.string.view_widget_cell_none) })
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
