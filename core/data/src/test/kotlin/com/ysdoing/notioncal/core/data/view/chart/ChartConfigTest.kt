package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12b VR-CHT 노션 차트 설정 읽기. 픽스처는 공식 문서(Working with views › Chart configuration)의 모양이다.
 * 실제 조회 응답은 실측 전이라 모르는 키는 무시하고, 못 그리는 설정은 [ChartParse.Unsupported]로 알린다.
 */
class ChartConfigTest {
    private val columns = listOf(
        ViewColumn("t", "이름", "title"),
        ViewColumn("cat", "분류", "select", listOf(ViewChoice("o1", "식비", color = "red"))),
        ViewColumn("st", "상태", "status", listOf(ViewChoice("s1", "할 일", "To-do", "gray"))),
        ViewColumn("tg", "태그", "multi_select"),
        ViewColumn("amt", "금액", "number"),
        ViewColumn("d", "날짜", "date"),
        ViewColumn("c", "완료", "checkbox"),
        ViewColumn("memo", "메모", "rich_text"),
        ViewColumn("f", "수식", "formula"),
    )

    private fun ok(json: String): ChartSpec = (ChartConfig.parse(json, columns) as ChartParse.Ok).spec

    private fun reason(json: String): String = (ChartConfig.parse(json, columns) as ChartParse.Unsupported).reason

    // --- 픽스처 4종

    @Test
    fun aColumnChartGroupedBySelectAndCounted() {
        val spec = ok("""{"type":"chart","chart_type":"column","x_axis":{"type":"select","property_id":"cat","sort":{"type":"manual"}},"y_axis":{"aggregator":"count"},"color_theme":"blue","show_data_labels":true,"height":"medium"}""")
        assertEquals(ChartSpec(ChartType.COLUMN, ChartGroup("cat", GroupKind.OPTION), ChartValue(AggFn.COUNT), colorTheme = "blue"), spec)
    }

    @Test
    fun aDonutChartGroupedByStatusGroupAndSummed() {
        val spec = ok("""{"type":"chart","chart_type":"donut","x_axis":{"type":"status","property_id":"st","group_by":"group","sort":{"type":"manual"}},"y_axis":{"aggregator":"sum","property_id":"amt"},"donut_labels":"name_and_value","legend_position":"bottom"}""")
        assertEquals(ChartSpec(ChartType.DONUT, ChartGroup("st", GroupKind.OPTION, byStatusGroup = true), ChartValue(AggFn.SUM, "amt")), spec)
    }

    @Test
    fun aLineChartGroupedByWeekStartingMondayAndAveraged() {
        val spec = ok("""{"type":"chart","chart_type":"line","x_axis":{"type":"date","property_id":"d","group_by":"week","sort":{"type":"ascending"},"start_day_of_week":1},"y_axis":{"aggregator":"average","property_id":"amt"},"smooth_line":true}""")
        assertEquals(
            ChartSpec(ChartType.LINE, ChartGroup("d", GroupKind.DATE, DateBucket.WEEK, weekStartsMonday = true), ChartValue(AggFn.AVG, "amt"), sort = ChartSort.X_ASC),
            spec,
        )
    }

    @Test
    fun aNumberChartTakesItsValueFromTheValueObject() {
        val spec = ok("""{"type":"chart","chart_type":"number","value":{"aggregator":"sum","property_id":"amt"}}""")
        assertEquals(ChartSpec(ChartType.NUMBER, null, ChartValue(AggFn.SUM, "amt")), spec)
    }

    // --- 읽는 방법

    @Test
    fun aMissingAggregationMeansCountLikeNotionsDefault() {
        assertEquals(ChartValue(AggFn.COUNT), ok("""{"chart_type":"bar","x_axis":{"type":"checkbox","property_id":"c"}}""").value)
        assertEquals(ChartValue(AggFn.COUNT), ok("""{"chart_type":"number"}""").value)
    }

