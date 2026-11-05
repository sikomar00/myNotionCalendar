package com.ysdoing.notioncal.core.data.view.chart

import com.ysdoing.notioncal.core.data.NotionColors
import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 12b VR-CHT 차트 값 계산: 행을 x축으로 묶어 집계한다. 값·서식은 집계 타일([com.ysdoing.notioncal.core.data.view.Aggregator])과 같고,
 * 노션 차트의 묶음 규칙(옵션 순서, 날짜 묶음, 없음 묶음, 빈 그룹 숨김)을 따른다.
 */
class ChartComputeTest {
    private val options = listOf(
        ViewChoice("o1", "식비", color = "red"), ViewChoice("o2", "교통", color = "blue"),
        ViewChoice("o3", "취미", color = "default"), ViewChoice("o4", "의료", color = "green"), ViewChoice("o5", "교육", color = "purple"),
    )
    private val category = ViewColumn("cat", "분류", "select", options)
    private val tags = ViewColumn("tg", "태그", "multi_select", options)
    private val status = ViewColumn(
        "st", "상태", "status",
        listOf(ViewChoice("s1", "할 일", "To-do", "gray"), ViewChoice("s4", "보류", "To-do", "gray"), ViewChoice("s2", "진행 중", "In progress", "blue"), ViewChoice("s3", "완료", "Complete", "green")),
    )
    private val amount = ViewColumn("amt", "금액", "number")
    private val date = ViewColumn("d", "날짜", "date")
    private val done = ViewColumn("c", "완료", "checkbox")
    private val title = ViewColumn("t", "이름", "title")
    private val columns = listOf(title, category, tags, status, amount, date, done)

    private fun day(iso: String) = JsonObject(mapOf("start" to JsonPrimitive(iso)))

    private fun row(id: String, vararg values: Pair<String, JsonElement>) = ViewRow(id, "", emptyList(), typed = mapOf(*values))

    private fun board(rows: List<ViewRow>, complete: Boolean = true) = ViewBoard("v1", "차트", "chart", emptyList(), rows, 1L, null, columns, rowsComplete = complete)

    private fun spec(type: ChartType, group: ChartGroup?, value: ChartValue = ChartValue(AggFn.COUNT), sort: ChartSort = ChartSort.MANUAL, hideEmpty: Boolean = false, theme: String? = null) =
        ChartSpec(type, group, value, sort, hideEmpty, theme)

    private val byCategory = ChartGroup("cat", GroupKind.OPTION)

    private val expenses = listOf(
        row("1", "cat" to JsonPrimitive("o1"), "amt" to JsonPrimitive(12000.0)), row("2", "cat" to JsonPrimitive("o1"), "amt" to JsonPrimitive(8000.0)),
        row("3", "cat" to JsonPrimitive("o1"), "amt" to JsonPrimitive(5000.0)), row("4", "cat" to JsonPrimitive("o2"), "amt" to JsonPrimitive(3500.0)),
        row("5", "cat" to JsonPrimitive("o2"), "amt" to JsonPrimitive(1500.0)), row("6", "amt" to JsonPrimitive(700.0)),
    )

    private fun ChartResult.table() = points.map { Triple(it.label, it.value, it.text) }

    // --- 선택으로 묶기

    @Test
    fun selectGroupsKeepTheOptionOrderIncludingEmptyOnesAndANoneGroup() {
        val result = ChartCompute.compute(board(expenses), spec(ChartType.COLUMN, byCategory))
        assertEquals(
            listOf(Triple("식비", 3.0, "3"), Triple("교통", 2.0, "2"), Triple("취미", 0.0, "0"), Triple("의료", 0.0, "0"), Triple("교육", 0.0, "0"), Triple("없음", 1.0, "1")),
            result.table(),
        )
        assertEquals(listOf("개수", "6"), listOf(result.valueLabel, result.totalText))
    }

    @Test
    fun hidingEmptyGroupsDropsOptionsWithoutRowsButKeepsTheNoneGroup() {
        val result = ChartCompute.compute(board(expenses), spec(ChartType.COLUMN, byCategory, hideEmpty = true))
        assertEquals(listOf("식비", "교통", "없음"), result.points.map { it.label })
        // 행이 하나도 없으면 없음 묶음도 없다
        assertEquals(listOf("식비", "교통"), ChartCompute.compute(board(expenses.take(5)), spec(ChartType.COLUMN, byCategory, hideEmpty = true)).points.map { it.label })
    }

