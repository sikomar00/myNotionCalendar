package com.ysdoing.notioncal.widget

import com.ysdoing.notioncal.core.data.view.AggFn
import com.ysdoing.notioncal.core.data.view.AggPeriod
import com.ysdoing.notioncal.core.data.view.ViewColumn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
    fun editingATileFillsTheFormFromItAndSavesItUnderTheSameId() {
        val tile = com.ysdoing.notioncal.core.data.view.AggTile("t3", "v1", AggFn.SUM, "n", "d", AggPeriod.THIS_MONTH, 5000.0, "식비 합계", "blue")
        val draft = TileDraft.from(tile)
        assertEquals(TileDraft("v1", AggFn.SUM, "n", "d", AggPeriod.THIS_MONTH, "5000", "식비 합계", "blue", "t3"), draft)
        assertEquals(tile, draft.toTile()) // 왕복: 번호·색·목표가 그대로
        assertEquals("5.5", TileDraft.from(tile.copy(goal = 5.5)).goalText)
        // 고친 값으로 저장하면 같은 번호의 새 타일이다. 새 타일 양식은 번호가 비어 '추가' 때 붙는다.
        assertEquals("t3", draft.copy(color = "red", goalText = "").toTile()!!.id)
        assertEquals("red", draft.copy(color = "red").toTile()!!.color)
        assertEquals("t", TileDraft().withView("v1", columns).toTile()!!.id)
    }

    @Test
    fun anUnknownColorIsDroppedSoOnlyTheNotionPaletteIsStored() {
        assertEquals("", TileDraft().withView("v1", columns).copy(color = "neon").toTile()!!.color)
        assertEquals("green", TileDraft().withView("v1", columns).copy(color = " green ").toTile()!!.color)
        assertEquals("", TileDraft().withView("v1", columns).toTile()!!.color)
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
    fun changingBetweenFunctionsThatTakeTheSamePropertyKeepsTheChosenOne() {
        val picked = TileDraft().withView("v1", columns).withFn(AggFn.SUM, columns).copy(propId = "q")
        listOf(AggFn.AVG, AggFn.MIN, AggFn.MAX, AggFn.SUM).forEach { assertEquals("q", picked.withFn(it, columns).propId) }
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
    fun theGoalAcceptsPercentSignsThousandsCommasAndDecimalCommas() {
        assertEquals(80.0, TileDraft(viewId = "v1", goalText = "80%").toTile()!!.goal!!, 0.0)
        assertEquals(1500.0, TileDraft(viewId = "v1", goalText = "1,500").toTile()!!.goal!!, 0.0)
        assertEquals(1.5, TileDraft(viewId = "v1", goalText = "1,5").toTile()!!.goal!!, 0.0) // 쉼표 소수점을 열 배로 읽지 않는다
        assertEquals(12345.5, TileDraft(viewId = "v1", goalText = "12,345.5").toTile()!!.goal!!, 0.0)
        assertNull(TileDraft(viewId = "v1", goalText = "").toTile()!!.goal) // 비우면 목표 없음
    }

    @Test
    fun aGoalThatCannotBeReadOrIsNotPositiveBlocksTheTileAndIsFlagged() {
        listOf("abc", "-5", "0", "8 0").forEach {
            val draft = TileDraft(viewId = "v1", goalText = it)
            assertNull("목표 '$it'", draft.toTile())
            assertTrue("목표 '$it'", draft.goalInvalid)
        }
        assertFalse(TileDraft(viewId = "v1", goalText = "").goalInvalid)
        assertFalse(TileDraft(viewId = "v1", goalText = "80").goalInvalid)
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
