package com.ysdoing.notioncal.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
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
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.withContext
import kotlin.math.ceil
import kotlin.math.floor

/** M5a 책 갤러리 위젯이 그릴 것. [covers]는 받아 둔 표지 파일에서 읽은 비트맵 묶음(없으면 제목 타일). */
internal data class BookWidgetState(val board: ViewBoard?, val config: WidgetConfig, val covers: Covers, val nowMillis: Long)

/** 표지는 가로:세로 = 2:3. */
private const val COVER_RATIO = 1.5f
private val MIN_TILE_WIDTH = 88.dp
private val MAX_COVER_WIDTH = 110.dp
private val MIN_COVER_HEIGHT = 56.dp
private val TILE_GAP = 8.dp
private val BAR_HEIGHT = 4.dp

/** 표지 아래 글자: 진행 막대와 제목(FULL), 막대만(BAR), 없음(NONE). 칸이 낮으면 표지가 잘리지 않게 글자부터 뺀다. */
internal enum class BookCaption { FULL, BAR, NONE }

/**
 * 책 타일 배치. 열 수는 폭으로, 줄 수는 높이로 정하되 책 수보다 크게 잡지 않는다(두 권이면 두 칸을 크게).
 * 표지는 2:3 비율로 칸에 맞추고 [MAX_COVER_WIDTH]를 넘기지 않는다(받아 둔 이미지가 240×360이라 더 키우면 흐려진다).
 * 줄 높이가 모자라면 표지를 줄이는 대신 아래 글자를 뺀다. 그래서 타일 한 줄은 몸통 높이를 넘지 않는다.
 */
internal data class BookLayout(val columns: Int, val rows: Int, val tileWidth: Dp, val coverWidth: Dp, val coverHeight: Dp, val caption: BookCaption) {
    val capacity: Int get() = columns * rows

    /** 타일 한 칸(표지 + 글자)의 높이. */
    fun tileHeight(textLine: Dp): Dp = coverHeight + captionHeight(caption, textLine)
}

private fun captionHeight(caption: BookCaption, textLine: Dp): Dp = when (caption) {
    BookCaption.FULL -> textLine + BAR_HEIGHT + 8.dp // 진행 막대·제목 줄과 그 위 여백
    BookCaption.BAR -> BAR_HEIGHT + 4.dp
    BookCaption.NONE -> 0.dp
}

/** 몸통([bodyWidth]×[bodyHeight])에 [books]권을 놓는 방법. [textLine]은 제목 한 줄 높이. */
internal fun bookLayout(bodyWidth: Dp, bodyHeight: Dp, textLine: Dp, books: Int): BookLayout {
    val count = books.coerceIn(1, CoverBudget.MAX_COVERS)
    val maxColumns = ((bodyWidth + TILE_GAP) / (MIN_TILE_WIDTH + TILE_GAP)).toInt().coerceIn(1, 3)
    val columns = minOf(maxColumns, count)
    val tileWidth = (bodyWidth - TILE_GAP * (columns - 1)) / columns
    // 줄 하나가 차지하는 높이(표지가 칸 폭에 맞는 높이, 단 최대 폭 기준)를 기준으로 몇 줄이 들어가는지.
    val idealRow = minOf(tileWidth, MAX_COVER_WIDTH) * COVER_RATIO + captionHeight(BookCaption.FULL, textLine)
    val fit = floor((bodyHeight + TILE_GAP) / (idealRow + TILE_GAP)).toInt().coerceAtLeast(1)
    val rows = minOf(ceil(count / columns.toFloat()).toInt(), fit).coerceIn(1, 3)
    val rowHeight = (bodyHeight - TILE_GAP * (rows - 1)) / rows
    val caption = listOf(BookCaption.FULL, BookCaption.BAR).firstOrNull { rowHeight - captionHeight(it, textLine) >= MIN_COVER_HEIGHT } ?: BookCaption.NONE
    val coverHeight = (rowHeight - captionHeight(caption, textLine)).coerceAtLeast(0.dp)
    val coverWidth = minOf(tileWidth, MAX_COVER_WIDTH, coverHeight / COVER_RATIO)
    return BookLayout(columns, rows, tileWidth, coverWidth, coverWidth * COVER_RATIO, caption)
}

/** 책 갤러리 위젯(M5a): 읽는 중인 책의 표지와 읽은 비율 막대. Room 스냅샷과 받아 둔 표지 파일만 읽는다(WG-02). */
class BookWidget : GlanceAppWidget() {
    override val sizeMode: SizeMode = SizeMode.Exact
    override val previewSizeMode = SizeMode.Responsive(setOf(DpSize(180.dp, 180.dp)))

