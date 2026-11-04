package com.ysdoing.notioncal.core.data.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 11d WL-01~03: 위젯 배치(단일·탭·섹션)와 그 편집 규칙. 탭 최대 5, 섹션 최대 4, 같은 보기는 한 번만, 높이는 비율 1~3. */
class WidgetLayoutTest {
    private fun layout(mode: LayoutMode, vararg ids: String) = ids.fold(WidgetLayout(mode)) { acc, id -> acc.add(id)!! }

    @Test
    fun aLayoutRoundTripsThroughItsText() {
        val original = layout(LayoutMode.TABS, "a", "b").withLabel("a", "할 일").withWeight("b", 2)
        val parsed = WidgetLayout.parse(WidgetLayout.encode(original))
        assertEquals(original, parsed)
        assertEquals(listOf("a" to "할 일", "b" to ""), parsed.items.map { it.viewId to it.label })
        assertEquals(2, parsed.items[1].weight)
    }

    @Test
    fun tabsTakeFiveSectionsFourAndASingleOne() {
        assertEquals(listOf(1, 5, 4), listOf(LayoutMode.SINGLE, LayoutMode.TABS, LayoutMode.SECTIONS).map { it.limit })
        val tabs = layout(LayoutMode.TABS, "a", "b", "c", "d", "e")
        assertNull(tabs.add("f")) // 여섯째 탭은 못 넣는다
        val sections = layout(LayoutMode.SECTIONS, "a", "b", "c", "d")
        assertNull(sections.add("e"))
        assertNull(layout(LayoutMode.SINGLE, "a").add("b"))
    }

    @Test
    fun theSameViewCannotBeAddedTwiceOrBlank() {
        val tabs = layout(LayoutMode.TABS, "a")
        assertNull(tabs.add("a"))
        assertNull(tabs.add("  "))
    }

    @Test
    fun removingAndMovingKeepTheOtherItemsInOrder() {
        val tabs = layout(LayoutMode.TABS, "a", "b", "c")
        assertEquals(listOf("a", "c"), tabs.remove("b").items.map { it.viewId })
        assertEquals(listOf("b", "a", "c"), tabs.move("b", -1).items.map { it.viewId })
        assertEquals(listOf("a", "c", "b"), tabs.move("b", +1).items.map { it.viewId })
        assertEquals(tabs, tabs.move("a", -1)) // 맨 앞은 더 앞으로 못 간다
        assertEquals(tabs, tabs.move("c", +1))
        assertEquals(tabs, tabs.move("없음", +1))
    }

    @Test
    fun switchingToASmallerModeKeepsTheFirstItemsOnly() {
        val tabs = layout(LayoutMode.TABS, "a", "b", "c", "d", "e")
        assertEquals(listOf("a", "b", "c", "d"), tabs.withMode(LayoutMode.SECTIONS).items.map { it.viewId })
        assertEquals(listOf("a"), tabs.withMode(LayoutMode.SINGLE).items.map { it.viewId })
        assertEquals(LayoutMode.TABS, tabs.withMode(LayoutMode.SECTIONS).withMode(LayoutMode.TABS).mode) // 모드만 바뀌고 남은 항목은 그대로
    }

    @Test
    fun weightIsKeptBetweenOneAndThreeAndLabelsAreTrimmed() {
        val sections = layout(LayoutMode.SECTIONS, "a")
        assertEquals(3, sections.withWeight("a", 9).items.single().weight)
        assertEquals(1, sections.withWeight("a", 0).items.single().weight)
        assertEquals("점심", sections.withLabel("a", "  점심 ").items.single().label)
    }

    @Test
    fun readingFixesWhatAHandEditedOrOldTextGotWrong() {
        val parsed = WidgetLayout.parse(
            """{"mode":"SECTIONS","items":[{"viewId":"a","weight":7},{"viewId":"a"},{"viewId":" "},{"viewId":"b","weight":0},{"viewId":"c"},{"viewId":"d"},{"viewId":"e"}]}""",
        )
        // 같은 보기·빈 ID는 버리고, 비율은 1~3으로, 상한(섹션 4)을 넘는 것은 자른다.
        assertEquals(listOf("a", "b", "c", "d"), parsed.items.map { it.viewId })
        assertEquals(listOf(3, 1, 1, 1), parsed.items.map { it.weight })
    }

