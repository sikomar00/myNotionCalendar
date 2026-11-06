package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.view.CoverSize
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.GalleryConfig
import com.ysdoing.notioncal.core.data.view.RatingText
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow

// Phase 12d VR-GAL: 갤러리 보기를 표지 카드 격자로 그린다. 이 파일은 순수 계산(격자·캡션)이고 그리기는 ViewGalleryBody.kt다.

/** 한 위젯 갤러리가 그리는 카드 수 상한(3×3). 표지도 이만큼만 받고 읽는다. */
internal const val GALLERY_COVER_CAP = CoverStore.MAX_GALLERY_COVERS

internal val GALLERY_GAP = 6.dp
private const val GALLERY_RATIO = 1.25f // 표지 세로/가로(4:5)
private const val MIN_COVER_RATIO = 0.9f // 한 줄을 더 넣으려고 표지를 낮출 때의 하한(거의 정사각)
private val MIN_GALLERY_COVER = 56.dp
private const val MIN_COLUMNS = 2
private const val MAX_COLUMNS = 4

/** 격자: [columns]열 × [rows]줄 카드. 카드는 폭 [cardWidth], 표지 높이 [coverHeight], 캡션은 제목 한 줄 + 속성 [propLines]줄이다. */
internal data class GalleryLayout(val columns: Int, val rows: Int, val cardWidth: Dp, val coverHeight: Dp, val propLines: Int) {
    val cards: Int get() = columns * rows
}

private fun targetCardWidth(size: CoverSize): Dp = when (size) {
    CoverSize.SMALL -> 84.dp
    CoverSize.MEDIUM -> 112.dp
    CoverSize.LARGE -> 150.dp
}

/** 폭 [bodyWidth]에 노션 카드 크기([size])에 가까운 카드가 들어가는 열 수(2~4). */
internal fun galleryColumns(bodyWidth: Dp, size: CoverSize): Int =
    ((bodyWidth + GALLERY_GAP) / (targetCardWidth(size) + GALLERY_GAP)).toInt().coerceIn(MIN_COLUMNS, MAX_COLUMNS)

/** 카드 캡션 높이: 위 여백 + 제목 한 줄 + 속성 [propLines]줄. */
internal fun galleryCaptionHeight(m: ViewMetrics, propLines: Int): Dp = 4.dp + (m.tableHeader + 2.dp) + m.tableHeader * propLines

/**
 * 갤러리 격자 계획. 2×2(`COMPACT`)이거나 표지 카드 한 줄도 읽히게 못 들어가면 null이고, 호출한 쪽이 표·목록으로 대신 그린다(캘린더·차트와 같은 폴백).
 * 높이가 모자라면 속성 줄(2→1→0)을 먼저 줄이고 그래도 안 되면 표지를 낮추며([MIN_GALLERY_COVER] 아래로는 안 간다), 카드가 [GALLERY_COVER_CAP]를 넘지 않게 줄 수를 자른다.
 * 다 못 보이면(또는 노션에 행이 더 있으면) '더 보기' 줄 자리를 먼저 뺀다.
 */
internal fun galleryLayoutFor(board: ViewBoard, size: DpSize, pane: PaneSpec, m: ViewMetrics, aboveHeight: Dp, config: GalleryConfig): GalleryLayout? {
    val step = sizeStep(size)
    if (step == SizeStep.COMPACT) return null
    val total = pane.height ?: (size.height - VIEW_PADDING_V * 2)
    val body = paneBodyHeight(total, progressOf(board, size) != null, m, aboveHeight)
    val width = size.width - PADDING_H * 2
    val columns = galleryColumns(width, config.size)
    val cardWidth = (width - GALLERY_GAP * (columns - 1)) / columns
    val maxRows = maxOf(1, GALLERY_COVER_CAP / columns)
    val wantedProps = if (step == SizeStep.WIDE) 1 else 2
    for (props in wantedProps downTo 0) {
        galleryAttempt(board, body, columns, cardWidth, maxRows, props, m)?.let { return it }
    }
    return null
}

