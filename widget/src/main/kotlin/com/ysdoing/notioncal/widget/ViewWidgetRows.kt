package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.Action
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.itemsIndexed
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
import androidx.glance.text.TextDecoration
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

// 노션 보기 위젯의 행·칸(2026-11-03 시안: 톤 타일 행, 원형 체크, 상태·날짜 알약). ViewWidget.kt가 500줄을 넘지 않게 나눴다.

/** 설정 화면 미리보기에 그리는 최대 행 수. Glance 한 컨테이너의 자식은 10개 이하여야 한다. */
internal const val PREVIEW_ROWS = 8

/**
 * 행 타일 위아래 틈. 눌러 바꿀 칸이 있는 행은 타일을 [TAP_ROW_MIN] 이상으로 높인다. 밀도 우선(사용자 결정 2026-11-03)이라
 * 눌림 기준 48dp(WL-4.2)는 채우지 않고 이전(32dp)보다 조금만 높인다. 눌림 폭은 48dp다.
 */
internal val ROW_GAP = 2.dp
internal val TAP_ROW_MIN = 34.dp
private val CHECK_SIZE = 24.dp

/** 표에서 체크 열 머리 글자를 원형 체크의 왼쪽 가장자리에 맞추는 들여쓰기(눌림 폭 48dp 안에서 원이 가운데라 생긴다). */
internal val CHECK_INSET = (TAP_TARGET - CHECK_SIZE) / 2
private val TILE_PADDING_H = 6.dp

/** 행·칸을 그릴 때 쓰는 글자 모양과 색. */
internal class ViewLook(
    val m: ViewMetrics,
    val palette: WidgetPalette,
    val numberStep: Double = 1.0,
    val stepNumbers: Boolean = false,
    private val leaving: Boolean = false,
) {
    // IX-06 필터에서 빠지는 행은 연한 색 + 취소선이다(색만이 아니라 취소선으로도 구분, A11Y-03).
    private val text = if (leaving) palette.subtle else palette.text
    private val strike = if (leaving) TextDecoration.LineThrough else null
    val title = TextStyle(color = text, fontSize = (m.titleSp * m.spFactor).sp, fontWeight = FontWeight.Medium, textDecoration = strike)
    val cell = TextStyle(color = text, fontSize = (m.titleSp * m.spFactor).sp, textDecoration = strike)
    val sub = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp, textDecoration = strike)
    val pillSp = m.subSp * m.spFactor

    /** 같은 설정의 '빠지는 중' 모양. */
    fun asLeaving() = ViewLook(m, palette, numberStep, stepNumbers, leaving = true)
}

internal enum class StatusTone { NEUTRAL, ACTIVE, DONE }

/** status 선택지의 그룹으로 알약 색을 정한다. 노션 기본 그룹 이름(영문)과 한글 이름을 모두 받는다. 그룹이 없는 select는 NEUTRAL. */
internal fun ViewColumn.toneOf(value: String): StatusTone =
    when (options.firstOrNull { it.name == value }?.group?.lowercase()) {
        "complete", "completed", "done", "완료" -> StatusTone.DONE
        "in progress", "진행 중", "진행중" -> StatusTone.ACTIVE
        else -> StatusTone.NEUTRAL
    }

private fun ViewBoard.columnAt(index: Int): ViewColumn? = columns.getOrNull(index)?.let { name -> columnInfo.firstOrNull { it.name == name } }

/** 머리 부제 값. [label]이 null이면 '완료'(상태 열의 완료 그룹), 아니면 체크박스 열 이름이다. */
internal data class ViewProgress(val label: String?, val done: Int, val total: Int)

/**
 * 머리 부제 '완료 n/m'의 값. 상태 열에 완료 그룹이 있으면 그 그룹의 행 수를 센다(체크박스보다 뜻이 확실하다).
 * 없으면 첫 체크박스 열의 체크 수를 열 이름과 함께 쓴다('보관 3/10'처럼, 무엇을 센 값인지 거짓말하지 않는다).
 * 진행이라는 개념이 없는 보기(체크·상태 열 없음)나 빈 보기는 null.
 */
