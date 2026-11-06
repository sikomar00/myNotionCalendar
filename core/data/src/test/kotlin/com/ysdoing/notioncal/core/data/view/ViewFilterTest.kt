package com.ysdoing.notioncal.core.data.view

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** Phase 11c IX-06·AD-01: 노션 보기 필터를 폰에서 판정한다. 단순 유형만 가리고, 못 가리면 UNKNOWN('반영 대기')이다. */
class ViewFilterTest {
    private val columns = listOf(
        ViewColumn("t", "이름", "title"),
        ViewColumn("tx", "메모", "rich_text"),
        ViewColumn("dn", "완료", "checkbox"),
        ViewColumn("st", "상태", "status", listOf(ViewChoice("o1", "할 일"), ViewChoice("o2", "완료"))),
        ViewColumn("sl", "분류", "select", listOf(ViewChoice("a", "집"), ViewChoice("b", "회사"))),
        ViewColumn("ms", "태그", "multi_select", listOf(ViewChoice("x", "급함"), ViewChoice("y", "나중"))),
        ViewColumn("pg", "쪽수", "number"),
        ViewColumn("dt", "날짜", "date"),
        ViewColumn("fm", "수식", "formula"),
    )

    private fun judge(filter: String, vararg typed: Pair<String, JsonElement>) = ViewFilter.parse(filter).matches(columns, typed.toMap())

    private fun leaf(property: String, type: String, op: String, value: String) = """{"property":"$property","$type":{"$op":$value}}"""

    private fun text(value: String) = JsonPrimitive(value)

    private fun date(start: String) = JsonObject(mapOf("start" to JsonPrimitive(start)))

    @Test
    fun checkboxEqualsTreatsAMissingValueAsOff() {
        assertEquals(FilterMatch.YES, judge(leaf("dn", "checkbox", "equals", "true"), "dn" to JsonPrimitive(true)))
        assertEquals(FilterMatch.NO, judge(leaf("dn", "checkbox", "equals", "true"), "dn" to JsonPrimitive(false)))
        assertEquals(FilterMatch.NO, judge(leaf("dn", "checkbox", "equals", "true"))) // 값 없음 = 꺼짐
        assertEquals(FilterMatch.YES, judge(leaf("dn", "checkbox", "equals", "false")))
        assertEquals(FilterMatch.YES, judge(leaf("dn", "checkbox", "does_not_equal", "true"), "dn" to JsonPrimitive(false)))
    }

    @Test
    fun selectAndStatusMatchTheOptionByIdOrByName() {
        assertEquals(FilterMatch.YES, judge(leaf("sl", "select", "equals", "\"집\""), "sl" to text("a")))
        assertEquals(FilterMatch.YES, judge(leaf("sl", "select", "equals", "\"a\""), "sl" to text("a")))
        assertEquals(FilterMatch.NO, judge(leaf("sl", "select", "equals", "\"회사\""), "sl" to text("a")))
        assertEquals(FilterMatch.YES, judge(leaf("sl", "select", "does_not_equal", "\"회사\""), "sl" to text("a")))
        assertEquals(FilterMatch.YES, judge(leaf("sl", "select", "is_empty", "true")))
        assertEquals(FilterMatch.YES, judge(leaf("sl", "select", "is_not_empty", "true"), "sl" to text("a")))
        assertEquals(FilterMatch.NO, judge(leaf("sl", "select", "is_not_empty", "true")))
        assertEquals(FilterMatch.YES, judge(leaf("st", "status", "equals", "\"완료\""), "st" to text("o2")))
        assertEquals(FilterMatch.NO, judge(leaf("st", "status", "equals", "\"완료\""), "st" to text("o1")))
    }

