package com.ysdoing.notioncal.widget

import androidx.compose.ui.graphics.Color
import com.ysdoing.notioncal.core.data.DesignTokens

/**
 * 위젯 톤 색(2026-11-03 시안, DESIGN.md '위젯'). 면은 앱 면 색(DesignTokens의 면 높음·가장 높음)을 그대로 쓰고,
 * 강조 컨테이너는 앱의 '선택 바탕'이다. 초록(습관·완료)만 위젯 전용 값이다. ColorProvider와 따로 두어 시험이 대비를 계산한다.
 */
internal class WidgetTones(
    /** 바탕 위로 한 단계 올린 타일. */
    val tile: Color,
    /** 버튼·빈 막대·타일 위의 칩. 그 위의 글자는 본문색만 쓴다(보조색은 라이트에서 4.3:1이라 모자라다). */
    val tileHigh: Color,
    val accentContainer: Color,
    val onAccentContainer: Color,
    /** 채운 초록(체크 완료, 습관 진행). 체크 표시는 원을 도려낸 아이콘이라 따로 색이 없다. */
    val green: Color,
    val greenContainer: Color,
    val onGreenContainer: Color,
) {
    companion object {
        val LIGHT = with(DesignTokens.Light) {
            WidgetTones(
                tile = Color(SURFACE_HIGH), tileHigh = Color(SURFACE_HIGHEST),
                // 라이트: 앱 강조색은 선택 바탕 위에서 4.3:1이라 한 단계 진한 파랑을 쓴다.
                accentContainer = Color(SELECTED), onAccentContainer = Color(0xFF124F96),
                green = Color(INCOME),
                greenContainer = Color(0xFFDDF0EA), onGreenContainer = Color(0xFF0B5F53),
            )
        }
        val DARK = with(DesignTokens.Dark) {
            WidgetTones(
                tile = Color(SURFACE_HIGH), tileHigh = Color(SURFACE_HIGHEST),
                accentContainer = Color(SELECTED), onAccentContainer = Color(ACCENT),
                green = Color(INCOME),
                greenContainer = Color(0xFF1F3A34), onGreenContainer = Color(0xFF8BDAC2),
            )
        }
    }
}
