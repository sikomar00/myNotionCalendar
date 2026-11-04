package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Phase 11e 설정의 타일 추가 양식. 화면 대신 순수 부분(함수에 맞는 속성, 보기·함수를 바꿀 때 기본값, 양식 → 타일)을 본다.
 */
class WidgetTileSettingsTest {
    private val title = ViewColumn("t", "이름", "title")
    private val amount = ViewColumn("n", "금액", "number")
    private val count = ViewColumn("q", "수량", "number")
    private val done = ViewColumn("c", "완료", "checkbox")
    private val day = ViewColumn("d", "날짜", "date")
    private val due = ViewColumn("e", "마감", "date")
    private val columns = listOf(title, amount, count, done, day, due)

    @Test
    fun eachFunctionOffersOnlyItsPropertyTypes() {
        assertEquals(listOf(amount, count), propertyChoices(AggFn.SUM, columns))
        assertEquals(listOf(amount, count), propertyChoices(AggFn.MAX, columns))
        assertEquals(listOf(done), propertyChoices(AggFn.RATIO, columns))
        assertEquals(listOf(done), propertyChoices(AggFn.STREAK, columns))
        assertEquals(emptyList<ViewColumn>(), propertyChoices(AggFn.COUNT, columns)) // 개수는 속성이 필요 없다
        assertEquals(emptyList<ViewColumn>(), propertyChoices(AggFn.SUM, listOf(title, done))) // 숫자 속성이 없는 보기
    }

    @Test
    fun dateChoicesAreTheDatePropertiesOnly() {
        assertEquals(listOf(day, due), dateChoices(columns))
        assertEquals(emptyList<ViewColumn>(), dateChoices(listOf(title, amount)))
    }

    @Test
    fun pickingAViewStartsFromCountWithTheFirstDateProperty() {
        val draft = TileDraft().withView("v1", columns)
        assertEquals(TileDraft(viewId = "v1", fn = AggFn.COUNT, propId = "", dateProp = "d"), draft)
    }

    @Test
    fun changingTheFunctionPicksTheFirstFittingPropertyAndKeepsTheDate() {
        val sum = TileDraft().withView("v1", columns).withFn(AggFn.SUM, columns)
        assertEquals("n", sum.propId)
        assertEquals("d", sum.dateProp)
        val ratio = sum.withFn(AggFn.RATIO, columns)
        assertEquals("c", ratio.propId) // 숫자 속성은 비율에 맞지 않아 바뀐다
        assertEquals("", ratio.withFn(AggFn.COUNT, columns).propId)
    }

    @Test
    fun aViewWithoutDatesLeavesTheDatePropertyEmptyAndStreakCannotBeBuilt() {
        val noDates = listOf(title, amount, done)
        val draft = TileDraft().withView("v1", noDates).withFn(AggFn.STREAK, noDates)
        assertEquals("", draft.dateProp)
        assertNull(draft.toTile()) // 연속 달성일은 날짜 속성이 있어야 한다
    }

    @Test
    fun theDraftBecomesATileOnlyWhenItHasWhatTheFunctionNeeds() {
        assertNull(TileDraft().toTile()) // 보기를 안 골랐다
        assertNull(TileDraft(viewId = "v1", fn = AggFn.SUM).toTile()) // 속성이 없다
        assertNotNull(TileDraft(viewId = "v1", fn = AggFn.COUNT).toTile())
        val tile = TileDraft(viewId = "v1", fn = AggFn.SUM, propId = "n", dateProp = "d", period = AggPeriod.THIS_MONTH, goalText = " 5,000 ", label = "  식비 ").toTile()!!
        assertEquals(5000.0, tile.goal!!, 0.0)
        assertEquals("식비", tile.label)
        assertEquals(AggPeriod.THIS_MONTH, tile.period)
    }

    @Test
    fun aBadOrNonPositiveGoalMeansNoGoalInsteadOfBlockingTheTile() {
        assertNull(TileDraft(viewId = "v1", goalText = "abc").toTile()!!.goal)
        assertNull(TileDraft(viewId = "v1", goalText = "-5").toTile()!!.goal)
        assertNull(TileDraft(viewId = "v1", goalText = "0").toTile()!!.goal)
        assertNull(TileDraft(viewId = "v1", goalText = "").toTile()!!.goal)
    }

    @Test
    fun aPeriodWithoutADatePropertyFallsBackToAll() {
        val tile = TileDraft(viewId = "v1", fn = AggFn.COUNT, dateProp = "", period = AggPeriod.TODAY).toTile()!!
        assertEquals(AggPeriod.ALL, tile.period)
    }

    @Test
    fun theTabAndTileNameInputsAreCutByCodePointsNotUtf16() {
        assertEquals("가나다", "가나다라".takeCodePoints(3))
        assertEquals("📚📖", "📚📖📕".takeCodePoints(2)) // 이모지를 반으로 자르지 않는다
        assertEquals("짧다", "짧다".takeCodePoints(20))
    }
}
