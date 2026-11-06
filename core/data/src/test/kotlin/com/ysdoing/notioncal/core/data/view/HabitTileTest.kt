package com.ysdoing.notioncal.core.data.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 12e 해빗 격자 타일: 정규화(날짜 속성·습관 1~3개), JSON 호환, 위젯당 하나(도넛과 별개로 종류마다 하나). */
class HabitTileTest {
    private fun habits(vararg props: String, date: String = "d", id: String = "t") =
        AggTile(id, "v1", dateProp = date, kind = TileKind.HABITS, habitProps = props.toList())

    @Test
    fun aHabitTileNeedsADatePropertyAndAtLeastOneHabitAndDropsDuplicatesAndExtras() {
        assertNull(habits("c1", date = "").normalized())
        assertNull(habits().normalized())
        assertNull(habits(" ", "").normalized())
        assertEquals(listOf("c1", "c2", "c3"), habits(" c1 ", "c2", "c1", "c3", "c4").normalized()!!.habitProps) // 공백·중복·넷째는 뺀다
        assertNull(AggTile("", "v1", dateProp = "d", kind = TileKind.HABITS, habitProps = listOf("c1")).normalized()) // 번호가 없다
    }

    @Test
    fun aHabitTileLosesFunctionGoalColorAndGroup() {
        val tile = AggTile("t", "v1", AggFn.SUM, "n", "d", AggPeriod.THIS_MONTH, 5.0, "습관", "blue", TileKind.HABITS, "g", listOf("c1")).normalized()!!
        assertEquals(listOf(AggFn.COUNT, "", AggPeriod.ALL, null, "", ""), listOf(tile.fn, tile.propId, tile.period, tile.goal, tile.color, tile.groupProp))
        assertEquals("습관", tile.label)
    }

    @Test
    fun otherKindsIgnoreStrayHabitProps() {
        assertEquals(emptyList<String>(), AggTile("t", "v1", kind = TileKind.AGG, habitProps = listOf("c1")).normalized()!!.habitProps)
    }

    @Test
    fun aTileJsonWithoutHabitPropsReadsAsEmptyAndAHabitTileRoundTrips() {
        val old = WidgetLayout.parse("""{"mode":"SECTIONS","tiles":[{"id":"t1","viewId":"v1","fn":"COUNT"}]}""")
        assertEquals(emptyList<String>(), old.tiles.single().habitProps)
        val layout = WidgetLayout(LayoutMode.SECTIONS).addTile(habits("c1", "c2"))!!
        val back = WidgetLayout.parse(WidgetLayout.encode(layout)).tiles.single()
        assertEquals(listOf(TileKind.HABITS, listOf("c1", "c2"), "d"), listOf(back.kind, back.habitProps, back.dateProp))
    }

    @Test
    fun eachSpecialKindIsLimitedToOnePerWidgetButTheyCoexist() {
        val donut = AggTile("", "v1", AggFn.COUNT, dateProp = "d", kind = TileKind.DONUT, groupProp = "g")
        var layout = WidgetLayout(LayoutMode.SECTIONS).addTile(habits("c1"))!!
        assertNull(layout.addTile(habits("c2"))) // 해빗 격자는 하나까지
        layout = layout.addTile(donut)!! // 도넛은 따로 센다
        assertNull(layout.addTile(donut))
        assertNotNull(layout.addTile(AggTile("", "v1", AggFn.COUNT)))
        // 바꿀 때도 같다: 다른 해빗 격자가 이미 있는데 해빗 격자로 바꾸면 그대로
        val agg = layout.addTile(AggTile("", "v1", AggFn.COUNT))!!
        val aggId = agg.tiles.last().id
        assertEquals(agg, agg.replaceTile(aggId, habits("c2")))
    }

    @Test
    fun parsingKeepsOnlyTheFirstHabitTilePerWidget() {
        val json = """{"mode":"SECTIONS","tiles":[
            {"id":"a","viewId":"v1","dateProp":"d","kind":"HABITS","habitProps":["c1"]},
            {"id":"b","viewId":"v1","dateProp":"d","kind":"HABITS","habitProps":["c2"]},
            {"id":"c","viewId":"v1","fn":"COUNT"}]}"""
        assertEquals(listOf("a", "c"), WidgetLayout.parse(json).tiles.map { it.id })
    }
}
