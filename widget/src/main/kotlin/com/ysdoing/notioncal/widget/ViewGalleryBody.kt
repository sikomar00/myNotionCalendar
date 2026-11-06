package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.semantics.contentDescription
import androidx.glance.semantics.semantics
import androidx.glance.semantics.testTag
import androidx.glance.text.Text
import com.ysdoing.notioncal.core.data.view.GalleryConfig
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewRow

// Phase 12d VR-GAL: 갤러리 보기 본문. 카드는 표지(없으면 빈 타일) + 제목 + 속성 줄이고, 누르면 그 행의 편집 시트(액션 버튼 포함)다.
// 표지는 `Covers.Draw`로만 그려 비트맵 코드는 WidgetCover.kt 안에 남는다(PF-07). 넘치면 '+N 더 보기'가 뷰어를 연다(VR-02).

private val COVER_CORNER = 6.dp

/** 카드를 그릴 때 쓰는 값: 보기 설정, 표지 묶음, 별점 속성. */
internal class GalleryDraw(val config: GalleryConfig, val covers: Covers, val ratingProp: String)

/** 갤러리 격자. Column(줄들 + 틈 + 더 보기)과 Row(카드 + 틈)라 Glance 자식 10개 한도 안이다(열 4 × 줄 2, 열 2 × 줄 4가 최대). */
@Composable
internal fun GalleryBody(board: ViewBoard, layout: GalleryLayout, draw: GalleryDraw, look: ViewLook) {
    val context = LocalContext.current
    val shown = board.rows.take(layout.cards)
    Column(GlanceModifier.fillMaxWidth()) {
        shown.chunked(layout.columns).forEachIndexed { index, cards ->
            if (index > 0) Spacer(GlanceModifier.height(GALLERY_GAP))
            Row(GlanceModifier.fillMaxWidth()) {
                cards.forEachIndexed { i, row ->
                    if (i > 0) Spacer(GlanceModifier.width(GALLERY_GAP))
                    GalleryCard(board, row, layout, draw, look)
                }
            }
        }
        moreOf(board.rows.size, shown.size, board.rowsComplete)?.let { MoreLine(moreText(context, it), board.viewId, look) }
    }
}

@Composable
private fun GalleryCard(board: ViewBoard, row: ViewRow, layout: GalleryLayout, draw: GalleryDraw, look: ViewLook) {
    val context = LocalContext.current
    val caption = galleryCaption(board, row, layout.propLines, draw.ratingProp)
    val hasCover = draw.covers.has(row.pageId)
    val frame = GlanceModifier.size(layout.cardWidth, layout.coverHeight).tile(look.palette)
    Column(
        GlanceModifier.width(layout.cardWidth)
            .clickable(rowSheetAction(context, board.viewId, row, look.actionWidgetId))
            .semantics {
                contentDescription = caption.description
                testTag = "galleryCard"
            },
    ) {
        // 맞춤(fit)일 때 이미지 둘레와 표지 없는 카드는 이 타일 바탕이 보인다.
        Box(if (hasCover) frame else frame.semantics { testTag = "galleryNoCover" }, contentAlignment = Alignment.Center) {
            draw.covers.Draw(row.pageId, layout.cardWidth, layout.coverHeight, COVER_CORNER, draw.config.fit)
        }
        Text(caption.title, modifier = GlanceModifier.fillMaxWidth().padding(top = 4.dp), style = look.title, maxLines = 1)
        caption.lines.forEach { Text(it, modifier = GlanceModifier.fillMaxWidth(), style = look.sub, maxLines = 1) }
    }
}
