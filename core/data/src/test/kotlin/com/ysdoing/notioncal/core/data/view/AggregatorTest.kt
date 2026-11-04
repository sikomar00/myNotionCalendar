package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate

/**
 * Phase 11e AG-01·02·04 집계 타일 계산. 함수 7종 × 기간 4종, 연속 달성일 경계, 목표 진행, 부분 데이터, 없는 속성.
 * 오늘은 2026-11-04(수). 주 시작이 월이면 11/2~11/8, 일이면 11/1~11/7이다.
 */
class AggregatorTest {
    private val today = LocalDate.of(2026, 11, 4)
    private val amount = ViewColumn("n", "금액", "number")
    private val done = ViewColumn("c", "완료", "checkbox")
    private val date = ViewColumn("d", "날짜", "date")
    private val memo = ViewColumn("m", "메모", "rich_text")

    private fun day(text: String): JsonElement = JsonObject(mapOf("start" to JsonPrimitive(text)))

    private fun row(id: String, on: String? = null, number: Double? = null, checked: Boolean? = null): ViewRow {
        val typed = buildMap<String, JsonElement> {
            if (on != null) put("d", day(on))
            if (number != null) put("n", JsonPrimitive(number))
            if (checked != null) put("c", JsonPrimitive(checked))
        }
        return ViewRow(id, "", emptyList(), typed = typed)
    }

    private fun board(rows: List<ViewRow>, complete: Boolean = true) =
        ViewBoard("v1", "예산", "table", emptyList(), rows, 1L, null, columnInfo = listOf(amount, done, date, memo), rowsComplete = complete)

    private fun tile(fn: AggFn, prop: String = "", period: AggPeriod = AggPeriod.ALL, goal: Double? = null) =
        AggTile("t1", "v1", fn, propId = prop, dateProp = if (period == AggPeriod.ALL && fn != AggFn.STREAK) "" else "d", period = period, goal = goal)

    private fun compute(rows: List<ViewRow>, t: AggTile, weekStart: DayOfWeek = DayOfWeek.MONDAY, complete: Boolean = true) =
        Aggregator.compute(board(rows, complete), t, today, weekStart)

    // --- 기간

    private val dated = listOf(
        row("a", "2026-10-31"), row("b", "2026-11-01"), row("c", "2026-11-02"), row("d", "2026-11-04"),
        row("e", "2026-11-08"), row("i", "2026-11-08"), row("f", "2026-11-30"), row("g", "2026-12-01"), row("h"),
    )

    @Test
    fun countByPeriodUsesTheDatePropertyAndLeavesOutRowsWithoutADate() {
        assertEquals("9", compute(dated, tile(AggFn.COUNT)).text) // 전체는 날짜가 없는 행도 센다
        assertEquals("1", compute(dated, tile(AggFn.COUNT, period = AggPeriod.TODAY)).text)
        assertEquals(6, compute(dated, tile(AggFn.COUNT, period = AggPeriod.THIS_MONTH)).number!!.toInt()) // 11/1·2·4·8·8·30 (10/31·12/1·날짜 없음 제외)
    }

    @Test
    fun theWeekStartsOnTheChosenDay() {
        val week = tile(AggFn.COUNT, period = AggPeriod.THIS_WEEK)
        assertEquals(4, compute(dated, week, DayOfWeek.MONDAY).number!!.toInt()) // 11/2~11/8: 2·4·8·8
        assertEquals(3, compute(dated, week, DayOfWeek.SUNDAY).number!!.toInt()) // 11/1~11/7: 1·2·4 (8일 둘은 다음 주)
    }

    @Test
    fun aDateTimeCountsOnItsOwnDate() {
        val rows = listOf(row("a", "2026-11-04T23:30:00+09:00"), row("b", "2026-11-05T00:10:00+09:00"))
        assertEquals(1, compute(rows, tile(AggFn.COUNT, period = AggPeriod.TODAY)).number!!.toInt())
    }

    // --- 함수

    private val numbers = listOf(row("a", number = 100.0), row("b", number = 250.5), row("c"), row("d", number = 49.5))

