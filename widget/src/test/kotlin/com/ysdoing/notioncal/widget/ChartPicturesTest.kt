package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.chart.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/** Phase 12b 도넛·꺾은선 그림을 실제로 그려 크기와 색을 확인한다(Robolectric 네이티브 그래픽). */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ChartPicturesTest {
    private fun point(label: String, value: Double, color: String) = ChartPoint(label, value, value.toString(), color)

    private val red = "#E03E3E"
    private val blue = "#0B6E99"

    @Test
    fun theDonutRingTakesTheSliceColorsAndLeavesTheHoleTransparent() {
        val picture = drawDonut(listOf(point("a", 1.0, red), point("b", 1.0, blue)), 200, 200)
        assertEquals(200 to 200, picture.width to picture.height)
        // 12시에서 시계 방향: 첫 조각(빨강)은 오른쪽 절반, 둘째(파랑)는 왼쪽 절반. 고리는 반지름 60~100.
        assertEquals(NotionColors.argb(red), picture.getPixel(180, 100))
        assertEquals(NotionColors.argb(blue), picture.getPixel(20, 100))
        assertEquals(0, picture.getPixel(100, 100)) // 가운데 구멍은 투명: 글자는 Glance가 그 위에 얹는다
        assertEquals(0, picture.getPixel(2, 2)) // 모서리도 투명
    }

    @Test
    fun aDonutWithNothingToShowIsAFaintEmptyRing() {
        val picture = drawDonut(listOf(point("a", 0.0, red)), 100, 100)
        assertNotEquals(0, picture.getPixel(90, 50)) // 고리는 있다
        assertEquals(0, picture.getPixel(50, 50))
    }

    @Test
    fun theLineIsDrawnInItsColorAtThePeakAndLeavesTheCornersClear() {
        val picture = drawLine(listOf(0.0, 10.0, 0.0), blue, 300, 100)
        assertEquals(300 to 100, picture.width to picture.height)
        assertEquals(NotionColors.argb(blue), picture.getPixel(150, 8)) // 꼭짓점의 점
        assertEquals(0, picture.getPixel(0, 0))
        assertNotEquals(0, picture.getPixel(150, 60)) // 선 아래 연한 영역
    }

    @Test
    fun anEmptySeriesIsAClearPictureAndASinglePointIsJustADot() {
        assertEquals(0, drawLine(emptyList(), blue, 50, 50).getPixel(25, 25))
        val dot = drawLine(listOf(5.0), blue, 100, 60)
        assertEquals(NotionColors.argb(blue), dot.getPixel(50, 5)) // 가운데 위쪽에 점 하나(선은 없다)
        assertEquals(0, dot.getPixel(5, 30))
    }
}
