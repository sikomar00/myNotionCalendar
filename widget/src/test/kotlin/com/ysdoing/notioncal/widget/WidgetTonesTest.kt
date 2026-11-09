package com.ysdoing.notioncal.widget

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.luminance
import com.ysdoing.notioncal.core.data.DesignTokens
import com.ysdoing.notioncal.core.data.NotionColors
import org.junit.Assert.assertTrue
import org.junit.Test

/** 위젯 톤(시안 2026-11-03)의 대비. 글자는 4.5:1, 아이콘·테두리 같은 그림은 3:1 이상이다(WC-3). */
class WidgetTonesTest {
    private fun contrast(a: Color, b: Color): Double {
        val (hi, lo) = listOf(a.luminance(), b.luminance()).sortedDescending()
        return (hi + 0.05) / (lo + 0.05)
    }

    private fun atLeast(min: Double, a: Color, b: Color, what: String) {
        val ratio = contrast(a, b)
        assertTrue("$what 대비 %.2f:1 < $min:1".format(ratio), ratio >= min)
    }

    private fun check(name: String, tones: WidgetTones, ink: Color, graphite: Color, accent: Color) {
        atLeast(4.5, ink, tones.tile, "$name 글자/타일")
        atLeast(4.5, ink, tones.tileHigh, "$name 글자/타일 높음")
        atLeast(4.5, graphite, tones.tile, "$name 보조 글자/타일")
        // 타일 높음 위의 글자는 본문색만 쓴다: 보조색은 라이트에서 4.3:1이라 모자란다.
        atLeast(4.5, tones.onAccentContainer, tones.accentContainer, "$name 강조 컨테이너 글자")
        atLeast(4.5, tones.onGreenContainer, tones.greenContainer, "$name 초록 컨테이너 글자")
        atLeast(3.0, tones.green, tones.tile, "$name 채운 초록 체크/타일")
        atLeast(3.0, graphite, tones.tile, "$name 체크 전 테두리/타일")
        atLeast(3.0, accent, tones.tile, "$name 강조 아이콘/타일")
    }

    @Test
    fun lightTonesKeepContrast() = with(DesignTokens.Light) {
        check("라이트", WidgetTones.LIGHT, Color(INK), Color(GRAPHITE), Color(ACCENT))
    }

    @Test
    fun darkTonesKeepContrast() = with(DesignTokens.Dark) {
        check("다크", WidgetTones.DARK, Color(INK), Color(GRAPHITE), Color(ACCENT))
    }

    /**
     * 월간 위젯 주 타일 위에 겹쳐 그리는 여러 날 일정 막대(진행 28%·완료 6%)의 글자가 노션 색 20가지 모두에서 읽힌다.
     * 완료 막대의 글자는 흑연이면 라이트에서 4.1:1까지 떨어져 본문색을 쓴다(WidgetGrid.Segment).
     */
    private fun barsOnTile(tones: WidgetTones, ink: Color) {
        for (hex in NotionColors.pickerPalette) {
            val color = Color(NotionColors.argb(hex))
            atLeast(4.5, ink, color.copy(alpha = 0.28f).compositeOver(tones.tile), "진행 막대 $hex")
            atLeast(4.5, ink, color.copy(alpha = 0.06f).compositeOver(tones.tile), "완료 막대 $hex")
        }
    }

    @Test
    fun multiDayBarsStayReadableOnTheWeekTile() {
        with(DesignTokens.Light) { barsOnTile(WidgetTones.LIGHT, Color(INK)) }
        with(DesignTokens.Dark) { barsOnTile(WidgetTones.DARK, Color(INK)) }
    }

    @Test
    fun tapTargetIsAtLeastTheGuidelineMinimum() {
        assertTrue(TAP_TARGET.value >= 48f)
    }
}