    @Test
    fun sumsAreFormattedLikeAggregationTiles() {
        val result = ChartCompute.compute(board(expenses), spec(ChartType.BAR, byCategory, ChartValue(AggFn.SUM, "amt"), hideEmpty = true))
        assertEquals(listOf(Triple("식비", 25000.0, "25,000"), Triple("교통", 5000.0, "5,000"), Triple("없음", 700.0, "700")), result.table())
        assertEquals(listOf("합계 금액", "30,700"), listOf(result.valueLabel, result.totalText))
    }

    @Test
    fun averageMinAndMaxAggregateEachGroupsRows() {
        fun table(fn: AggFn) = ChartCompute.compute(board(expenses), spec(ChartType.BAR, byCategory, ChartValue(fn, "amt"), hideEmpty = true)).table().map { it.second }
        assertEquals(listOf(25000.0 / 3, 2500.0, 700.0), table(AggFn.AVG))
        assertEquals(listOf(5000.0, 1500.0, 700.0), table(AggFn.MIN))
        assertEquals(listOf(12000.0, 3500.0, 700.0), table(AggFn.MAX))
        assertEquals("평균 금액", ChartCompute.compute(board(expenses), spec(ChartType.BAR, byCategory, ChartValue(AggFn.AVG, "amt"))).valueLabel)
    }

    @Test
    fun anAverageOfNothingIsAZeroBarWithADashLabel() {
        val none = ChartCompute.compute(board(expenses), spec(ChartType.BAR, byCategory, ChartValue(AggFn.AVG, "amt")))
        assertEquals(Triple("취미", 0.0, "—"), none.table()[2])
    }

    // --- 순서

    @Test
    fun sortByXOrdersByNameWithNoneLastAndByYOrdersByValue() {
        fun labels(sort: ChartSort) = ChartCompute.compute(board(expenses), spec(ChartType.BAR, byCategory, sort = sort, hideEmpty = true)).points.map { it.label }
        assertEquals(listOf("교통", "식비", "없음"), labels(ChartSort.X_ASC))
        assertEquals(listOf("식비", "교통", "없음"), labels(ChartSort.X_DESC))
        assertEquals(listOf("식비", "교통", "없음"), labels(ChartSort.Y_DESC))
        assertEquals(listOf("없음", "교통", "식비"), labels(ChartSort.Y_ASC))
    }

    // --- 색

    @Test
    fun optionColorsComeFirstThenTheThemeThenThePalette() {
        val colors = ChartCompute.compute(board(expenses), spec(ChartType.COLUMN, byCategory, theme = "orange")).points.associate { it.label to it.colorHex }
        assertEquals(NotionColors.hexFor("red"), colors["식비"])
        assertEquals(NotionColors.hexFor("blue"), colors["교통"])
        assertEquals(NotionColors.hexFor("orange"), colors["취미"]) // default는 색이 아니라 테마색
        assertEquals(ChartPalette.GRAY, colors["없음"])
        // 도넛은 조각을 구분해야 해서 테마 한 색을 쓰지 않고 팔레트를 돈다
        val donut = ChartCompute.compute(board(expenses), spec(ChartType.DONUT, byCategory, theme = "orange")).points.first { it.label == "취미" }
        assertEquals(ChartPalette.at(2), donut.colorHex)
    }

    // --- 다중 선택·상태·체크박스·글자

    @Test
    fun aMultiSelectRowCountsInEveryOptionItHas() {
        val rows = listOf(
            row("1", "tg" to JsonArray(listOf(JsonPrimitive("o1"), JsonPrimitive("o2")))), row("2", "tg" to JsonArray(listOf(JsonPrimitive("o1")))),
            row("3", "tg" to JsonArray(emptyList())), row("4", "tg" to JsonArray(listOf(JsonPrimitive("gone")))),
        )
        val result = ChartCompute.compute(board(rows), spec(ChartType.BAR, ChartGroup("tg", GroupKind.MULTI_OPTION), hideEmpty = true))
        assertEquals(listOf(Triple("식비", 2.0, "2"), Triple("교통", 1.0, "1"), Triple("없음", 2.0, "2")), result.table()) // 비었거나 지워진 옵션은 없음
        assertEquals("4", result.totalText) // 전체는 행 수(겹치지 않는다)
    }