internal fun viewProgress(board: ViewBoard): ViewProgress? {
    if (board.rows.isEmpty()) return null
    val indices = board.columns.indices
    indices.firstOrNull { i -> board.columnAt(i)?.let { it.type == "status" && it.hasCompleteGroup() } == true }?.let { i ->
        val column = board.columnAt(i) ?: return null
        return ViewProgress(null, board.rows.count { column.toneOf(it.cells.getOrNull(i).orEmpty()) == StatusTone.DONE }, board.rows.size)
    }
    val i = indices.firstOrNull { board.columnAt(it)?.type == "checkbox" } ?: return null
    return ViewProgress(board.columnAt(i)?.name, board.rows.count { it.cells.getOrNull(i).orEmpty().isNotEmpty() }, board.rows.size)
}

private fun ViewColumn.hasCompleteGroup(): Boolean = options.any { toneOf(it.name) == StatusTone.DONE }

/** 행을 누르면 그 노션 페이지를 연다. 주소가 없으면 누를 수 없다. 책 위젯 표지가 쓴다(표지 탭은 노션 열기, 책은 ±가 주 동작). */
internal fun rowAction(row: ViewRow): Action? = row.url.takeIf { it.isNotBlank() }?.let {
    actionStartActivity(Intent(Intent.ACTION_VIEW, it.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** 행을 누르면 그 행의 편집 시트를 연다(ED-01). 주소가 없는 행도 고칠 수 있다. */
internal fun rowSheetAction(context: Context, viewId: String, row: ViewRow): Action = actionStartActivity(viewRowIntent(context, viewId, row.pageId))

/**
 * [tableColumns]가 null이면 목록(제목 + 둘째 줄), 아니면 그 열 수의 표. 크기 단계의 밀도표대로 [cap]행까지만 그리고, 넘으면(또는 노션에 행이 더 있으면)
 * 마지막에 '+N 더 보기'를 둔다(VR-02). [staticRows]면 목록 대신 고정 Column이다. [paneHeight]는 탭·섹션 한 칸의 높이다(단일 위젯은 null).
 */
@Composable
internal fun ViewRows(board: ViewBoard, tableColumns: Int?, cap: Int, look: ViewLook, staticRows: Boolean, paneHeight: Dp? = null) {
    // 눌러 바꿀 칸이 보이면(보이지 않는 그룹·필터 열은 세지 않는다) 행을 [TAP_ROW_MIN] 이상으로 높인다. 숫자 − + 는 표에서만 그려진다. 보드 단위로 한 번만 정한다.
    val tall = board.columns.indices.any { board.tappableAt(it) != null } ||
        (tableColumns != null && look.stepNumbers && board.columns.indices.any { board.columnAt(it)?.type == "number" })
    // 탭·섹션의 한 칸이면 그 칸의 높이에 들어가는 행 수로도 줄인다(낮은 칸에서 행이 잘리지 않게, 나머지는 '더 보기').
    val shown = board.rows.take(paneHeight?.let { minOf(cap, paneRowLimit(it, look.m, tableColumns != null, tall)) } ?: cap)
    if (staticRows) {
        Column(GlanceModifier.fillMaxSize()) { shown.take(PREVIEW_ROWS).forEach { ViewRowItem(board, it, tableColumns, look, tall) } }
        return
    }
    val more = moreOf(board.rows.size, shown.size, board.rowsComplete)
    LazyColumn(GlanceModifier.fillMaxSize()) {
        itemsIndexed(shown, itemId = { _, row -> row.pageId.hashCode().toLong() }) { _, row -> ViewRowItem(board, row, tableColumns, look, tall) }
        if (more != null) item(itemId = MORE_ITEM_ID) { MoreLine(moreText(LocalContext.current, more), board.viewId, look) }
    }
}

private const val MORE_ITEM_ID = -1L

/** '+7 더 보기'(정확) / '92+ 더 보기'(노션에 더 있을 수 있음) / '더 보기'(몇 개인지 모름). */
internal fun moreText(context: Context, more: More): String = when {
    more.exact -> context.getString(R.string.view_widget_more, more.remaining)
    more.remaining > 0 -> context.getString(R.string.view_widget_more_plus, more.remaining)
    else -> context.getString(R.string.view_widget_more_unknown)
}

@Composable
private fun ViewRowItem(board: ViewBoard, row: ViewRow, tableColumns: Int?, look: ViewLook, tall: Boolean) {
    val base = if (tableColumns != null) look.m.tableRow else look.m.listRow
    val height = if (tall) maxOf(base, TAP_ROW_MIN) else base
    val leaving = board.isLeaving(row)
    val rowLook = if (leaving) look.asLeaving() else look
    val tile = rowModifier(board.viewId, row, height, look.palette, if (leaving) "viewRowLeaving" else "viewRow")
    Box(GlanceModifier.fillMaxWidth().padding(vertical = ROW_GAP)) {
        if (tableColumns != null) ViewTableRow(board, row, tableColumns, tile, height, rowLook) else ViewListRow(board, row, tile, height, rowLook)
    }
}

@Composable
private fun rowModifier(viewId: String, row: ViewRow, height: Dp, palette: WidgetPalette, tag: String): GlanceModifier {
    val base = GlanceModifier.fillMaxWidth().height(height).tile(palette)
    return base.clickable(rowSheetAction(LocalContext.current, viewId, row)).padding(horizontal = TILE_PADDING_H).semantics { testTag = tag }
}

@Composable
private fun ViewTableRow(board: ViewBoard, row: ViewRow, columns: Int, modifier: GlanceModifier, height: Dp, look: ViewLook) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        for (i in 0 until columns) ViewCell(board, row, i, look, GlanceModifier.defaultWeight(), height)
    }
}

/**
 * 목록: 눌러 바꿀 체크박스 열이 있으면 맨 앞에 두고, 제목 아래 둘째 줄은 제목·체크박스 다음 열이다(그 열도 눌러 바꿀 수 있으면 눌러진다).
 * 제목과 체크박스뿐인 보기는 둘째 줄이 없다.
 */
@Composable
private fun ViewListRow(board: ViewBoard, row: ViewRow, modifier: GlanceModifier, height: Dp, look: ViewLook) {
    val check = board.columns.indices.firstOrNull { board.tappableAt(it)?.type == "checkbox" }
    val second = board.columns.indices.firstOrNull { it != 0 && it != check }
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        if (check != null) ViewCell(board, row, check, look, GlanceModifier.width(TAP_TARGET), height) else Spacer(GlanceModifier.width(4.dp))
        Column(GlanceModifier.defaultWeight(), verticalAlignment = Alignment.CenterVertically) {
            Text(row.cells.firstOrNull().orEmpty(), style = look.title, maxLines = 1)
            if (second != null) ViewCell(board, row, second, look, GlanceModifier.fillMaxWidth(), height / 2, secondLine = true)
        }
    }
}