    @Test
    fun sumAverageMinMaxSkipRowsWithoutANumber() {
        assertEquals("400", compute(numbers, tile(AggFn.SUM, "n")).text)
        assertEquals("133.3", compute(numbers, tile(AggFn.AVG, "n")).text) // (100 + 250.5 + 49.5) / 3 = 133.33…
        assertEquals("49.5", compute(numbers, tile(AggFn.MIN, "n")).text)
        assertEquals("250.5", compute(numbers, tile(AggFn.MAX, "n")).text)
    }

    @Test
    fun emptyInputsGiveZeroForSumAndADashForTheRest() {
        assertEquals("0", compute(emptyList(), tile(AggFn.SUM, "n")).text)
        assertEquals("0", compute(emptyList(), tile(AggFn.COUNT)).text)
        listOf(AggFn.AVG, AggFn.MIN, AggFn.MAX).forEach { assertEquals("—", compute(listOf(row("a")), tile(it, "n")).text) }
        assertEquals("—", compute(emptyList(), tile(AggFn.RATIO, "c")).text)
        assertNull(compute(emptyList(), tile(AggFn.RATIO, "c")).number)
    }

    @Test
    fun ratioIsTheCheckedShareOfTheRowsInThePeriod() {
        val rows = listOf(row("a", "2026-11-04", checked = true), row("b", "2026-11-04", checked = true), row("c", "2026-11-04", checked = true), row("d", "2026-11-04", checked = false), row("e", "2026-10-01", checked = true))
        assertEquals("75%", compute(rows, tile(AggFn.RATIO, "c", AggPeriod.TODAY)).text)
        assertEquals("80%", compute(rows, tile(AggFn.RATIO, "c")).text)
    }

    @Test
    fun largeNumbersGetThousandsSeparatorsAndSmallOnesKeepUpToTwoDecimals() {
        assertEquals("1,234,567", compute(listOf(row("a", number = 1_234_567.0)), tile(AggFn.SUM, "n")).text)
        assertEquals("0.12", compute(listOf(row("a", number = 0.123456)), tile(AggFn.SUM, "n")).text)
        assertEquals("-1,500", compute(listOf(row("a", number = -1500.0)), tile(AggFn.SUM, "n")).text)
    }

    // --- 연속 달성일(AG-04)

    private fun streak(vararg checked: String, unchecked: List<String> = emptyList(), now: LocalDate = today): AggResult {
        val rows = checked.map { row("c-$it", it, checked = true) } + unchecked.map { row("u-$it", it, checked = false) }
        return Aggregator.compute(board(rows), tile(AggFn.STREAK, "c"), now, DayOfWeek.MONDAY)
    }

    @Test
    fun streakCountsBackFromTodayWhenTodayIsChecked() {
        assertEquals("3일", streak("2026-11-02", "2026-11-03", "2026-11-04").text)
    }

    @Test
    fun anUncheckedTodayDoesNotBreakTheStreakItCountsFromYesterday() {
        assertEquals("2일", streak("2026-11-02", "2026-11-03").text)
        assertEquals("2일", streak("2026-11-02", "2026-11-03", unchecked = listOf("2026-11-04")).text) // 오늘 페이지가 있어도 미체크면 같다
    }

    @Test
    fun aMissedDayStopsTheStreak() {
        assertEquals("1일", streak("2026-11-01", "2026-11-03").text) // 11/2를 건너뜀: 어제(11/3)만
        assertEquals("0일", streak("2026-11-01", "2026-11-02").text) // 어제(11/3)부터 끊겼으니 0
        assertEquals("0일", streak().text)
    }

    @Test
    fun futureDatesAndRepeatedPagesOnOneDayDoNotInflateTheStreak() {
        assertEquals("1일", streak("2026-11-04", "2026-11-10", "2026-11-04").text)
        assertEquals("1일", streak("2026-11-04", unchecked = listOf("2026-11-04")).text) // 같은 날 체크된 페이지가 하나라도 있으면 그날은 달성
    }

    @Test
    fun theStreakCrossesMonthAndYearBoundaries() {
        assertEquals("3일", streak("2026-10-30", "2026-10-31", "2026-11-01", now = LocalDate.of(2026, 11, 1)).text)
        assertEquals("3일", streak("2025-12-30", "2025-12-31", "2026-01-01", now = LocalDate.of(2026, 1, 1)).text)
    }