    @Test
    fun multiSelectContainsAndIsEmpty() {
        val both = "ms" to JsonArray(listOf(text("x")))
        assertEquals(FilterMatch.YES, judge(leaf("ms", "multi_select", "contains", "\"급함\""), both))
        assertEquals(FilterMatch.NO, judge(leaf("ms", "multi_select", "contains", "\"나중\""), both))
        assertEquals(FilterMatch.YES, judge(leaf("ms", "multi_select", "does_not_contain", "\"나중\""), both))
        assertEquals(FilterMatch.YES, judge(leaf("ms", "multi_select", "is_empty", "true"), "ms" to JsonArray(emptyList())))
    }

    @Test
    fun numberComparisonsAndEmpty() {
        val twelve = "pg" to JsonPrimitive(12.0)
        assertEquals(FilterMatch.YES, judge(leaf("pg", "number", "equals", "12"), twelve))
        assertEquals(FilterMatch.YES, judge(leaf("pg", "number", "greater_than", "10"), twelve))
        assertEquals(FilterMatch.NO, judge(leaf("pg", "number", "less_than", "10"), twelve))
        assertEquals(FilterMatch.YES, judge(leaf("pg", "number", "greater_than_or_equal_to", "12"), twelve))
        assertEquals(FilterMatch.NO, judge(leaf("pg", "number", "less_than_or_equal_to", "11"), twelve))
        assertEquals(FilterMatch.YES, judge(leaf("pg", "number", "is_empty", "true")))
        assertEquals(FilterMatch.NO, judge(leaf("pg", "number", "equals", "12"))) // 값이 없으면 비교는 아니다
    }

    @Test
    fun textOperatorsIgnoreCase() {
        val title = "t" to text("장보기 ABC")
        assertEquals(FilterMatch.YES, judge(leaf("t", "title", "contains", "\"abc\""), title))
        assertEquals(FilterMatch.YES, judge(leaf("t", "title", "starts_with", "\"장보기\""), title))
        assertEquals(FilterMatch.YES, judge(leaf("t", "title", "ends_with", "\"abc\""), title))
        assertEquals(FilterMatch.NO, judge(leaf("t", "title", "equals", "\"장보기\""), title))
        assertEquals(FilterMatch.YES, judge(leaf("t", "title", "does_not_contain", "\"우유\""), title))
        assertEquals(FilterMatch.YES, judge(leaf("tx", "rich_text", "is_empty", "true"), "tx" to text("")))
        assertEquals(FilterMatch.YES, judge(leaf("tx", "rich_text", "is_not_empty", "true"), "tx" to text("메모")))
    }

    @Test
    fun absoluteDateComparisonsUseTheStartDateAndRelativeOnesAreUnknown() {
        val day = "dt" to date("2026-11-04T09:00:00.000+09:00")
        assertEquals(FilterMatch.YES, judge(leaf("dt", "date", "equals", "\"2026-11-04\""), day))
        assertEquals(FilterMatch.YES, judge(leaf("dt", "date", "before", "\"2026-11-05\""), day))
        assertEquals(FilterMatch.NO, judge(leaf("dt", "date", "after", "\"2026-11-04\""), day))
        assertEquals(FilterMatch.YES, judge(leaf("dt", "date", "on_or_before", "\"2026-11-04\""), day))
        assertEquals(FilterMatch.NO, judge(leaf("dt", "date", "on_or_after", "\"2026-11-05\""), day))
        assertEquals(FilterMatch.YES, judge(leaf("dt", "date", "is_empty", "true")))
        assertEquals(FilterMatch.UNKNOWN, judge(leaf("dt", "date", "past_week", "{}"), day)) // 상대 날짜는 판정하지 않는다
        assertEquals(FilterMatch.UNKNOWN, judge(leaf("dt", "date", "this_week", "{}"), day))
    }

