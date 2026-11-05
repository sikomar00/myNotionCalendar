package com.ysdoing.notioncal.feature.quicksheet

import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** Phase 11b ED-01·02: 보기 행 편집 시트의 순수 계산 — 어떤 속성을 편집·잠금으로 보일지, 무엇이 바뀌었는지, 날짜 다루기. */
class ViewRowEditingTest {
    private val title = ViewColumn("t", "이름", "title")
    private val tags = ViewColumn("tg", "태그", "multi_select", listOf(ViewChoice("a", "가"), ViewChoice("b", "나"), ViewChoice("c", "다")))
    private val status = ViewColumn("st", "상태", "status", listOf(ViewChoice("o1", "할 일"), ViewChoice("o2", "완료")))
    private val done = ViewColumn("dn", "완료", "checkbox")
    private val pages = ViewColumn("pg", "쪽수", "number")
    private val doc = ViewColumn("tx", "메모", "rich_text")
    private val formula = ViewColumn("fm", "점수", "formula")
    private val relation = ViewColumn("rl", "관련", "relation")

    private fun board(vararg columns: ViewColumn, names: List<String> = columns.map { it.name }) =
        ViewBoard("v1", "할 일", "table", names, emptyList(), 1, null, columns.toList())

    @Test
    fun propsFollowTheViewOrderAndSplitIntoEditableAndLocked() {
        val b = board(title, formula, tags, relation, names = listOf("이름", "점수", "태그", "관련", "지운속성"))
        val row = ViewRow("p1", "u", listOf("할 일", "7", "가, 나", "", "?"))
        val props = rowProps(b, row)
        assertEquals(listOf("이름", "점수", "태그", "관련", "지운속성"), props.map { it.name })
        assertEquals(listOf(true, false, true, false, false), props.map { it is RowProp.Editable })
        assertEquals(LockReason.COMPUTED, (props[1] as RowProp.Locked).reason) // 수식은 노션이 계산한다
        assertEquals("7", (props[1] as RowProp.Locked).shown)
        assertEquals(LockReason.ELSEWHERE, (props[3] as RowProp.Locked).reason) // 연결은 노션에서 고친다
        assertEquals(LockReason.ELSEWHERE, (props[4] as RowProp.Locked).reason) // 쓰기 정보에 없는 속성
    }

    @Test
    fun onlyChangedPropertiesAreReturnedAndClearingIsNull() {
        val columns = listOf(title, status, done, pages, doc)
        val initial = mapOf<String, kotlinx.serialization.json.JsonElement>("t" to JsonPrimitive("첫째"), "st" to JsonPrimitive("o1"), "pg" to JsonPrimitive(12.0), "tx" to JsonPrimitive("메모"))
        assertEquals(emptyMap<String, Any>(), changedValues(columns, initial, initial))
        val edited = initial + mapOf("t" to JsonPrimitive("둘째"), "dn" to JsonPrimitive(true), "pg" to JsonNull, "tx" to JsonPrimitive(""))
        assertEquals(
            mapOf("t" to JsonPrimitive("둘째"), "dn" to JsonPrimitive(true), "pg" to JsonNull, "tx" to JsonPrimitive("")),
            changedValues(columns, initial, edited),
        )
    }

    @Test
    fun valuesThatOnlyLookDifferentAreNotChanges() {
        val columns = listOf(title, done, pages, tags)
        val initial = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "t" to JsonPrimitive(""), "pg" to JsonPrimitive(12.0), "tg" to JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b"))),
        )
        val edited = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "dn" to JsonPrimitive(false), // 없음 = 꺼짐
            "pg" to JsonPrimitive(12.0),
            "tg" to JsonArray(listOf(JsonPrimitive("b"), JsonPrimitive("a"))), // 순서만 다르다
        )
        assertEquals(emptyMap<String, Any>(), changedValues(columns, initial, edited))
        // 새 옵션 이름이 끼면 바뀐 것이다.
        val withNew = edited + ("tg" to JsonArray(listOf(JsonPrimitive("a"), buildJsonObject { put("name", "새 태그") })))
        assertEquals(setOf("tg"), changedValues(columns, initial, withNew).keys)
    }

    @Test
    fun readOnlyAndUnknownColumnsAreNeverReported() {
        val columns = listOf(formula, relation)
        assertEquals(emptyMap<String, Any>(), changedValues(columns, emptyMap(), mapOf("fm" to JsonPrimitive("x"))))
    }

    @Test
    fun aNumberFieldReadsBlankAsClearAndRejectsLetters() {
        assertEquals(NumberInput.Value(12.5), parseNumber("12.5"))
        assertEquals(NumberInput.Value(12.5), parseNumber(" 12.5 "))
        assertEquals(NumberInput.Blank, parseNumber("  "))
        assertEquals(NumberInput.Invalid, parseNumber("12a"))
        // NaN·Infinity·넘치는 큰 수는 JSON으로 쓸 수 없다(저장하다 앱이 꺼진다). 숫자가 아닌 것으로 본다.
        listOf("NaN", "Infinity", "-Infinity", "1e999", "9".repeat(400)).forEach { assertEquals(it, NumberInput.Invalid, parseNumber(it)) }
        assertEquals("12", formatNumber(12.0))
        assertEquals("12.5", formatNumber(12.5))
    }
}