    @Test
    fun theStreakIgnoresThePeriodSetting() {
        val rows = listOf(row("a", "2026-11-03", checked = true), row("b", "2026-11-04", checked = true))
        val t = tile(AggFn.STREAK, "c", AggPeriod.TODAY)
        assertEquals("2일", Aggregator.compute(board(rows), t, today, DayOfWeek.MONDAY).text)
    }

    // --- 목표(AG-02)

    private fun withGoal(sum: Double, goal: Double) = compute(listOf(row("a", number = sum)), tile(AggFn.SUM, "n", goal = goal))

    @Test
    fun goalShowsPercentOfGoalAndTheBarStopsAtFull() {
        assertEquals("목표 80%", withGoal(80.0, 100.0).goalText)
        assertEquals(0.8f, withGoal(80.0, 100.0).progress!!, 0.0001f)
        assertEquals("목표 29%", withGoal(29.0, 100.0).goalText) // 0.29 * 100 = 28.999999999999996이어도 29
        assertEquals("목표 99%", withGoal(99.6, 100.0).goalText) // 반올림으로 달성처럼 보이지 않는다
        assertEquals("목표 100%", withGoal(100.0, 100.0).goalText) // 딱 맞으면 초과가 아니다
    }

    @Test
    fun overGoalIsSaidInWordsNotOnlyByTheBar() {
        assertEquals("목표 12% 초과", withGoal(112.0, 100.0).goalText)
        assertEquals("목표 1% 초과", withGoal(100.4, 100.0).goalText) // 조금이라도 넘으면 0%가 아니라 1%
        assertEquals(1f, withGoal(250.0, 100.0).progress!!, 0.0001f)
    }

    @Test
    fun noGoalNoGoalTextAndAnEmptyValueHasNoProgress() {
        assertNull(compute(numbers, tile(AggFn.SUM, "n")).goalText)
        assertNull(compute(numbers, tile(AggFn.SUM, "n", goal = 0.0)).goalText)
        assertNull(compute(numbers, tile(AggFn.SUM, "n", goal = -5.0)).progress)
        val none = compute(listOf(row("a")), tile(AggFn.AVG, "n", goal = 10.0))
        assertNull(none.goalText)
        assertNull(none.progress)
    }

    @Test
    fun aNegativeValueHasNoBarProgress() {
        assertEquals(0f, withGoal(-5.0, 100.0).progress!!, 0.0001f)
    }

    // --- 부분 데이터·없는 속성

    @Test
    fun anIncompleteViewMarksTheResultPartial() {
        assertTrue(compute(numbers, tile(AggFn.SUM, "n"), complete = false).partial)
        assertFalse(compute(numbers, tile(AggFn.SUM, "n")).partial)
        assertTrue(compute(emptyList(), tile(AggFn.COUNT), complete = false).partial)
    }

    @Test
    fun aMissingOrWrongTypedPropertyGivesADashInsteadOfCrashing() {
        assertEquals("—", compute(numbers, tile(AggFn.SUM, "zzz")).text) // 보기에서 사라진 속성
        assertEquals("—", compute(numbers, tile(AggFn.SUM, "m")).text) // 숫자가 아닌 속성
        assertEquals("—", compute(numbers, tile(AggFn.RATIO, "n")).text) // 체크박스가 아닌 속성
        assertEquals("—", compute(numbers, AggTile("t", "v1", AggFn.COUNT, dateProp = "n", period = AggPeriod.TODAY)).text) // 날짜가 아닌 기간 속성
        assertEquals("—", compute(numbers, AggTile("t", "v1", AggFn.COUNT, dateProp = "zzz", period = AggPeriod.TODAY)).text)
    }

    @Test
    fun eachFunctionAcceptsOnlyItsPropertyTypes() {
        assertTrue(AggFn.SUM.accepts(amount))
        assertFalse(AggFn.SUM.accepts(done))
        assertTrue(AggFn.RATIO.accepts(done))
        assertTrue(AggFn.STREAK.accepts(done))
        assertFalse(AggFn.STREAK.accepts(amount))
        assertFalse(AggFn.COUNT.needsProperty)
        assertTrue(AggFn.MAX.needsProperty)
        assertTrue(AggFn.STREAK.needsDate)
        assertFalse(AggFn.SUM.needsDate)
    }
}
