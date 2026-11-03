package com.ysdoing.notioncal.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
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
import androidx.glance.text.TextStyle
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow

// 노션 보기 위젯의 행·칸(2026-11-03 시안: 톤 타일 행, 원형 체크, 상태·날짜 알약). ViewWidget.kt가 500줄을 넘지 않게 나눴다.

/** 설정 화면 미리보기에 그리는 최대 행 수. Glance 한 컨테이너의 자식은 10개 이하여야 한다. */
internal const val PREVIEW_ROWS = 8

/** 행 타일 위아래 틈. 눌러 바꿀 칸이 있는 행은 타일을 [TAP_ROW_MIN] 이상으로 높여(틈 포함 약 48dp) 누르기 쉽게 한다(WL-4.2). */
private val ROW_GAP = 2.dp
internal val TAP_ROW_MIN = 44.dp
private val CHECK_SIZE = 24.dp
private val TILE_PADDING_H = 6.dp

/** 행·칸을 그릴 때 쓰는 글자 모양과 색. */
internal class ViewLook(val m: ViewMetrics, val palette: WidgetPalette) {
    val title = TextStyle(color = palette.text, fontSize = (m.titleSp * m.spFactor).sp, fontWeight = FontWeight.Medium)
    val cell = TextStyle(color = palette.text, fontSize = (m.titleSp * m.spFactor).sp)
    val sub = TextStyle(color = palette.subtle, fontSize = (m.subSp * m.spFactor).sp)
    val pillSp = m.subSp * m.spFactor
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

/**
 * 머리 부제 '완료 n/m'의 값. 체크박스 열이 있으면 체크된 행 수, 없으면 상태 열에서 완료 그룹인 행 수다.
 * 진행이라는 개념이 없는 보기(체크·상태 열 없음)나 빈 보기는 null.
 */
internal fun viewProgress(board: ViewBoard): Pair<Int, Int>? {
    if (board.rows.isEmpty()) return null
    val indices = board.columns.indices
    indices.firstOrNull { board.columnAt(it)?.type == "checkbox" }?.let { i ->
        return board.rows.count { it.cells.getOrNull(i).orEmpty().isNotEmpty() } to board.rows.size
    }
    val i = indices.firstOrNull { board.columnAt(it)?.type == "status" } ?: return null
    val column = board.columnAt(i) ?: return null
    return board.rows.count { column.toneOf(it.cells.getOrNull(i).orEmpty()) == StatusTone.DONE } to board.rows.size
}

/** 행을 누르면 그 노션 페이지를 연다. 주소가 없으면 누를 수 없다. */
private fun rowAction(row: ViewRow): Action? = row.url.takeIf { it.isNotBlank() }?.let {
    actionStartActivity(Intent(Intent.ACTION_VIEW, it.toUri()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

/** [tableColumns]가 null이면 목록(제목 + 둘째 줄), 아니면 그 열 수의 표. [staticRows]면 목록 대신 고정 Column이다. */
@Composable
internal fun ViewRows(board: ViewBoard, tableColumns: Int?, look: ViewLook, staticRows: Boolean) {
    if (staticRows) {
        Column(GlanceModifier.fillMaxSize()) { board.rows.take(PREVIEW_ROWS).forEach { ViewRowItem(board, it, tableColumns, look) } }
        return
    }
    LazyColumn(GlanceModifier.fillMaxSize()) {
        itemsIndexed(board.rows, itemId = { _, row -> row.pageId.hashCode().toLong() }) { _, row -> ViewRowItem(board, row, tableColumns, look) }
    }
}

@Composable
private fun ViewRowItem(board: ViewBoard, row: ViewRow, tableColumns: Int?, look: ViewLook) {
    val base = if (tableColumns != null) look.m.tableRow else look.m.listRow
    val height = if (board.columnInfo.any { it.tappable }) maxOf(base, TAP_ROW_MIN) else base
    val tile = rowModifier(row, height, look.palette)
    Box(GlanceModifier.fillMaxWidth().padding(vertical = ROW_GAP)) {
        if (tableColumns != null) ViewTableRow(board, row, tableColumns, tile, height, look) else ViewListRow(board, row, tile, height, look)
    }
}

private fun rowModifier(row: ViewRow, height: Dp, palette: WidgetPalette): GlanceModifier {
    val base = GlanceModifier.fillMaxWidth().height(height).tile(palette)
    return (rowAction(row)?.let { base.clickable(it) } ?: base).padding(horizontal = TILE_PADDING_H).semantics { testTag = "viewRow" }
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
    when {
        tappable != null -> TapCell(board.viewId, row, tappable, raw, look, modifier, tapHeight)
        board.columnAt(index)?.type == "date" && raw.isNotEmpty() ->
            Box(modifier, contentAlignment = Alignment.CenterStart) { WidgetPill(raw, look.palette.tileHigh, look.palette.text, look.pillSp) }
        else -> Text(raw, modifier = modifier, style = if (secondLine) look.sub else if (index == 0) look.title else look.cell, maxLines = 1)
    }
}

@Composable
private fun TapCell(viewId: String, row: ViewRow, column: ViewColumn, raw: String, look: ViewLook, modifier: GlanceModifier, tapHeight: Dp) {
    val description = tapDescription(LocalContext.current, row.cells.firstOrNull().orEmpty(), column, raw)
    val tap = actionRunCallback<ViewCellAction>(
        actionParametersOf(
            ViewCellAction.ViewKey to viewId, ViewCellAction.PageKey to row.pageId,
            ViewCellAction.PropKey to column.id, ViewCellAction.TypeKey to column.type,
        ),
    )
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

/** 상태·선택 알약. 색은 상태 그룹으로 꾸미기만 하고, 구분은 알약 안의 글자가 한다(A11Y-03). */
@Composable
private fun OptionPill(column: ViewColumn, raw: String, look: ViewLook) {
    val p = look.palette
    val (background, foreground) = when (column.toneOf(raw)) {
        StatusTone.NEUTRAL -> p.tileHigh to p.text
        StatusTone.ACTIVE -> p.accentContainer to p.onAccentContainer
        StatusTone.DONE -> p.greenContainer to p.onGreenContainer
    }
    WidgetPill(raw.ifEmpty { "–" }, background, foreground, look.pillSp)
}

/** 눌러 바꾸는 칸의 낭독: 행 제목, 열 이름, 지금 값, 누르면 어떻게 되는지. */
internal fun tapDescription(context: Context, title: String, column: ViewColumn, value: String): String = when {
    column.type == "checkbox" && value.isNotEmpty() -> context.getString(R.string.view_widget_cell_checked, title, column.name)
    column.type == "checkbox" -> context.getString(R.string.view_widget_cell_unchecked, title, column.name)
    else -> context.getString(R.string.view_widget_cell_option, title, column.name, value.ifEmpty { context.getString(R.string.view_widget_cell_none) })
}