    @Test
    fun brokenOrEmptyTextIsAnEmptySingleLayout() {
        listOf("", "  ", "깨진 글자", "[]", "null", "{}").forEach { assertEquals(it, WidgetLayout(), WidgetLayout.parse(it)) }
        assertEquals("", WidgetLayout.encode(WidgetLayout())) // 배치가 없으면 열을 비워 둔다
    }

    @Test
    fun anItemShowsItsLabelOrTheViewName() {
        val item = LayoutItem("a", label = "")
        assertEquals("투두", item.title("투두"))
        assertEquals("할 일", item.copy(label = "할 일").title("투두"))
        assertEquals("보기", item.title("")) // 이름 없는 보기도 노션에서 허용된다
    }

    @Test
    fun checkingAViewInSingleModeReplacesTheChosenOne() {
        val single = WidgetLayout().add("a")!!
        assertEquals(listOf("b"), single.toggle("b", on = true)!!.items.map { it.viewId })
        assertEquals(LayoutMode.SINGLE, single.toggle("b", on = true)!!.mode)
        assertEquals(emptyList<LayoutItem>(), single.toggle("a", on = false)!!.items)
    }

    @Test
    fun checkingAViewInTabsAddsItUntilTheLimitAndNeverTwice() {
        var layout = WidgetLayout(LayoutMode.TABS)
        "abcde".forEach { layout = layout.toggle(it.toString(), on = true)!! }
        assertNull(layout.toggle("f", on = true)) // 6번째는 못 담는다
        assertNull(layout.toggle("a", on = true)) // 같은 보기를 또 담지 못한다
        assertEquals(listOf("a", "b", "c", "d"), layout.toggle("e", on = false)!!.items.map { it.viewId })
    }

    @Test
    fun theTabIndexFollowsTheViewThatWasShownWhenTheLayoutIsEdited() {
        val old = layout(LayoutMode.TABS, "a", "b", "c")
        assertEquals(1, old.remove("a").tabIndexAfter(old, 2)) // 보던 c: [b, c]에서 1번
        assertEquals(0, old.move("c", -2).tabIndexAfter(old, 2)) // [c, a, b]
        assertEquals(0, old.remove("c").tabIndexAfter(old, 2)) // 보던 보기를 뺐으면 첫 탭
        assertEquals(1, old.tabIndexAfter(old, 1))
        assertEquals(2, old.tabIndexAfter(old, 9)) // 번호가 탭 수를 넘으면 마지막 탭을 보던 것으로 본다
        assertEquals(0, WidgetLayout(LayoutMode.TABS).tabIndexAfter(WidgetLayout(LayoutMode.TABS), 3))
    }

    // --- Phase 11e 집계 타일 줄: 섹션에서만, 위젯당 한 줄(타일 최대 6개), 섹션 4개 상한에 참여한다.

    private fun tile(view: String = "v1", fn: AggFn = AggFn.COUNT, prop: String = "") = AggTile("", view, fn, propId = prop)

    @Test
    fun tilesCanOnlyBeAddedInSectionsAndGetTheFirstFreeId() {
        assertNull(layout(LayoutMode.TABS, "a").addTile(tile()))
        assertNull(layout(LayoutMode.SINGLE, "a").addTile(tile()))
        val l = layout(LayoutMode.SECTIONS, "a").addTile(tile())!!.addTile(tile(fn = AggFn.SUM, prop = "n"))!!
        assertEquals(listOf("t1", "t2"), l.tiles.map { it.id })
        assertEquals(listOf("t2"), l.removeTile("t1").tiles.map { it.id })
        assertEquals(listOf("t2", "t1"), l.removeTile("t1").addTile(tile())!!.tiles.map { it.id }) // 빈 번호를 다시 쓴다
    }

    @Test
    fun aTileRowHoldsSixTilesAtMost() {
        var l = layout(LayoutMode.SECTIONS, "a")
        repeat(6) { l = l.addTile(tile())!! }
        assertNull(l.addTile(tile()))
        assertEquals(6, l.tiles.size)
    }

