package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.view.AggFn
import org.junit.Assert.assertEquals
import org.junit.Test

/** A11Y-04 차트 낭독 문장: 색이 아니라 이름과 값을 함께 읽고, 도넛은 비율로, 부분 값이면 그 말을 붙인다. */
class ChartSummaryTest {
    private fun point(label: String, value: Double, text: String = value.toInt().toString()) = ChartPoint(label, value, text, "#000000")

    private fun result(type: ChartType, points: List<ChartPoint>, label: String = "개수", partial: Boolean = false, totalText: String = "0", fn: AggFn = AggFn.COUNT) =
        ChartResult(ChartSpec(type, ChartGroup("p", GroupKind.OPTION), ChartValue(fn)), points, label, totalText, partial)

    @Test
    fun barsReadTheirNamesAndValues() {
        val r = result(ChartType.COLUMN, listOf(point("식비", 12000.0, "12,000"), point("교통", 6000.0, "6,000")), "합계 금액")
        assertEquals("합계 금액: 식비 12,000, 교통 6,000", ChartSummary.sentence(r))
    }

    @Test
    fun aDonutReadsPercentages() {
        val r = result(ChartType.DONUT, listOf(point("식비", 42.0), point("교통", 21.0), point("기타", 37.0)))
        assertEquals("개수 비율: 식비 42%, 교통 21%, 기타 37%", ChartSummary.sentence(r))
    }

    @Test
    fun aDonutOfAllZerosFallsBackToValues() {
        assertEquals("개수: 식비 0", ChartSummary.sentence(result(ChartType.DONUT, listOf(point("식비", 0.0)))))
    }

    @Test
    fun aDonutOfAveragesIsReadAsValuesLikeItsLegendNotAsShares() {
        val r = result(ChartType.DONUT, listOf(point("식비", 8333.0, "8,333"), point("교통", 2500.0, "2,500")), label = "평균 금액", fn = AggFn.AVG)
        assertEquals("평균 금액: 식비 8,333, 교통 2,500", ChartSummary.sentence(r))
    }

    @Test
    fun negativeValuesAreNotReadAsNegativeShares() {
        val r = result(ChartType.DONUT, listOf(point("A", -5.0, "-5"), point("B", 10.0, "10")), label = "합계 금액", fn = AggFn.SUM)
        assertEquals("합계 금액 비율: A 0%, B 100%", ChartSummary.sentence(r))
    }

    @Test
    fun onlyTheFirstFiveAreNamedTheRestAreCounted() {
        val r = result(ChartType.BAR, (1..7).map { point("항목$it", it.toDouble()) })
        assertEquals("개수: 항목1 1, 항목2 2, 항목3 3, 항목4 4, 항목5 5 외 2개", ChartSummary.sentence(r))
    }

    @Test
    fun aNumberChartReadsItsValue() {
        assertEquals("합계 금액 30,700", ChartSummary.sentence(result(ChartType.NUMBER, emptyList(), "합계 금액", totalText = "30,700")))
    }

    @Test
    fun partialRowsAreSaidOutLoud() {
        assertEquals("개수: 식비 3 (일부 행만 반영)", ChartSummary.sentence(result(ChartType.COLUMN, listOf(point("식비", 3.0)), partial = true)))
        assertEquals("개수 1 (일부 행만 반영)", ChartSummary.sentence(result(ChartType.NUMBER, emptyList(), totalText = "1", partial = true)))
    }

    @Test
    fun noPointsSaysThereIsNothingToShow() {
        assertEquals(ChartSummary.EMPTY, ChartSummary.sentence(result(ChartType.COLUMN, emptyList())))
    }
}
