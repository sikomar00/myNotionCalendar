package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Phase 11c AD-05: 위젯당 최대 5개의 프리셋(이름 + 값 묶음)을 JSON 글자 하나로 저장한다. */
class ViewPresetsTest {
    private val lunch = ViewPreset("점심", mapOf("c" to JsonPrimitive("a"), "pg" to JsonPrimitive(2.0)))

    @Test
    fun aPresetListRoundTripsThroughItsText() {
        val text = ViewPresets.add("", lunch)!!
        assertEquals(listOf(lunch), ViewPresets.parse(text))
        assertEquals(emptyList<ViewPreset>(), ViewPresets.parse(""))
        assertEquals(emptyList<ViewPreset>(), ViewPresets.parse("깨진 글자")) // 깨졌으면 없는 것으로 읽는다
    }

    @Test
    fun addingTheSameNameReplacesItAndTheOrderIsKept() {
        var text = ViewPresets.add("", lunch)!!
        text = ViewPresets.add(text, ViewPreset("커피"))!!
        text = ViewPresets.add(text, ViewPreset("점심", mapOf("c" to JsonPrimitive("b"))))!!
        assertEquals(listOf("점심", "커피"), ViewPresets.parse(text).map { it.name })
        assertEquals(JsonPrimitive("b"), ViewPresets.parse(text)[0].props["c"]) // 같은 이름은 새 값으로 바뀐다
    }

    @Test
    fun aSixthPresetIsRefusedAndABlankNameToo() {
        var text = ""
        for (n in 1..ViewPresets.MAX) text = ViewPresets.add(text, ViewPreset("이름$n"))!!
        assertNull(ViewPresets.add(text, ViewPreset("여섯째")))
        assertEquals(ViewPresets.MAX, ViewPresets.parse(text).size)
        assertEquals(listOf("이름1"), ViewPresets.parse(ViewPresets.add(text, ViewPreset("이름1"))!!).map { it.name }.take(1)) // 같은 이름은 꽉 차도 바꿀 수 있다
        assertNull(ViewPresets.add("", ViewPreset("   ")))
    }

    @Test
    fun removingByNameLeavesTheOthers() {
        val text = ViewPresets.add(ViewPresets.add("", lunch)!!, ViewPreset("커피"))!!
        assertEquals(listOf("커피"), ViewPresets.parse(ViewPresets.remove(text, "점심")).map { it.name })
        assertEquals(text, ViewPresets.remove(text, "없는 이름"))
    }
}