    override suspend fun providePreview(context: Context, widgetCategory: Int) {
        val state = WidgetPreviews.bookState()
        provideContent { BookWidgetContent(state, systemFontScale = 1f) }
    }

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val appWidgetId = GlanceAppWidgetManager(context).getAppWidgetId(id)
        val deps = WidgetDependencies.from(context)
        val initialConfig = deps.widgetConfigs().get(appWidgetId)
        val initialBoard = initialConfig.viewId?.let { deps.views().observe(it).first() }
        val initialCovers = loadCovers(deps, initialBoard)
        val initialKey = coverKey(deps, initialBoard)
        provideContent {
            val config by remember { deps.widgetConfigs().observe(appWidgetId) }.collectAsState(initialConfig)
            val board by remember(config.viewId) { config.viewId?.let { deps.views().observe(it) } ?: flowOf(null) }
                .collectAsState(initialBoard)
            // 행이 그대로여도 표지 파일만 나중에 받아질 수 있다(동기화가 행을 쓴 뒤 표지를 받는다). 파일 상태가 바뀌면 다시 읽는다.
            // 처음 한 번은 위에서 읽은 것을 그대로 쓰고(같은 JPEG를 두 번 디코딩하지 않는다), 열쇠가 바뀔 때만 읽는다.
            val key = coverKey(deps, board)
            var loadedKey by remember { mutableStateOf(initialKey) }
            var covers by remember { mutableStateOf(initialCovers) }
            LaunchedEffect(key) {
                if (key != loadedKey) {
                    covers = loadCovers(deps, board)
                    loadedKey = key
                }
            }
            val fontScale by SystemFontScale.flow.collectAsState()
            BookWidgetContent(BookWidgetState(board, config, covers, System.currentTimeMillis()), fontScale ?: SystemFontScale.current(context))
        }
    }

    /** 보기 행(주소 포함)과 받아 둔 표지 파일의 상태. 둘 중 하나라도 바뀌면 표지를 다시 읽는다. */
    private fun coverKey(deps: WidgetDependencies, board: ViewBoard?): Pair<List<ViewRow>?, Long> {
        val rows = board?.rows?.take(CoverBudget.MAX_COVERS)
        return rows to (rows?.let { deps.covers().stamp(it.map(ViewRow::coverUrl)) } ?: 0L)
    }

    /** 파일 읽기·디코딩은 IO 스레드에서. */
    private suspend fun loadCovers(deps: WidgetDependencies, board: ViewBoard?): Covers =
        if (board == null) Covers.EMPTY else withContext(Dispatchers.IO) { Covers.load(deps.covers(), board.rows) }
}

@Composable
internal fun BookWidgetContent(
    state: BookWidgetState,
    systemFontScale: Float = LocalContext.current.applicationContext.resources.configuration.fontScale,
) {
    val context = LocalContext.current
    val palette = WidgetPalette.of(state.config.theme, state.config.opacity, context)
    val m = viewMetrics(state.config.fontScale / 100f, systemFontScale)
    val board = state.board
    Column(
        GlanceModifier.fillMaxSize().appWidgetBackground().cornerRadius(android.R.dimen.system_app_widget_background_radius)
            .background(palette.background).padding(horizontal = PADDING_H, vertical = VIEW_PADDING_V),
    ) {
        BookHeader(board, m, palette)
        if (board == null) {
            val message = if (state.config.viewId == null) R.string.view_widget_choose else R.string.view_widget_missing
            ViewNote(message, state.config.appWidgetId, m, palette, tag = "bookNote")
        } else {
            BookBody(board, state, m, palette)
        }
    }
}

/** 머리(공통 [WidgetHeader]): 책 아이콘 + 보기 이름(누르면 새로고침) + 새로고침 버튼(폭 200dp 이상). */
@Composable
private fun BookHeader(board: ViewBoard?, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val title = board?.name?.takeIf { it.isNotBlank() } ?: context.getString(R.string.book_widget_label)
    val description = context.getString(R.string.view_widget_header_description, title)
    WidgetHeader(
        icon = R.drawable.ic_widget_book,
        title = title,
        subtitle = null,
        palette = palette,
        height = m.header,
        titleSp = m.headerSp * m.spFactor,
        subSp = m.subSp * m.spFactor,
        titleModifier = GlanceModifier.clickable(actionRunCallback<RefreshAction>()).semantics {
            contentDescription = description
            testTag = "bookHeader"
        },
    ) {
        if (board != null && LocalSize.current.width >= REFRESH_MIN_WIDTH) {
            WidgetIconButton(
                R.drawable.ic_widget_refresh, context.getString(R.string.widget_refresh), "bookRefresh", actionRunCallback<RefreshAction>(), palette, height = m.header,
            )
        }
    }
}

