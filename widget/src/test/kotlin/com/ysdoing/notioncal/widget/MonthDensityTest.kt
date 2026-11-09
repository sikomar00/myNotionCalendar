package com.ysdoing.notioncal.widget

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.core.data.WidgetConfig
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 월간 위젯은 새 스타일(2026-11-03)로 바뀌어도 면적당 보이는 일정 줄 수가 줄지 않는다(사용자 요구).
 * 아래 줄 수는 새 스타일 이전 레이아웃의 값이다. 머리 높이(24/34/48dp)도 그 이상 커지지 않는다.
 * 줄 수는 치수 계산([WidgetMetrics])이 정하므로 이 시험은 그 계산이 줄 수·머리 높이를 줄이면 실패한다.
 * 그리는 쪽의 구조(주 타일은 이전 1dp 구분선 자리를 쓰고 View를 늘리지 않음)는 이 시험이 보지 않는다:
 * 코드 주석(WidgetGrid.WeekRow)과 View 개수·크기 시험(WidgetRemoteViewsTest)이 지킨다.
 */
class MonthDensityTest {
    private data class Baseline(val width: Int, val height: Int, val fontScale: Int, val systemFont: Float, val lanes: Int)

    private val baselines = listOf(
        Baseline(110, 110, 70, 1.0f, 1),
        Baseline(110, 110, 70, 1.3f, 1),
        Baseline(110, 110, 100, 1.0f, 1),
        Baseline(110, 110, 100, 1.3f, 1),
        Baseline(110, 110, 150, 1.0f, 1),
        Baseline(110, 110, 150, 1.3f, 1),
        Baseline(358, 152, 70, 1.0f, 1),
        Baseline(358, 152, 70, 1.3f, 1),
        Baseline(358, 152, 100, 1.0f, 1),
        Baseline(358, 152, 100, 1.3f, 1),
        Baseline(358, 152, 150, 1.0f, 1),
        Baseline(358, 152, 150, 1.3f, 1),
        Baseline(180, 400, 70, 1.0f, 3),
        Baseline(180, 400, 70, 1.3f, 2),
        Baseline(180, 400, 100, 1.0f, 2),
        Baseline(180, 400, 100, 1.3f, 1),
        Baseline(180, 400, 150, 1.0f, 1),
        Baseline(180, 400, 150, 1.3f, 1),
        Baseline(280, 299, 70, 1.0f, 1),
        Baseline(280, 299, 70, 1.3f, 1),
        Baseline(280, 299, 100, 1.0f, 1),
        Baseline(280, 299, 100, 1.3f, 1),
        Baseline(280, 299, 150, 1.0f, 1),
        Baseline(280, 299, 150, 1.3f, 1),
        Baseline(358, 380, 70, 1.0f, 2),
        Baseline(358, 380, 70, 1.3f, 1),
        Baseline(358, 380, 100, 1.0f, 1),
        Baseline(358, 380, 100, 1.3f, 1),
        Baseline(358, 380, 150, 1.0f, 1),
        Baseline(358, 380, 150, 1.3f, 1),
        Baseline(380, 420, 70, 1.0f, 3),
        Baseline(380, 420, 70, 1.3f, 2),
        Baseline(380, 420, 100, 1.0f, 2),
        Baseline(380, 420, 100, 1.3f, 1),
        Baseline(380, 420, 150, 1.0f, 1),
        Baseline(380, 420, 150, 1.3f, 1),
        Baseline(411, 800, 70, 1.0f, 6),
        Baseline(411, 800, 70, 1.3f, 6),
        Baseline(411, 800, 100, 1.0f, 5),
        Baseline(411, 800, 100, 1.3f, 4),
        Baseline(411, 800, 150, 1.0f, 3),
        Baseline(411, 800, 150, 1.3f, 2),
    )

    @Test
    fun visibleEventLanesNeverDropBelowTheOldLayout() {
        for (b in baselines) {
            val m = WidgetMetrics.of(DpSize(b.width.dp, b.height.dp), WidgetConfig(1, fontScale = b.fontScale), b.systemFont)
            assertTrue("${b.width}x${b.height} 글자 ${b.fontScale}% 시스템 ${b.systemFont}: ${m.lanes}줄 < 기준 ${b.lanes}줄", m.lanes >= b.lanes)
        }
    }

    @Test
    fun headerStaysAsLowAsBefore() {
        for (b in baselines) {
            val m = WidgetMetrics.of(DpSize(b.width.dp, b.height.dp), WidgetConfig(1, fontScale = b.fontScale), b.systemFont)
            val cap = when (m.mode) {
                WidgetMode.SMALL -> 24.dp
                WidgetMode.MEDIUM -> 34.dp
                WidgetMode.LARGE -> 48.dp
            }
            assertTrue("${m.mode} 머리 ${m.headerHeight} > $cap", m.headerHeight <= cap)
        }
    }
}
