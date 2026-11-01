package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** VW-02 타입별 표시 글자. */
class PropertyDisplayTest {
    private fun show(json: String) = displayValue(Json.parseToJsonElement(json).jsonObject)

    @Test
    fun textAndSelects() {
        assertEquals("안녕하세요", show("""{"type":"title","title":[{"plain_text":"안녕"},{"plain_text":"하세요"}]}"""))
        assertEquals("메모", show("""{"type":"rich_text","rich_text":[{"plain_text":"메모"}]}"""))
        assertEquals("진행 중", show("""{"type":"status","status":{"name":"진행 중"}}"""))
        assertEquals("", show("""{"type":"select","select":null}"""))
        assertEquals("a, b", show("""{"type":"multi_select","multi_select":[{"name":"a"},{"name":"b"}]}"""))
    }

    @Test
    fun numbersDatesAndFlags() {
        assertEquals("3", show("""{"type":"number","number":3.0}"""))
        assertEquals("2.5", show("""{"type":"number","number":2.5}"""))
        assertEquals("", show("""{"type":"number","number":null}"""))
        assertEquals("2026-10-21", show("""{"type":"date","date":{"start":"2026-10-21","end":null}}"""))
        assertEquals("2026-10-21~2026-10-23", show("""{"type":"date","date":{"start":"2026-10-21","end":"2026-10-23"}}"""))
        assertEquals("✓", show("""{"type":"checkbox","checkbox":true}"""))
        assertEquals("", show("""{"type":"checkbox","checkbox":false}"""))
        assertEquals("2026-10-21", show("""{"type":"last_edited_time","last_edited_time":"2026-10-21T03:04:00.000Z"}"""))
    }

    @Test
    fun linksPeopleAndCounts() {
        assertEquals("https://a.b", show("""{"type":"url","url":"https://a.b"}"""))
        assertEquals("김, 이", show("""{"type":"people","people":[{"name":"김"},{"name":"이"}]}"""))
        assertEquals("2개", show("""{"type":"relation","relation":[{"id":"a"},{"id":"b"}],"has_more":false}"""))
        assertEquals("", show("""{"type":"files","files":[]}"""))
        assertEquals("1개", show("""{"type":"files","files":[{"name":"x"}]}"""))
        assertEquals("TSK-7", show("""{"type":"unique_id","unique_id":{"prefix":"TSK","number":7}}"""))
    }

    @Test
    fun formulaAndRollupFollowTheirResultType() {
        assertEquals("D-3", show("""{"type":"formula","formula":{"type":"string","string":"D-3"}}"""))
        assertEquals("12", show("""{"type":"formula","formula":{"type":"number","number":12}}"""))
        assertEquals("4", show("""{"type":"rollup","rollup":{"type":"number","number":4,"function":"sum"}}"""))
        assertEquals("2개", show("""{"type":"rollup","rollup":{"type":"array","array":[{},{}]}}"""))
    }

    @Test
    fun unknownTypesAreBlank() {
        assertEquals("", show("""{"type":"button","button":{}}"""))
        assertEquals("", show("""{"type":"brand_new_type","brand_new_type":{"x":1}}"""))
        assertEquals("", show("""{}"""))
    }
}