    @Test
    fun theTileRowTakesOneOfTheFourSectionSlots() {
        assertNull(layout(LayoutMode.SECTIONS, "a", "b", "c", "d").addTile(tile())) // 섹션 넷이 이미 찼다
        val three = layout(LayoutMode.SECTIONS, "a", "b", "c").addTile(tile())!!
        assertNull(three.add("d")) // 타일 줄이 한 자리를 차지했다
        assertEquals(2, three.addTile(tile())!!.tiles.size) // 타일을 더 넣는다고 자리가 더 들지는 않는다
        assertEquals(4, three.removeTile("t1").add("d")!!.items.size) // 타일 줄을 비우면 보기 자리가 돌아온다
    }

    @Test
    fun goingBackToSectionsKeepsRoomForTheTileRow() {
        val tabs = layout(LayoutMode.SECTIONS, "a", "b", "c").addTile(tile())!!.withMode(LayoutMode.TABS).add("d")!!.add("e")!!
        assertEquals(5, tabs.items.size)
        assertEquals(1, tabs.tiles.size) // 타일은 탭에서도 JSON에 남는다
        val back = tabs.withMode(LayoutMode.SECTIONS)
        assertEquals(listOf("a", "b", "c"), back.items.map { it.viewId }) // 넷이 아니라 셋: 타일 줄 자리
        assertEquals(1, back.tiles.size)
    }

    @Test
    fun parseKeepsGoodTilesAndDropsBrokenOnesWithoutLosingTheViews() {
        val text = """{"mode":"SECTIONS","items":[{"viewId":"a"}],"tiles":[
            {"id":"t1","viewId":"v1","fn":"COUNT"},
            {"id":"t2","viewId":"v1","fn":"BOGUS"},
            {"id":"t3","viewId":"","fn":"COUNT"},
            {"id":"t4","viewId":"v1","fn":"SUM"},
            {"id":"t5","viewId":"v1","fn":"SUM","propId":"n","goal":-3},
            {"id":"t1","viewId":"v2","fn":"COUNT"},
            {"id":"t6","viewId":"v1","fn":"STREAK","propId":"c"},
            {"id":"t7","viewId":"v1","fn":"COUNT","period":"THIS_WEEK"},
            {"id":"","viewId":"v1","fn":"COUNT"}]}"""
        val l = WidgetLayout.parse(text)
        assertEquals(listOf("a"), l.items.map { it.viewId })
        assertEquals(listOf("t1", "t5", "t7"), l.tiles.map { it.id })
        assertNull(l.tiles[1].goal) // 목표 0 이하는 목표 없음
        assertEquals(AggPeriod.ALL, l.tiles[2].period) // 날짜 속성이 없으면 기간은 전체
        assertEquals("v1", l.tiles[0].viewId) // 같은 번호의 뒤쪽(v2)은 버린다
    }

    @Test
    fun aBrokenTilesFieldDoesNotLoseTheLayout() {
        assertEquals(listOf("a"), WidgetLayout.parse("""{"mode":"SECTIONS","items":[{"viewId":"a"}],"tiles":"oops"}""").items.map { it.viewId })
        assertEquals(emptyList<AggTile>(), WidgetLayout.parse("""{"mode":"SECTIONS","items":[{"viewId":"a"}],"tiles":"oops"}""").tiles)
    }

    @Test
    fun tilesRoundTripThroughEncodeAndParseAndAtMostSixAreRead() {
        val l = layout(LayoutMode.SECTIONS, "a").addTile(AggTile("", "v1", AggFn.SUM, propId = "n", dateProp = "d", period = AggPeriod.THIS_MONTH, goal = 5000.0, label = "식비"))!!
        assertEquals(l, WidgetLayout.parse(WidgetLayout.encode(l)))
        val many = (1..8).joinToString(",") { """{"id":"t$it","viewId":"v1","fn":"COUNT"}""" }
        assertEquals(6, WidgetLayout.parse("""{"mode":"SECTIONS","tiles":[$many]}""").tiles.size)
    }

    @Test
    fun boardIdsAreTheViewsToReadWithoutRepeatsAndTilesOnlyCountInSections() {
        val sections = layout(LayoutMode.SECTIONS, "a", "b").addTile(tile("v1"))!!.addTile(tile("a"))!!
        assertEquals(listOf("a", "b", "v1"), sections.boardIds())
        assertEquals(listOf("a", "b"), sections.withMode(LayoutMode.TABS).boardIds()) // 탭에서는 타일을 그리지 않으니 읽지도 않는다
    }
}