    @Test
    fun statusGroupsMergeOptionsOfTheSameGroupInGroupOrder() {
        val rows = listOf(
            row("1", "st" to JsonPrimitive("s1"), "amt" to JsonPrimitive(1000.0)), row("2", "st" to JsonPrimitive("s4"), "amt" to JsonPrimitive(500.0)),
            row("3", "st" to JsonPrimitive("s2"), "amt" to JsonPrimitive(2000.0)), row("4", "st" to JsonPrimitive("s3"), "amt" to JsonPrimitive(3000.0)),
        )
        val result = ChartCompute.compute(board(rows), spec(ChartType.DONUT, ChartGroup("st", GroupKind.OPTION, byStatusGroup = true), ChartValue(AggFn.SUM, "amt")))
        assertEquals(listOf(Triple("To-do", 1500.0, "1,500"), Triple("In progress", 2000.0, "2,000"), Triple("Complete", 3000.0, "3,000")), result.table())
        assertEquals(listOf(NotionColors.hexFor("gray"), NotionColors.hexFor("blue"), NotionColors.hexFor("green")), result.points.map { it.colorHex })
        // 그룹이 아니라 옵션으로 묶으면 옵션마다
        val byOption = ChartCompute.compute(board(rows), spec(ChartType.DONUT, ChartGroup("st", GroupKind.OPTION)))
        assertEquals(listOf("할 일", "보류", "진행 중", "완료"), byOption.points.map { it.label })
    }

    @Test
    fun checkboxGroupsAreCheckedAndUnchecked() {
        val rows = listOf(row("1", "c" to JsonPrimitive(true)), row("2", "c" to JsonPrimitive(false)), row("3", "c" to JsonPrimitive(true)), row("4"))
        val result = ChartCompute.compute(board(rows), spec(ChartType.DONUT, ChartGroup("c", GroupKind.CHECKBOX)))
        assertEquals(listOf(Triple("체크함", 2.0, "2"), Triple("체크 안 함", 2.0, "2")), result.table()) // 값이 없는 행은 체크 안 함
        assertEquals(NotionColors.hexFor("green"), result.points[0].colorHex)
    }

    @Test
    fun textGroupsAreExactValuesInFirstAppearanceOrder() {
        val rows = listOf(row("1", "t" to JsonPrimitive("집")), row("2", "t" to JsonPrimitive(" 회사 ")), row("3", "t" to JsonPrimitive("집")), row("4", "t" to JsonPrimitive("  ")))
        val result = ChartCompute.compute(board(rows), spec(ChartType.BAR, ChartGroup("t", GroupKind.TEXT)))
        assertEquals(listOf(Triple("집", 2.0, "2"), Triple("회사", 1.0, "1"), Triple("없음", 1.0, "1")), result.table())
    }

    // --- 날짜로 묶기

    private val dated = listOf(
        row("1", "d" to day("2027-01-04"), "amt" to JsonPrimitive(10.0)), row("2", "d" to day("2027-01-06T09:00:00+09:00"), "amt" to JsonPrimitive(20.0)),
        row("3", "d" to day("2027-01-13"), "amt" to JsonPrimitive(50.0)), row("4", "d" to day("2027-01-11"), "amt" to JsonPrimitive(30.0)),
        row("5", "d" to day("2028-02-01"), "amt" to JsonPrimitive(100.0)), row("6", "amt" to JsonPrimitive(5.0)),
    )

    private fun dates(bucket: DateBucket, monday: Boolean = false, sort: ChartSort = ChartSort.MANUAL, fn: AggFn = AggFn.AVG) =
        ChartCompute.compute(board(dated), spec(ChartType.COLUMN, ChartGroup("d", GroupKind.DATE, bucket, weekStartsMonday = monday), ChartValue(fn, "amt"), sort)) // 막대는 빈 날짜를 채우지 않는다

    @Test
    fun weeklyBucketsStartOnTheConfiguredWeekdayAndAreInDateOrderWithNoneLast() {
        assertEquals(
            listOf(Triple("1/4~", 15.0, "15"), Triple("1/11~", 40.0, "40"), Triple("1/31~", 100.0, "100"), Triple("없음", 5.0, "5")),
            dates(DateBucket.WEEK, monday = true).table(),
        )
        // 일요일 시작: 1/4(월)·1/6(수)은 1/3 주, 1/11(월)·1/13(수)은 1/10 주, 2028-02-01(화)은 1/30 주
        assertEquals(listOf("1/3~", "1/10~", "1/30~", "없음"), dates(DateBucket.WEEK).points.map { it.label })
    }

    @Test
    fun dayMonthAndYearBucketsAreLabelledShortly() {
        assertEquals(listOf("1/4", "1/6", "1/11", "1/13", "2/1", "없음"), dates(DateBucket.DAY).points.map { it.label })
        assertEquals(listOf("2027.01", "2028.02", "없음"), dates(DateBucket.MONTH).points.map { it.label })
        assertEquals(listOf("2027", "2028", "없음"), dates(DateBucket.YEAR).points.map { it.label })
        assertEquals(listOf(Triple("2027.01", 110.0, "110"), Triple("2028.02", 100.0, "100"), Triple("없음", 5.0, "5")), dates(DateBucket.MONTH, fn = AggFn.SUM).table())
    }