    @Test
    fun andAndOrCombineYesNoAndUnknownTheWayLogicSays() {
        val yes = leaf("dn", "checkbox", "equals", "false")
        val no = leaf("dn", "checkbox", "equals", "true")
        val unknown = leaf("fm", "formula", "equals", "1")
        fun and(vararg parts: String) = """{"and":[${parts.joinToString(",")}]}"""
        fun or(vararg parts: String) = """{"or":[${parts.joinToString(",")}]}"""
        assertEquals(FilterMatch.NO, judge(and(yes, no)))
        assertEquals(FilterMatch.UNKNOWN, judge(and(yes, unknown)))
        assertEquals(FilterMatch.NO, judge(and(no, unknown))) // 하나가 아니면 나머지가 뭐든 아니다
        assertEquals(FilterMatch.YES, judge(or(no, yes)))
        assertEquals(FilterMatch.YES, judge(or(yes, unknown))) // 하나가 맞으면 나머지가 뭐든 맞다
        assertEquals(FilterMatch.UNKNOWN, judge(or(no, unknown)))
        assertEquals(FilterMatch.NO, judge(or(no, no)))
        assertEquals(FilterMatch.YES, judge(and(yes, or(no, yes))))
    }

    @Test
    fun whatItCannotJudgeIsUnknownAndNoFilterMatchesEverything() {
        assertEquals(FilterMatch.UNKNOWN, judge(leaf("zz", "checkbox", "equals", "true"))) // 열을 모른다(보기에 없는 속성)
        assertEquals(FilterMatch.UNKNOWN, judge(leaf("fm", "formula", "equals", "1"))) // 수식·롤업·관계
        assertEquals(FilterMatch.UNKNOWN, judge("""{"timestamp":"created_time","created_time":{"past_week":{}}}"""))
        assertEquals(FilterMatch.UNKNOWN, judge("""{"property":"dn","checkbox":{"weird_op":true}}"""))
        listOf("", "  ", "null", "{}", "not json", "[1]").forEach { assertEquals(it, FilterMatch.YES, judge(it)) } // 필터 없음 = 모두 맞다
    }

    @Test
    fun equalsConditionsAreTheSimpleTopLevelOnesNewRowsCanBePrefilledWith() {
        val filter = ViewFilter.parse(
            """{"and":[${leaf("sl", "select", "equals", "\"집\"")},${leaf("dn", "checkbox", "equals", "false")},${leaf("pg", "number", "equals", "3")},
                ${leaf("t", "title", "contains", "\"x\"")},{"or":[${leaf("st", "status", "equals", "\"완료\"")}]}]}""",
        )
        // 선택·체크·숫자의 같음 조건만. 선택은 옵션 ID로 바꿔 쓰기 정보(typed)와 같은 모양이다. 포함·or 안의 조건은 채우지 않는다.
        assertEquals(mapOf<String, JsonElement>("sl" to text("a"), "dn" to JsonPrimitive(false), "pg" to JsonPrimitive(3.0)), filter.equalsConditions(columns))
        assertEquals(mapOf<String, JsonElement>("st" to text("o2")), ViewFilter.parse(leaf("st", "status", "equals", "\"완료\"")).equalsConditions(columns))
        assertEquals(emptyMap<String, JsonElement>(), ViewFilter.parse(leaf("sl", "select", "equals", "\"없는 옵션\"")).equalsConditions(columns))
    }

    @Test
    fun aBoardCallsAPendingRowThatNoLongerMatchesLeaving() {
        val board = ViewBoard(
            "v1", "할 일", "table", listOf("이름", "완료"),
            listOf(ViewRow("p1", "u", listOf("a", "✓"), typed = mapOf("dn" to JsonPrimitive(true))), ViewRow("p2", "u", listOf("b", "✓"), typed = mapOf("dn" to JsonPrimitive(true)))),
            1, null, columns, filterJson = leaf("dn", "checkbox", "equals", "false"), pendingPageIds = setOf("p1"),
        )
        // 빠지는 중: 바꾼 행(쓰기 대기)이 필터에서 벗어났다. 바꾸지 않은 행은 판정이 어긋나도 건드리지 않는다.
        assertEquals(listOf(true, false), board.rows.map { board.isLeaving(it) })
        assertEquals(false, board.copy(filterJson = leaf("fm", "formula", "equals", "1")).isLeaving(board.rows[0])) // 판정 불가는 흐리지 않는다
        assertEquals(false, board.copy(filterJson = "").isLeaving(board.rows[0]))
    }
}
