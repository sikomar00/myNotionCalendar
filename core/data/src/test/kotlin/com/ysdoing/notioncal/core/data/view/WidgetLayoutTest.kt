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
}
