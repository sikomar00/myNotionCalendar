package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** M3 위젯에서 바꾼 보기 행 속성이 노션 PATCH 본문으로 바뀌는 모양. 키는 속성 이름이다. */
class ViewValueEncoderTest {
    private fun json(text: String) = Json.parseToJsonElement(text)

    @Test
    fun checkbox() {
        assertEquals(json("""{"Done":{"checkbox":true}}"""), encodeViewValue("Done", "checkbox", JsonPrimitive(true)))
        assertEquals(json("""{"Done":{"checkbox":false}}"""), encodeViewValue("Done", "checkbox", JsonPrimitive(false)))
        assertEquals(json("""{"Done":{"checkbox":false}}"""), encodeViewValue("Done", "checkbox", JsonNull)) // 값이 없으면 해제
    }

    @Test
    fun statusAndSelectUseTheOptionId() {
        assertEquals(json("""{"Status":{"status":{"id":"o2"}}}"""), encodeViewValue("Status", "status", JsonPrimitive("o2")))
        assertEquals(json("""{"분류":{"select":{"id":"a1"}}}"""), encodeViewValue("분류", "select", JsonPrimitive("a1")))
    }

    @Test
    fun selectCanBeCleared() {
        assertEquals(json("""{"분류":{"select":null}}"""), encodeViewValue("분류", "select", JsonNull))
    }

    @Test
    fun number() {
        assertEquals(json("""{"Pages":{"number":12.5}}"""), encodeViewValue("Pages", "number", JsonPrimitive(12.5)))
        assertEquals(json("""{"Pages":{"number":null}}"""), encodeViewValue("Pages", "number", JsonNull))
    }

    @Test
    fun unknownTypesAreNotWritten() {
        assertNull(encodeViewValue("Note", "rich_text", JsonPrimitive("x")))
        assertNull(encodeViewValue("When", "date", JsonPrimitive("2026-11-03")))
    }

    @Test
    fun newItemHasATitleAndAnOptionalDate() {
        assertEquals(
            json("""{"Name":{"title":[{"text":{"content":"장보기"}}]},"When":{"date":{"start":"2026-11-04"}}}"""),
            encodeViewCreate("Name", "장보기", "When", "2026-11-04"),
        )
        assertEquals(json("""{"Name":{"title":[{"text":{"content":"메모"}}]}}"""), encodeViewCreate("Name", "메모", null, null))
        assertEquals(json("""{"Name":{"title":[{"text":{"content":"메모"}}]}}"""), encodeViewCreate("Name", "메모", "When", null)) // 날짜 값이 없으면 넣지 않는다
    }

    @Test
    fun newItemTitleIsCappedAtNotionsLimit() {
        val content = encodeViewCreate("Name", "가".repeat(5000), null, null).toString()
        assertEquals(2000, content.count { it == '가' })
    }
}