    @Test
    fun xAxisKindsMapToGroupKinds() {
        fun kind(prop: String, extra: String = "") = ok("""{"chart_type":"bar","x_axis":{"property_id":"$prop"$extra}}""").group
        assertEquals(ChartGroup("tg", GroupKind.MULTI_OPTION), kind("tg"))
        assertEquals(ChartGroup("c", GroupKind.CHECKBOX), kind("c"))
        assertEquals(ChartGroup("st", GroupKind.OPTION, byStatusGroup = false), kind("st", ""","group_by":"option""""))
        assertEquals(ChartGroup("t", GroupKind.TEXT), kind("t", ""","group_by":"exact""""))
        assertEquals(ChartGroup("memo", GroupKind.TEXT), kind("memo")) // 묶음이 없으면 exact로 본다
        assertEquals(ChartGroup("d", GroupKind.DATE, DateBucket.MONTH), kind("d", ""","group_by":"month""""))
        assertEquals(ChartGroup("d", GroupKind.DATE, DateBucket.YEAR), kind("d", ""","group_by":"year""""))
    }

    @Test
    fun sortComesFromTheTopLevelFieldFirstThenTheAxis() {
        fun sort(extra: String, axis: String) = ok("""{"chart_type":"bar"$extra,"x_axis":{"property_id":"cat","sort":{"type":"$axis"}}}""").sort
        assertEquals(ChartSort.X_DESC, sort("", "descending"))
        assertEquals(ChartSort.MANUAL, sort("", "manual"))
        assertEquals(ChartSort.Y_DESC, sort(""","sort":"y_descending"""", "ascending")) // 위쪽 sort가 이긴다
        assertEquals(ChartSort.X_ASC, sort(""","sort":"x_ascending"""", "descending"))
    }

    @Test
    fun emptyGroupsLegendAndColorsAreRead() {
        val spec = ok("""{"chart_type":"column","hide_empty_groups":true,"legend_position":"off","color_theme":"colorful","x_axis":{"property_id":"cat"}}""")
        assertTrue(spec.hideEmpty && spec.legendOff)
        assertEquals("colorful", spec.colorTheme)
        assertTrue(ok("""{"chart_type":"column","x_axis":{"property_id":"cat","hide_empty_groups":true}}""").hideEmpty)
    }

    @Test
    fun unknownKeysAndStackingAreIgnored() {
        val plain = ok("""{"chart_type":"column","x_axis":{"property_id":"cat"}}""")
        val extras = ok("""{"chart_type":"column","x_axis":{"property_id":"cat","future":1},"stack_by":{"type":"select","property_id":"st"},"group_style":"percent","brand_new":[1,2]}""")
        assertEquals(plain, extras)
    }

    // --- 못 그리는 설정

    @Test
    fun configurationsTheWidgetCannotDrawAreUnsupportedWithAReason() {
        val cases = mapOf(
            """{"chart_type":"column","x_axis":null,"x_axis_property_id":"cat","y_axis_property_id":"amt"}""" to "x축 없음",
            """{"chart_type":"column","x_axis":{"property_id":"gone"}}""" to "x축 속성을 알 수 없음",
            """{"chart_type":"column","x_axis":{"type":"formula","property_id":"f"}}""" to "x축 속성 종류 formula",
            """{"chart_type":"column","x_axis":{"property_id":"d","group_by":"relative"}}""" to "날짜 묶음 relative",
            """{"chart_type":"column","x_axis":{"property_id":"d"}}""" to "날짜 묶음 null",
            """{"chart_type":"column","x_axis":{"property_id":"memo","group_by":"alphabet_prefix"}}""" to "글자 묶음 alphabet_prefix",
            """{"chart_type":"column","x_axis":{"property_id":"amt"}}""" to "x축 속성 종류 number",
            """{"chart_type":"column","x_axis":{"property_id":"cat"},"y_axis":{"aggregator":"median","property_id":"amt"}}""" to "집계 median",
            """{"chart_type":"column","x_axis":{"property_id":"cat"},"y_axis":{"aggregator":"sum"}}""" to "숫자 속성을 알 수 없음",
            """{"chart_type":"column","x_axis":{"property_id":"cat"},"y_axis":{"aggregator":"sum","property_id":"memo"}}""" to "숫자 속성을 알 수 없음",
            """{"chart_type":"number","value":{"aggregator":"percent_checked","property_id":"c"}}""" to "집계 percent_checked",
            """{"chart_type":"gauge","x_axis":{"property_id":"cat"}}""" to "chart_type gauge",
            """{"x_axis":{"property_id":"cat"}}""" to "chart_type null",
            "not json" to "설정을 읽지 못함",
            "" to "설정을 읽지 못함",
        )
        cases.forEach { (json, expected) -> assertTrue("$json → ${reason(json)}", reason(json).startsWith(expected)) }
    }
}