@Composable
private fun BookBody(board: ViewBoard, state: BookWidgetState, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val status = viewStatusText(context, board, state.nowMillis)
    ViewStatusLine(board, state.config.appWidgetId, state.nowMillis, m, palette, tag = "bookStatus")
    val books = board.rows.take(CoverBudget.MAX_COVERS)
    if (books.isEmpty()) {
        WidgetEmpty(R.drawable.ic_widget_book, context.getString(R.string.book_widget_empty), palette, 12f * m.spFactor, ctaTag = "bookEmpty")
        return
    }
    val size = LocalSize.current
    val textLine = (m.titleSp * m.spFactor * WidgetMetrics.TEXT_BOX).dp
    val statusHeight = if (status != null) (m.subSp * m.spFactor * WidgetMetrics.TEXT_BOX).dp + 4.dp else 0.dp
    val bodyHeight = (size.height - VIEW_PADDING_V * 2 - m.header - statusHeight).coerceAtLeast(0.dp)
    val layout = bookLayout(size.width - PADDING_H * 2, bodyHeight, textLine, books.size)
    val shown = books.take(layout.capacity)
    Column(GlanceModifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically, horizontalAlignment = Alignment.CenterHorizontally) {
        shown.chunked(layout.columns).forEachIndexed { index, rowBooks ->
            if (index > 0) Spacer(GlanceModifier.height(TILE_GAP))
            Row(verticalAlignment = Alignment.Top) {
                rowBooks.forEachIndexed { i, book ->
                    if (i > 0) Spacer(GlanceModifier.width(TILE_GAP))
                    BookTile(book, layout, state.covers, m, palette)
                }
            }
        }
    }
}

/** 책 한 칸: 표지(없으면 제목 타일) + 읽은 비율 막대 + 제목 한 줄. 표지를 누르면 그 노션 페이지. */
@Composable
private fun BookTile(book: ViewRow, layout: BookLayout, covers: Covers, m: ViewMetrics, palette: WidgetPalette) {
    val context = LocalContext.current
    val title = book.cells.firstOrNull().orEmpty().ifBlank { context.getString(R.string.widget_config_view_untitled) }
    val percent = book.progress?.let { (it * 100f + 0.001f).toInt().coerceIn(0, 100) } // 0.53f*100 = 52.999996: 그냥 내림하면 한 칸 모자라게 읽힌다
    val description = if (percent != null) context.getString(R.string.book_widget_tile_progress, title, percent) else title
    val open = rowAction(book)
    val tap = open?.let { GlanceModifier.clickable(it) } ?: GlanceModifier
    Column(GlanceModifier.width(layout.tileWidth), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(tap.size(layout.coverWidth, layout.coverHeight).semantics { testTag = "bookTile"; contentDescription = description }, contentAlignment = Alignment.Center) {
            if (covers.has(book.pageId)) {
                covers.Draw(book.pageId, layout.coverWidth, layout.coverHeight, WidgetShape.TILE)
            } else {
                Box(GlanceModifier.size(layout.coverWidth, layout.coverHeight).tile(palette).padding(6.dp).semantics { testTag = "bookNoCover" }, contentAlignment = Alignment.Center) {
                    Text(title, style = TextStyle(color = palette.text, fontSize = (m.titleSp * m.spFactor).sp, textAlign = TextAlign.Center), maxLines = 5)
                }
            }
        }
        if (layout.caption != BookCaption.NONE) {
            Spacer(GlanceModifier.height(4.dp))
            ProgressBar(book.progress, layout.coverWidth, palette)
        }
        if (layout.caption == BookCaption.FULL) {
            Text(
                title,
                modifier = GlanceModifier.width(layout.coverWidth).padding(top = 4.dp),
                style = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp, textAlign = TextAlign.Center),
                maxLines = 1,
            )
        }
    }
}

/** 읽은 비율 막대: 칸 폭의 트랙 위에 채움. 비율을 모르면(null) 그리지 않는다. 진행 중이면 최소 폭을 둬 보이게 한다. */
@Composable
private fun ProgressBar(progress: Float?, width: Dp, palette: WidgetPalette) {
    if (progress == null) return
    Box(GlanceModifier.size(width, BAR_HEIGHT).background(palette.tileHigh).cornerRadius(BAR_HEIGHT / 2).semantics { testTag = "bookProgress" }) {
        if (progress > 0f) Box(GlanceModifier.size(maxOf(width * progress, BAR_HEIGHT), BAR_HEIGHT).background(palette.green).cornerRadius(BAR_HEIGHT / 2)) {}
    }
}