/**
 * 칸 하나. 눌러 바꿀 체크·상태·선택 열이면 [tapHeight] 높이의 눌림 영역이고(체크는 원형, 상태·선택은 알약),
 * 날짜는 알약, 나머지는 글자만이다(행을 누르면 노션 페이지).
 */
@Composable
private fun ViewCell(board: ViewBoard, row: ViewRow, index: Int, look: ViewLook, modifier: GlanceModifier, tapHeight: Dp, secondLine: Boolean = false) {
    val raw = row.cells.getOrElse(index) { "" }
    val tappable = board.tappableAt(index)
    val column = board.columnAt(index)
    when {
        tappable != null -> TapCell(board.viewId, row, tappable, raw, look, modifier, tapHeight)
        column?.type == "multi_select" && raw.isNotEmpty() -> MultiChips(column, row, raw, look, modifier)
        column?.type == "number" && look.stepNumbers && !secondLine && column.writable -> NumberStepCell(board.viewId, row, column, raw, look, modifier, tapHeight)
        board.columnAt(index)?.type == "date" && raw.isNotEmpty() ->
            Box(modifier, contentAlignment = Alignment.CenterStart) { WidgetPill(raw, look.palette.tileHigh, look.palette.text, look.pillSp) }
        else -> Text(raw, modifier = modifier, style = if (secondLine) look.sub else if (index == 0) look.title else look.cell, maxLines = 1)
    }
}

