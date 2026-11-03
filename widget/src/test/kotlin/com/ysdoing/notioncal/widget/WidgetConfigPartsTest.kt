package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.LineSpacing
import com.ysdoing.notioncal.core.data.WidgetConfig
import com.ysdoing.notioncal.core.data.WidgetTab
import com.ysdoing.notioncal.core.data.WidgetTheme
import org.junit.Assert.assertEquals
import org.junit.Test

/** 위젯 설정 화면의 '초기화'와 고정 미리보기 배율. */
class WidgetConfigPartsTest {
    @Test
    fun resetLookRestoresAppearanceButKeepsWhatTheWidgetPointsAt() {
        val configured = WidgetConfig(
            7, monthOffset = 2, dataSourceIds = setOf("a"), theme = WidgetTheme.DARK, opacity = 40, fontScale = 120, maxPerCell = 3,
            lineSpacing = LineSpacing.WIDE, hideDone = true, selectedTab = WidgetTab.HABIT, hideAmounts = true, ddayPageId = "d", showWeather = true, viewId = "v",
        )
        // 모양은 기본값, 고른 대상(보기·디데이)과 보고 있는 달·탭은 그대로다.
        assertEquals(WidgetConfig(7, monthOffset = 2, selectedTab = WidgetTab.HABIT, ddayPageId = "d", viewId = "v"), configured.resetLook())
    }

    @Test
    fun resetOfADefaultConfigChangesNothing() {
        assertEquals(WidgetConfig(3), WidgetConfig(3).resetLook())
    }

    @Test
    fun previewShrinksToTheScreenWidthButNeverEnlarges() {
        assertEquals(1f, previewScale(DpSize(234.dp, 213.dp), 360.dp), 0.001f)
        assertEquals(360f / 501f, previewScale(DpSize(501.dp, 826.dp), 360.dp), 0.001f)
    }

    @Test
    fun tallPreviewIsCutAtTheCapInsteadOfShrinkingTheText() {
        val tall = DpSize(501.dp, 826.dp)
        val scale = previewScale(tall, 360.dp)
        assertEquals(300.dp, previewHeight(tall, scale, 300.dp))
        // 낮은 위젯은 자르지 않는다.
        val low = DpSize(234.dp, 213.dp)
        assertEquals(213.dp, previewHeight(low, previewScale(low, 360.dp), 300.dp))
    }
}