private fun galleryAttempt(board: ViewBoard, body: Dp, columns: Int, cardWidth: Dp, maxRows: Int, props: Int, m: ViewMetrics): GalleryLayout? {
    val caption = galleryCaptionHeight(m, props)
    val cover = cardWidth * GALLERY_RATIO
    fun rowsFit(available: Dp): Int = minOf(maxRows, ((available + GALLERY_GAP) / (cover + caption + GALLERY_GAP)).toInt())
    var rows = rowsFit(body)
    val overflow = rows * columns < board.rows.size || !board.rowsComplete
    if (overflow) rows = rowsFit(body - MORE_HEIGHT)
    if (rows >= 1) return squeezeOneMoreRow(board, body, GalleryLayout(columns, rows, cardWidth, cover, props), caption, maxRows)
    // 표지 높이를 줄여 한 줄이라도 넣는다. 너무 낮아지면 이 속성 줄 수로는 못 그린다.
    val low = (body - caption - (if (overflow) MORE_HEIGHT else 0.dp)).coerceAtMost(cover)
    return low.takeIf { it >= MIN_GALLERY_COVER }?.let { GalleryLayout(columns, 1, cardWidth, it, props) }
}

/**
 * 남는 높이가 있고 보일 행이 더 있으면, 표지를 정사각에 가깝게까지([MIN_COVER_RATIO]) 낮춰 한 줄을 더 넣는다(좁은 위젯에서 아래가 빈 채 남지 않게).
 * 더 넣을 줄이 없거나 표지가 그보다 낮아지면 그대로 둔다.
 */
private fun squeezeOneMoreRow(board: ViewBoard, body: Dp, layout: GalleryLayout, caption: Dp, maxRows: Int): GalleryLayout {
    val rows = layout.rows + 1
    if (rows > maxRows || board.rows.size <= layout.cards) return layout
    val more = if (rows * layout.columns < board.rows.size || !board.rowsComplete) MORE_HEIGHT else 0.dp
    val cover = (body - more - GALLERY_GAP * (rows - 1)) / rows - caption
    return if (cover >= layout.cardWidth * MIN_COVER_RATIO) layout.copy(rows = rows, coverHeight = cover) else layout
}

/** 카드 캡션: 제목, 캡션 줄(별점이 먼저, 이어 보이는 속성 값), 낭독 글자(제목과 줄들을 쉼표로 이은 것). */
internal data class GalleryCaption(val title: String, val lines: List<String>, val description: String)

/**
 * 카드 캡션. 제목 아래 줄은 [propLines]개까지이고, 별점 속성([ratingProp], 레시피가 고른 것, 보기에서 숨겨져 있어도 된다)이 값이 있으면 첫 줄을 쓴다.
 * 나머지는 보기에 보이는 속성 중 제목·파일·별점 열을 뺀 값이 있는 것들이다(노션 갤러리 카드처럼 값만).
 */
internal fun galleryCaption(board: ViewBoard, row: ViewRow, propLines: Int, ratingProp: String): GalleryCaption {
    val title = row.cells.firstOrNull().orEmpty()
    val ratingColumn = board.columnInfo.firstOrNull { it.id == ratingProp && ratingProp.isNotEmpty() }
    val rating = ratingColumn?.let { RatingText.text(it, row.typed[it.id]) }
    val ratingSpoken = ratingColumn?.let { RatingText.spoken(it, row.typed[it.id]) }
    val values = board.columns.indices.drop(1).mapNotNull { i ->
        val name = board.columns[i]
        val info = board.columnInfo.firstOrNull { it.name == name }
        row.cells.getOrNull(i)?.takeIf { it.isNotBlank() && info?.type != "files" && name != ratingColumn?.name }
    }
    val lines = (listOfNotNull(rating) + values).take(propLines)
    val spoken = (listOf(title) + listOfNotNull(ratingSpoken.takeIf { rating != null && propLines > 0 }) + values.take((propLines - (if (rating != null) 1 else 0)).coerceAtLeast(0))).filter { it.isNotBlank() }
    return GalleryCaption(title, lines, spoken.joinToString(", "))
}
