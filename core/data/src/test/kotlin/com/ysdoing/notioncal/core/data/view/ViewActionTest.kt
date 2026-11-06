package com.ysdoing.notioncal.core.data.view

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 12c IX-07 액션 버튼 모델: 읽을 때 정리, JSON 왕복, 편집(상한·번호 부여·바꾸기). */
class ViewActionTest {
    private val tomorrow = ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1)
    private val done = ActionChange("st", ActionOp.SET, "o3")
    private fun action(id: String = "a1", name: String = "내일로", view: String = "v1", vararg changes: ActionChange = arrayOf(tomorrow)) = ViewAction(id, view, name, changes.toList())

    @Test
    fun normalizingTrimsTheNameAndDropsChangesThatMeanNothing() {
        val messy = ViewAction(" a1 ", " v1 ", "  내일로  ", listOf(ActionChange(" wh ", ActionOp.SHIFT_DAYS, days = 1), ActionChange("x", ActionOp.SHIFT_DAYS, days = 0), ActionChange("y", ActionOp.SET, "  "), ActionChange("wh", ActionOp.TODAY)))
        assertEquals(ViewAction("a1", "v1", "내일로", listOf(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1))), messy.normalized()) // 같은 속성은 앞의 것만
    }

    @Test
    fun anActionWithoutNameViewIdOrAnyChangeIsDropped() {
        assertNull(action(name = " ").normalized())
        assertNull(action(view = "").normalized())
        assertNull(action(id = "").normalized())
        assertNull(ViewAction("a1", "v1", "빈 액션", emptyList()).normalized())
        assertNull(ViewAction("a1", "v1", "너무 멀리", listOf(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1000))).normalized())
    }

    @Test
    fun aNameIsCutAndOnlyThreeChangesAreKept() {
        val long = ViewAction("a1", "v1", "가".repeat(20), (1..5).map { ActionChange("p$it", ActionOp.SET, "v") }).normalized()!!
        assertEquals(ViewActions.NAME_MAX, long.name.length)
        assertEquals(listOf("p1", "p2", "p3"), long.changes.map { it.propId })
    }

    @Test
    fun aNameIsCutByCodePointsSoAnEmojiIsNeverSplit() {
        val name = "🔥".repeat(ViewActions.NAME_MAX + 3)
        val cut = ViewAction("a1", "v1", name, listOf(ActionChange("wh", ActionOp.TODAY))).normalized()!!.name
        assertEquals(ViewActions.NAME_MAX, cut.codePointCount(0, cut.length))
        assertEquals("🔥".repeat(ViewActions.NAME_MAX), cut)
    }

    @Test
    fun numberTextMustBeFinite() {
        assertEquals(40.0, ViewActions.parseNumber(" 40 "))
        assertEquals(-1.5, ViewActions.parseNumber("-1.5"))
        listOf("", "abc", "NaN", "Infinity", "-Infinity", "1e999").forEach { assertNull(it, ViewActions.parseNumber(it)) }
    }

    @Test
    fun theListRoundTripsThroughJsonAndBrokenInputReadsAsEmpty() {
        val list = listOf(action(), action("a2", "완료로", "v1", done, ActionChange("dn", ActionOp.SET, "true")))
        assertEquals(list, ViewActions.parse(ViewActions.encode(list)))
        assertEquals("", ViewActions.encode(emptyList()))
        listOf("", "   ", "not json", "{}", """{"a":1}""", "[1,2]").forEach { assertEquals(emptyList<ViewAction>(), ViewActions.parse(it)) }
    }

    @Test
    fun parsingDropsOnlyTheBrokenActionsAndDuplicateIdsAndKeepsAtMostFive() {
        val text = """[{"id":"a1","viewId":"v1","name":"좋음","changes":[{"propId":"wh","op":"TODAY"}]},
            {"id":"a2","viewId":"v1","name":"","changes":[{"propId":"wh","op":"TODAY"}]},
            {"id":"a1","viewId":"v2","name":"겹침","changes":[{"propId":"wh","op":"TODAY"}]},
            {"id":"a3","viewId":"v1","name":"모르는 연산","changes":[{"propId":"wh","op":"EXPLODE"}]},
            {"id":"a4","viewId":"v1","name":"넷","changes":[{"propId":"wh","op":"TODAY"}],"unknownField":1}]"""
        assertEquals(listOf("a1", "a4"), ViewActions.parse(text).map { it.id })
        val many = (1..8).map { """{"id":"b$it","viewId":"v1","name":"n$it","changes":[{"propId":"wh","op":"TODAY"}]}""" }.joinToString(",", "[", "]")
        assertEquals(ViewActions.LIMIT, ViewActions.parse(many).size)
    }

    @Test
    fun addingNumbersTheActionInTheFirstFreeSlotAndStopsAtTheLimit() {
        var list = emptyList<ViewAction>()
        repeat(ViewActions.LIMIT) { list = ViewActions.add(list, action(id = ""))!! }
        assertEquals(listOf("a1", "a2", "a3", "a4", "a5"), list.map { it.id })
        assertNull(ViewActions.add(list, action()))
        val gap = ViewActions.remove(list, "a2")
        assertEquals("a2", ViewActions.add(gap, action())!!.last().id)
        assertNull(ViewActions.add(emptyList(), action(name = ""))) // 정리하면 사라지는 액션은 담지 않는다
    }

    @Test
    fun replacingKeepsTheIdAndPositionAndRefusesAnEmptyResult() {
        val list = listOf(action("a1"), action("a2", "완료로", "v1", done))
        val replaced = ViewActions.replace(list, "a1", action("zzz", "모레로", "v1", ActionChange("wh", ActionOp.SHIFT_DAYS, days = 2)))!!
        assertEquals(listOf("a1", "a2"), replaced.map { it.id })
        assertEquals("모레로", replaced.first().name)
        assertNull(ViewActions.replace(list, "a1", action(name = "")))
        assertEquals(list, ViewActions.replace(list, "nope", action())) // 없는 번호는 그대로
    }

    @Test
    fun forViewKeepsOnlyThatViewsActionsInOrder() {
        val list = listOf(action("a1", view = "v1"), action("a2", view = "v2"), action("a3", view = "v1"))
        assertEquals(listOf("a1", "a3"), ViewActions.forView(list, "v1").map { it.id })
    }
}