    @Test
    fun descendingDatesReverseTheOrderButKeepNoneLast() {
        assertEquals(listOf("2028", "2027", "없음"), dates(DateBucket.YEAR, sort = ChartSort.X_DESC).points.map { it.label })
    }

    // --- 숫자 차트·한도·부분 값

    @Test
    fun aLineChartFillsTheDatesBetweenBucketsSoTheSpacingFollowsTime() {
        // 월요일 시작 주: 1/4에 10, 1/25에 40이 있고 사이의 1/11·1/18 주는 행이 없다
        val rows = listOf(
            row("1", "d" to day("2027-01-04"), "amt" to JsonPrimitive(10.0)), row("2", "d" to day("2027-01-25"), "amt" to JsonPrimitive(40.0)),
        )
        val group = ChartGroup("d", GroupKind.DATE, DateBucket.WEEK, weekStartsMonday = true)
        val line = ChartCompute.compute(board(rows), spec(ChartType.LINE, group, ChartValue(AggFn.SUM, "amt")))
        assertEquals(listOf("1/4~" to 10.0, "1/11~" to 0.0, "1/18~" to 0.0, "1/25~" to 40.0), line.points.map { it.label to it.value })
        // 막대는 채우지 않는다(노션이 빈 날짜를 보여 주는지 실측 전이고, 점이 많아진다)
        val bars = ChartCompute.compute(board(rows), spec(ChartType.COLUMN, group, ChartValue(AggFn.SUM, "amt")))
        assertEquals(listOf("1/4~", "1/25~"), bars.points.map { it.label })
        // 빈 그룹 숨기기를 켜면 선도 채우지 않는다
        assertEquals(listOf("1/4~", "1/25~"), ChartCompute.compute(board(rows), spec(ChartType.LINE, group, ChartValue(AggFn.SUM, "amt"), hideEmpty = true)).points.map { it.label })
    }

    @Test
    fun monthsAndYearsAreFilledToo() {
        val rows = listOf(row("1", "d" to day("2026-11-05")), row("2", "d" to day("2027-02-01")))
        fun labels(bucket: DateBucket) = ChartCompute.compute(board(rows), spec(ChartType.LINE, ChartGroup("d", GroupKind.DATE, bucket))).points.map { it.label }
        assertEquals(listOf("2026.11", "2026.12", "2027.01", "2027.02"), labels(DateBucket.MONTH))
        assertEquals(listOf("2026", "2027"), labels(DateBucket.YEAR))
        assertEquals(listOf("11/5", "11/6"), ChartCompute.compute(board(listOf(row("1", "d" to day("2026-11-05")), row("2", "d" to day("2026-11-06")))), spec(ChartType.LINE, ChartGroup("d", GroupKind.DATE, DateBucket.DAY))).points.map { it.label })
    }

    @Test
    fun aRangeTooWideToFillKeepsOnlyTheBucketsWithRows() {
        val rows = listOf(row("1", "d" to day("2020-01-01")), row("2", "d" to day("2027-01-01")))
        val points = ChartCompute.compute(board(rows), spec(ChartType.LINE, ChartGroup("d", GroupKind.DATE, DateBucket.DAY))).points
        assertEquals(listOf("1/1", "1/1"), points.map { it.label }) // 일 단위로 2,500일은 채우지 않는다
    }

    @Test
    fun aGroupWithNothingToAggregateIsFlaggedMissingNotJustZero() {
        val average = ChartCompute.compute(board(expenses), spec(ChartType.LINE, byCategory, ChartValue(AggFn.AVG, "amt")))
        assertEquals(listOf(false, false, true, true, true, false), average.points.map { it.missing }) // 취미·의료·교육은 값이 없다
        // 합계·개수는 비어 있어도 0이라는 값이다
        assertTrue(ChartCompute.compute(board(expenses), spec(ChartType.LINE, byCategory)).points.none { it.missing })
    }

