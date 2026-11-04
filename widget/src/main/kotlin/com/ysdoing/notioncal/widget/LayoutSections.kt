package com.ysdoing.notioncal.widget

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.glance.GlanceModifier
import androidx.glance.LocalSize
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding

internal val SECTION_GAP = 4.dp
private const val STRIP_MAX_SHARE = 0.45f // 보기와 함께일 때 타일 줄이 몸통에서 쓸 수 있는 최대 비율

/**
 * WL-03 섹션 높이. 쌓을 높이 [total]에서 섹션 사이 틈을 뺀 값을 [weights] 비율로 나눈다. 섹션 하나가 [min](머리줄 + 행 하나 + '더 보기')보다 낮아지면 뒤 섹션부터 빼
 * 앞쪽 섹션이 읽히는 높이를 지킨다(하나는 모자라도 남는다). 돌려주는 목록의 길이가 그릴 섹션 수다.
 */
internal fun sectionHeights(total: Dp, weights: List<Int>, gap: Dp, min: Dp): List<Dp> {
    for (count in weights.size downTo 1) {
        val shown = weights.take(count)
        val room = (total - gap * (count - 1)).coerceAtLeast(0.dp)
        val heights = shown.map { room * it / shown.sum() }
        if (count == 1 || heights.all { it >= min }) return heights
    }
    return emptyList()
}

/**
 * WL-03 섹션: 보기마다 한 칸씩 위아래로 쌓는다(자식은 타일 줄 + 틈 + 섹션 4개 + 틈 3개라 Column 자식 10개 제한 안이다). 칸 높이는 dp로 직접 준다
 * (`defaultWeight`는 같은 비율만 된다). 칸이 낮으면 [paneRowLimit]이 행을 줄이고 나머지는 '+N 더 보기'로 보낸다.
 * Phase 11e 집계 타일 줄은 맨 위에 두고 높이는 타일 줄 수로 정한다(보기가 있으면 몸통의 45%까지, 타일뿐이면 전부). 보기 섹션은 남은 높이를 나눈다.
 */
@Composable
internal fun LayoutSectionsBody(state: LayoutWidgetState, look: LayoutLook, size: DpSize) {
    val items = state.layout.items
    val body = (size.height - VIEW_PADDING_V * 2).coerceAtLeast(0.dp)
    val strip = state.layout.tiles.takeIf { it.isNotEmpty() }
        ?.let { planTileStrip(state.tileCells(), look.metrics, size, room = if (items.isEmpty()) body else body * STRIP_MAX_SHARE) }
    val used = strip?.let { it.height + if (items.isEmpty()) 0.dp else SECTION_GAP } ?: 0.dp
    val heights = sectionHeights((body - used).coerceAtLeast(0.dp), items.map { it.weight }, SECTION_GAP, paneMinHeight(look.metrics))
    Column(GlanceModifier.fillMaxSize().padding(horizontal = PADDING_H, vertical = VIEW_PADDING_V)) {
        if (strip != null) {
            TileStrip(strip, look, size.width - PADDING_H * 2)
            if (items.isNotEmpty()) Spacer(GlanceModifier.height(SECTION_GAP))
        }
        heights.forEachIndexed { index, height ->
            if (index > 0) Spacer(GlanceModifier.height(SECTION_GAP))
            Column(GlanceModifier.fillMaxWidth().height(height)) {
                val pane = state.paneState(items[index])
                CompositionLocalProvider(LocalSize provides DpSize(size.width, height)) {
                    ViewPane(pane, look.palette, look.metrics, PaneSpec(look.staticRows, height = height, presets = false))
                }
            }
        }
    }
}
