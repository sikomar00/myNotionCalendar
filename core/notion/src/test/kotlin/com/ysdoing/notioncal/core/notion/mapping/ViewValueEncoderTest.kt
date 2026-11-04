package com.ysdoing.notioncal.core.notion.mapping

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
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
        // 읽기 전용(수식·롤업·자동 값)과 아직 보기 행에서 쓰지 않는 종류(관계형·사람·파일)는 보내지 않는다.
        for (type in listOf("formula", "rollup", "created_time", "relation", "people", "files", "unique_id", "button")) {
            assertNull(type, encodeViewValue("Prop", type, JsonPrimitive("x")))
        }
    }

    @Test
    fun newItemHasATitleAndAnOptionalDate() {
        assertEquals(
            json("""{"Name":{"title":[{"text":{"content":"장보기"}}]},"When":{"date":{"start":"2026-11-04"}}}"""),
            encodeViewCreate("Name", "장보기", "When", "2026-11-04"),
        )
        assertEquals(json("""{"Name":{"title":[{"text":{"content":"메모"}}]}}"""), encodeViewCreate("Name", "메모", null, null))
        assertEquals(json("""{"Name":{"title":[{"text":{"content":"메모"}}]}}"""), encodeViewCreate("Name", "메모", "When", null)) // 날짜 값이 없으면 넣지 않는다
        // Phase 11c: 값 묶음(프리셋·맥락 채움)은 제목 뒤에 그대로 합친다.
        assertEquals(
            json("""{"Name":{"title":[{"text":{"content":"점심"}}]},"Status":{"status":{"id":"o2"}}}"""),
            encodeViewCreate("Name", "점심", null, null, json("""{"Status":{"status":{"id":"o2"}}}""").jsonObject),
        )
    }

    @Test
    fun newItemTitleIsCappedAtNotionsLimit() {
        val content = encodeViewCreate("Name", "가".repeat(5000), null, null).toString()
        assertEquals(2000, content.count { it == '가' })
    }

    @Test
    fun textAndStringTypesUseTheCodecShape() {
        assertEquals(json("""{"이름":{"title":[{"text":{"content":"장보기"}}]}}"""), encodeViewValue("이름", "title", JsonPrimitive("장보기")))
        assertEquals(json("""{"메모":{"rich_text":[{"text":{"content":"내용"}}]}}"""), encodeViewValue("메모", "rich_text", JsonPrimitive("내용")))
        assertEquals(json("""{"링크":{"url":"https://a.b"}}"""), encodeViewValue("링크", "url", JsonPrimitive("https://a.b")))
        assertEquals(json("""{"링크":{"url":null}}"""), encodeViewValue("링크", "url", JsonNull)) // 비우기
        assertEquals(json("""{"메일":{"email":"a@b.c"}}"""), encodeViewValue("메일", "email", JsonPrimitive("a@b.c")))
        assertEquals(json("""{"전화":{"phone_number":"010"}}"""), encodeViewValue("전화", "phone_number", JsonPrimitive("010")))
    }

    @Test
    fun multiSelectUsesIdsAndNamesForNewOptions() {
        val value = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("t1"), kotlinx.serialization.json.buildJsonObject { put("name", JsonPrimitive("새 태그")) }))
        assertEquals(json("""{"태그":{"multi_select":[{"id":"t1"},{"name":"새 태그"}]}}"""), encodeViewValue("태그", "multi_select", value))
        assertEquals(json("""{"태그":{"multi_select":[]}}"""), encodeViewValue("태그", "multi_select", JsonNull)) // 모두 해제
    }

    @Test
    fun dateKeepsStartEndAndTimeZoneOrClears() {
        val value = kotlinx.serialization.json.buildJsonObject {
            put("start", JsonPrimitive("2026-11-04"))
            put("end", JsonPrimitive("2026-11-05"))
        }
        assertEquals(json("""{"날짜":{"date":{"start":"2026-11-04","end":"2026-11-05"}}}"""), encodeViewValue("날짜", "date", value))
        assertEquals(json("""{"날짜":{"date":null}}"""), encodeViewValue("날짜", "date", JsonNull))
    }
}