    @Test
    fun sharesAreOnlyForCountAndSumAndIgnoreNegativeValues() {
        val count = ChartCompute.compute(board(expenses), spec(ChartType.DONUT, byCategory, hideEmpty = true))
        assertEquals(listOf(50, 33, 17), count.points.map { count.sharePercent(it) })
        val avg = ChartCompute.compute(board(expenses), spec(ChartType.DONUT, byCategory, ChartValue(AggFn.AVG, "amt"), hideEmpty = true))
        assertEquals(listOf<Int?>(null, null, null), avg.points.map { avg.sharePercent(it) }) // 평균의 합은 몫이 아니다
        val negatives = ChartCompute.compute(
            board(listOf(row("1", "cat" to JsonPrimitive("o1"), "amt" to JsonPrimitive(-5.0)), row("2", "cat" to JsonPrimitive("o2"), "amt" to JsonPrimitive(10.0)))),
            spec(ChartType.DONUT, byCategory, ChartValue(AggFn.SUM, "amt"), hideEmpty = true),
        )
        assertEquals(listOf(0, 100), negatives.points.map { negatives.sharePercent(it) }) // 음수는 0으로: 도는 고리와 같다
    }

    @Test
    fun aNumberChartIsOneValueOverAllRows() {
        val result = ChartCompute.compute(board(expenses), spec(ChartType.NUMBER, null, ChartValue(AggFn.SUM, "amt")))
        assertEquals(listOf(Triple("합계 금액", 30700.0, "30,700")), result.table())
        assertEquals("30,700", result.totalText)
    }

    @Test
    fun pointsBeyondTheLimitMergeIntoOtherAggregatedOverTheirOwnRows() {
        val rows = listOf(
            row("1", "cat" to JsonPrimitive("o1"), "amt" to JsonPrimitive(100.0)), row("2", "cat" to JsonPrimitive("o2"), "amt" to JsonPrimitive(50.0)),
            row("3", "cat" to JsonPrimitive("o3"), "amt" to JsonPrimitive(10.0)), row("4", "cat" to JsonPrimitive("o4"), "amt" to JsonPrimitive(30.0)),
            row("5", "cat" to JsonPrimitive("o5"), "amt" to JsonPrimitive(20.0)),
        )
        val sum = ChartCompute.compute(board(rows), spec(ChartType.DONUT, byCategory, ChartValue(AggFn.SUM, "amt"), ChartSort.Y_DESC), maxPoints = 3)
        assertEquals(listOf(Triple("식비", 100.0, "100"), Triple("교통", 50.0, "50"), Triple("기타", 60.0, "60")), sum.table())
        assertEquals(ChartCompute.OTHER_LABEL, sum.points.last().label)
        assertEquals(ChartPalette.GRAY, sum.points.last().colorHex)
        // 평균은 점들의 평균이 아니라 합친 행들의 평균이다: 의료 30·교육 20·취미 10 → 20
        val avg = ChartCompute.compute(board(rows), spec(ChartType.DONUT, byCategory, ChartValue(AggFn.AVG, "amt"), ChartSort.Y_DESC), maxPoints = 3)
        assertEquals(20.0, avg.points.last().value, 0.0001)
        // 한도 안이면 그대로
        assertFalse(ChartCompute.compute(board(rows), spec(ChartType.DONUT, byCategory), maxPoints = 5).points.any { it.label == ChartCompute.OTHER_LABEL })
    }

    @Test
    fun aLimitOfOneJustTruncatesAndMultiSelectRowsAreNotCountedTwiceInOther() {
        val rows = listOf(row("1", "tg" to JsonArray(listOf(JsonPrimitive("o3"), JsonPrimitive("o4")))), row("2", "tg" to JsonArray(listOf(JsonPrimitive("o1")))))
        val group = ChartGroup("tg", GroupKind.MULTI_OPTION)
        assertEquals(listOf("식비"), ChartCompute.compute(board(rows), spec(ChartType.BAR, group, sort = ChartSort.Y_DESC), maxPoints = 1).points.map { it.label })
        val merged = ChartCompute.compute(board(rows), spec(ChartType.BAR, group, hideEmpty = true), maxPoints = 2)
        assertEquals(listOf("식비", "기타"), merged.points.map { it.label })
        assertEquals(1.0, merged.points.last().value, 0.0) // 취미·의료 두 옵션이 한 행에 겹쳐도 그 행은 한 번
    }

    @Test
    fun partialRowsAreFlaggedAndAMissingColumnGivesNoPoints() {
        assertTrue(ChartCompute.compute(board(expenses, complete = false), spec(ChartType.BAR, byCategory)).partial)
        assertFalse(ChartCompute.compute(board(expenses), spec(ChartType.BAR, byCategory)).partial)
        val gone = ChartCompute.compute(board(expenses), spec(ChartType.BAR, ChartGroup("gone", GroupKind.OPTION)))
        assertEquals(emptyList<ChartPoint>(), gone.points)
        assertNull(gone.points.firstOrNull())
    }
}