@Composable
private fun TapCell(viewId: String, row: ViewRow, column: ViewColumn, raw: String, look: ViewLook, modifier: GlanceModifier, tapHeight: Dp) {
    val description = tapDescription(LocalContext.current, row.cells.firstOrNull().orEmpty(), column, raw)
    val context = LocalContext.current
    val tap = if (column.opensChooser) {
        actionStartActivity(viewChoiceIntent(context, viewId, row.pageId, column.id)) // 선택지가 많은 select는 순환하지 않고 선택 시트(IX-02)
    } else {
        actionRunCallback<ViewCellAction>(
            actionParametersOf(
                ViewCellAction.ViewKey to viewId, ViewCellAction.PageKey to row.pageId,
                ViewCellAction.PropKey to column.id, ViewCellAction.TypeKey to column.type,
            ),
        )
    }
    if (column.type == "checkbox") {
        Box(modifier, contentAlignment = Alignment.CenterStart) {
            WidgetCheck(raw.isNotEmpty(), description, "viewCellTap", tap, look.palette, CHECK_SIZE, tapHeight)
        }
        return
    }
    Box(
        modifier.height(tapHeight).clickable(tap).semantics {
            contentDescription = description
            testTag = "viewCellTap"
        },
        contentAlignment = Alignment.CenterStart,
    ) { OptionPill(column, raw, look) }
}

/**
 * 상태·선택 알약. select는 노션 옵션 색을 따르고(기본 색이면 중립), status는 그룹으로 꾸민다. 구분은 알약 안의 글자가 한다(A11Y-03).
 */
@Composable
private fun OptionPill(column: ViewColumn, raw: String, look: ViewLook) {
    val p = look.palette
    val colored = if (column.type == "select") column.options.firstOrNull { it.name == raw }?.color?.let(::notionPill) else null
    val (background, foreground) = colored ?: when (column.toneOf(raw)) {
        StatusTone.NEUTRAL -> p.tileHigh to p.text
        StatusTone.ACTIVE -> p.accentContainer to p.onAccentContainer
        StatusTone.DONE -> p.greenContainer to p.onGreenContainer
    }
    WidgetPill(raw.ifEmpty { "–" }, background, foreground, look.pillSp)
}

/** 노션 옵션 색 이름 → 알약 면과 글자색(`NotionColors.onColor`로 대비를 보장). 기본 색·모르는 이름은 null. */
private fun notionPill(color: String): Pair<ColorProvider, ColorProvider>? {
    val hex = NotionColors.hexFor(color)?.takeIf { color != "default" } ?: return null
    return ColorProvider(Color(NotionColors.argb(hex))) to ColorProvider(Color(NotionColors.onColor(hex)))
}

/** 다중 선택: 칩 최대 3개 + 남은 수('+2'). 칩 색은 옵션 색. 원래 값(옵션 ID)을 모르면 표시 글자를 ', '로 나눈다. */
@Composable
private fun MultiChips(column: ViewColumn, row: ViewRow, raw: String, look: ViewLook, modifier: GlanceModifier) {
    val ids = (row.typed[column.id] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull }
    val options = ids?.map { id -> column.options.firstOrNull { it.id == id } ?: ViewChoice(id, id) }
        ?: raw.split(", ").map { name -> column.options.firstOrNull { it.name == name } ?: ViewChoice(name, name) }
    val p = look.palette
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        options.take(MAX_CHIPS).forEach { option ->
            val (background, foreground) = option.color?.let(::notionPill) ?: (p.tileHigh to p.text)
            WidgetPill(option.name, background, foreground, look.pillSp)
            Spacer(GlanceModifier.width(3.dp))
        }
        if (options.size > MAX_CHIPS) Text("+${options.size - MAX_CHIPS}", style = look.sub)
    }
}

private const val MAX_CHIPS = 3

/** 눌러 바꾸는 칸의 낭독: 행 제목, 열 이름, 지금 값, 누르면 어떻게 되는지. */
internal fun tapDescription(context: Context, title: String, column: ViewColumn, value: String): String = when {
    column.type == "checkbox" && value.isNotEmpty() -> context.getString(R.string.view_widget_cell_checked, title, column.name)
    column.type == "checkbox" -> context.getString(R.string.view_widget_cell_unchecked, title, column.name)
    column.opensChooser -> context.getString(R.string.view_widget_cell_choose, title, column.name, value.ifEmpty { context.getString(R.string.view_widget_cell_none) })
    else -> context.getString(R.string.view_widget_cell_option, title, column.name, value.ifEmpty { context.getString(R.string.view_widget_cell_none) })
}
