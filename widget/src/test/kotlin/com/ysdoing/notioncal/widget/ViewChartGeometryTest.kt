package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.view.chart.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Phase 12b 도넛 각도·꺾은선 좌표·그림 한 장의 예산(PF-07) 계산. */
class ViewChartGeometryTest {
    private fun point(label: String, value: Double, color: String = "#000000") = ChartPoint(label, value, value.toString(), color)

    // --- 도넛

    @Test
    fun donutArcsAreProportionalStartAtTwelveOClockAndSumToATurn() {
        val arcs = donutArcs(listOf(point("a", 1.0, "#111111"), point("b", 2.0, "#222222"), point("c", 1.0, "#333333")))
        assertEquals(listOf(-90f, 0f, 180f), arcs.map { it.start })
        assertEquals(listOf(90f, 180f, 90f), arcs.map { it.sweep })
        assertEquals(listOf("#111111", "#222222", "#333333"), arcs.map { it.colorHex })
        assertEquals(360f, arcs.sumOf { it.sweep.toDouble() }.toFloat(), 0.001f)
    }

    @Test
    fun zeroNegativeAndNonFiniteValuesGetNoSliceAndAnAllZeroDonutIsEmpty() {
        val arcs = donutArcs(listOf(point("a", 3.0), point("zero", 0.0), point("neg", -5.0), point("nan", Double.NaN), point("b", 1.0)))
        assertEquals(listOf(270f, 90f), arcs.map { it.sweep })
        assertEquals(emptyList<DonutArc>(), donutArcs(listOf(point("a", 0.0), point("b", -1.0))))
        assertEquals(emptyList<DonutArc>(), donutArcs(emptyList()))
    }

    @Test
    fun aSingleSliceIsAFullRing() {
        val arcs = donutArcs(listOf(point("only", 7.0)))
        assertEquals(listOf(-90f to 360f), arcs.map { it.start to it.sweep })
    }

    @Test
    fun theLastSliceAbsorbsRoundingSoTheRingCloses() {
        val arcs = donutArcs((1..7).map { point("p$it", 1.0) })
        assertEquals(270f, arcs.last().start + arcs.last().sweep, 0.0001f) // -90 + 360
    }

    // --- 꺾은선

    @Test
    fun lineXIsEvenAndYPutsZeroAtTheBottomAndTheMaximumAtTheTop() {
        val g = lineGeometry(listOf(0.0, 5.0, 10.0), width = 100f, height = 60f, inset = 10f)
        assertEquals(listOf(10f, 50f, 90f), g.points.map { it.first })
        assertEquals(listOf(50f, 30f, 10f), g.points.map { it.second }) // 0 → 아래(60-10), 10 → 위(10)
        assertEquals(50f, g.baselineY, 0.001f)
    }

    @Test
    fun negativeValuesPutTheBaselineAboveTheBottom() {
        val g = lineGeometry(listOf(-10.0, 10.0), width = 100f, height = 100f, inset = 0f)
        assertEquals(listOf(100f, 0f), g.points.map { it.second })
        assertEquals(50f, g.baselineY, 0.001f)
    }

    @Test
    fun aConstantSeriesSitsAtTheTopAndAllZerosInTheMiddleAndASinglePointIsCentered() {
        val flat = lineGeometry(listOf(4.0, 4.0, 4.0), width = 90f, height = 40f, inset = 5f)
        assertEquals(listOf(5f, 5f, 5f), flat.points.map { it.second }) // 0이 아래, 가장 큰 값(4)이 위
        val zeros = lineGeometry(listOf(0.0, 0.0), width = 90f, height = 40f, inset = 5f)
        assertEquals(listOf(20f, 20f), zeros.points.map { it.second })
        assertEquals(35f, zeros.baselineY, 0.001f)
        val single = lineGeometry(listOf(3.0), width = 80f, height = 40f, inset = 5f)
        assertEquals(40f, single.points.single().first, 0.001f)
        assertEquals(emptyList<Pair<Float, Float>>(), lineGeometry(emptyList(), 80f, 40f, 5f).points)
    }

    @Test
    fun nonFiniteLineValuesAreSkippedAndKeepTheirPlace() {
        val g = lineGeometry(listOf(Double.NaN, 10.0, Double.POSITIVE_INFINITY, 5.0), width = 90f, height = 100f, inset = 0f)
        assertEquals(listOf(30f, 90f), g.points.map { it.first }) // 둘째·넷째 자리(0·30·60·90 중 30과 90)
        assertEquals(listOf(0f, 50f), g.points.map { it.second })
        assertTrue(g.points.all { it.first.isFinite() && it.second.isFinite() })
        assertEquals(emptyList<Pair<Float, Float>>(), lineGeometry(listOf(Double.NaN, Double.NaN), 90f, 100f, 0f).points)
    }

    @Test
    fun anAllNegativeSeriesKeepsTheZeroLineAtTheTopInsideThePicture() {
        val g = lineGeometry(listOf(-10.0, -5.0), width = 100f, height = 100f, inset = 10f)
        assertEquals(10f, g.baselineY, 0.001f) // 값 0은 위(최댓값 자리)
        assertEquals(listOf(90f, 50f), g.points.map { it.second })
        assertTrue(g.points.all { it.second in 0f..100f } && g.baselineY in 0f..100f)
    }

    // --- 예산(PF-07)

    @Test
    fun theWidgetBudgetIsAQuarterOfScreenTimesFourTimesOneAndAHalf() {
        assertEquals(3_790_800L, ChartBudget.widgetBytes(1080, 2340)) // S25: 1080×2340×4×1.5의 25%
        assertEquals(1_382_400L, ChartBudget.widgetBytes(720, 1280))
    }

    @Test
    fun everyPictureOfAWidgetFitsTheWidgetBudgetOnEveryScreen() {
        for ((w, h) in listOf(480 to 800, 720 to 1280, 1080 to 1920, 1080 to 2340, 1440 to 3120, 1812 to 2176)) {
            val picture = ChartBudget.pictureBytes(w, h)
            assertTrue("$w×$h: 그림 한 장 $picture", picture in 1..ChartBudget.MAX_PICTURE_BYTES)
            assertTrue("$w×$h: ${ChartBudget.MAX_PICTURES}장 ${picture * ChartBudget.MAX_PICTURES}", picture * ChartBudget.MAX_PICTURES <= ChartBudget.widgetBytes(w, h))
        }
    }

    @Test
    fun fitKeepsThePixelSizeWhenItFitsAndShrinksWithTheSameAspectWhenItDoesNot() {
        assertEquals(300 to 150, ChartBudget.fit(100f, 50f, 3f, maxBytes = 1_000_000))
        val (w, h) = ChartBudget.fit(500f, 250f, 3f, maxBytes = 400_000) // 1500×750 = 4.5MB → 줄인다
        assertTrue("$w×$h = ${w * h * 4}", w * h * 4L <= 400_000)
        assertEquals(2.0, w.toDouble() / h, 0.02)
    }

    @Test
    fun fitNeverReturnsAnEmptyPicture() {
        assertEquals(1 to 1, ChartBudget.fit(0f, 0f, 3f, 1_000_000))
        assertTrue(ChartBudget.fit(1000f, 1000f, 3f, maxBytes = 4).let { it.first >= 1 && it.second >= 1 })
    }
}
