package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.fixture
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 10-3 속성 코덱: 노션 속성 유형 24종(요청 22종 + 제목 + 확인)과 모르는 유형의 읽기·쓰기. 픽스처는 직접 만든 값이고 실제 노션 데이터가 아니다.
 * 쓰기 모양(`encode`)은 `PATCH /pages`의 속성 하나(`{"유형": 값}`)이고, 읽기 전용 유형은 null이다(PRD §8.3).
 */
class PropertyCodecTest {
    private val all: JsonObject = Json.parseToJsonElement(fixture("properties_all_types.json")).jsonObject

    private fun prop(name: String): JsonObject = all.getValue(name).jsonObject

    private fun decode(name: String): NotionValue = PropertyCodec.decode(prop(name))

    private fun json(text: String): JsonElement = Json.parseToJsonElement(text)

    private fun opt(id: String?, name: String?, color: String?) = NotionValue.Option(id, name, color)

    @Test
    fun decodesTextNumberAndChoiceTypes() {
        assertEquals(NotionValue.Text("title", "회의록"), decode("title"))
        assertEquals(NotionValue.Text("rich_text", "메모 내용"), decode("rich_text")) // 서식은 글자만 남긴다
        assertEquals(NotionValue.Num(3.5), decode("number"))
        assertEquals(NotionValue.Choice("select", opt("opt-1", "진행", "blue")), decode("select"))
        assertEquals(NotionValue.Choice("status", opt("opt-s", "진행 중", "yellow")), decode("status"))
        assertEquals(NotionValue.Choices(listOf(opt("t1", "집", "blue"), opt("t2", "회사", "green"))), decode("multi_select"))
    }

    @Test
    fun decodesDatePeopleFilesAndCheckbox() {
        assertEquals(NotionValue.DateRange("2026-11-03T09:00:00.000+09:00", "2026-11-03T10:00:00.000+09:00", null), decode("date"))
        assertEquals(NotionValue.Users("people", listOf(NotionValue.User("user-1", "홍길동"), NotionValue.User("user-2", null))), decode("people"))
        assertEquals(NotionValue.Files(listOf(NotionValue.FileRef("표지.jpg", "https://img.example/cover.jpg", external = true))), decode("files"))
        assertEquals(NotionValue.Files(listOf(NotionValue.FileRef("업로드.png", "https://files.example/x.png?X-Amz-Signature=abc", external = false))), decode("files_hosted"))
        assertEquals(NotionValue.Check(true), decode("checkbox"))
    }

    @Test
    fun decodesStringTypesRelationFormulaAndRollup() {
        assertEquals(NotionValue.Str("url", "https://example.com/a"), decode("url"))
        assertEquals(NotionValue.Str("email", "user@example.com"), decode("email"))
        assertEquals(NotionValue.Str("phone_number", "010-0000-0000"), decode("phone_number"))
        assertEquals(NotionValue.Formula(NotionValue.Num(12.0)), decode("formula"))
        assertEquals(NotionValue.Relation(listOf("page-1", "page-2"), hasMore = false), decode("relation"))
        assertEquals(NotionValue.Rollup("sum", NotionValue.Num(7.0), null), decode("rollup"))
        assertEquals(
            NotionValue.Rollup("show_original", null, listOf(NotionValue.Num(1.0), NotionValue.Text("title", "하나"))),
            decode("rollup_array"),
        )
    }

    @Test
    fun decodesAutomaticTypes() {
        assertEquals(NotionValue.Str("created_time", "2026-11-01T00:00:00.000Z"), decode("created_time"))
        assertEquals(NotionValue.Users("created_by", listOf(NotionValue.User("user-1", "홍길동"))), decode("created_by"))
        assertEquals(NotionValue.Str("last_edited_time", "2026-11-02T01:02:00.000Z"), decode("last_edited_time"))
        assertEquals(NotionValue.Users("last_edited_by", listOf(NotionValue.User("user-2", null))), decode("last_edited_by"))
        assertEquals(NotionValue.UniqueId("TASK", 12), decode("unique_id"))
    }

    @Test
    fun emptyValuesDecodeToEmptyTypedValues() {
        assertEquals(NotionValue.Choice("select", null), decode("empty_select"))
        assertEquals(NotionValue.Num(null), decode("empty_number"))
        assertEquals(NotionValue.DateRange(null, null, null), decode("empty_date"))
        assertEquals(NotionValue.Str("url", null), decode("empty_url"))
        assertEquals(NotionValue.Text("title", ""), decode("empty_title"))
    }

    @Test
    fun verificationButtonPlaceAndUnknownTypesKeepTheOriginalJson() {
        for (name in listOf("verification", "button", "place", "future_type")) {
            val value = decode(name)
            assertTrue("$name: ${value::class.simpleName}", value is NotionValue.Raw)
            assertEquals(prop(name), (value as NotionValue.Raw).json) // 원본을 그대로 쥔다
        }
        assertEquals("ai_summary", (decode("future_type") as NotionValue.Raw).type)
    }

    @Test
    fun writableTypesEncodeToTheNotionPatchShape() {
        assertEquals(json("""{"title":[{"text":{"content":"회의록"}}]}"""), PropertyCodec.encode(decode("title")))
        assertEquals(json("""{"rich_text":[{"text":{"content":"메모 내용"}}]}"""), PropertyCodec.encode(decode("rich_text")))
        assertEquals(json("""{"number":3.5}"""), PropertyCodec.encode(decode("number")))
        assertEquals(json("""{"select":{"id":"opt-1"}}"""), PropertyCodec.encode(decode("select")))
        assertEquals(json("""{"status":{"id":"opt-s"}}"""), PropertyCodec.encode(decode("status")))
        assertEquals(json("""{"multi_select":[{"id":"t1"},{"id":"t2"}]}"""), PropertyCodec.encode(decode("multi_select")))
        assertEquals(
            json("""{"date":{"start":"2026-11-03T09:00:00.000+09:00","end":"2026-11-03T10:00:00.000+09:00"}}"""),
            PropertyCodec.encode(decode("date")),
        )
        assertEquals(json("""{"people":[{"id":"user-1"},{"id":"user-2"}]}"""), PropertyCodec.encode(decode("people")))
        assertEquals(json("""{"files":[{"name":"표지.jpg","external":{"url":"https://img.example/cover.jpg"}}]}"""), PropertyCodec.encode(decode("files")))
        assertEquals(json("""{"checkbox":true}"""), PropertyCodec.encode(decode("checkbox")))
        assertEquals(json("""{"url":"https://example.com/a"}"""), PropertyCodec.encode(decode("url")))
        assertEquals(json("""{"email":"user@example.com"}"""), PropertyCodec.encode(decode("email")))
        assertEquals(json("""{"phone_number":"010-0000-0000"}"""), PropertyCodec.encode(decode("phone_number")))
        assertEquals(json("""{"relation":[{"id":"page-1"},{"id":"page-2"}]}"""), PropertyCodec.encode(decode("relation")))
    }

    @Test
    fun emptyValuesEncodeToClearTheProperty() {
        assertEquals(json("""{"select":null}"""), PropertyCodec.encode(decode("empty_select")))
        assertEquals(json("""{"number":null}"""), PropertyCodec.encode(decode("empty_number")))
        assertEquals(json("""{"date":null}"""), PropertyCodec.encode(decode("empty_date")))
        assertEquals(json("""{"url":null}"""), PropertyCodec.encode(decode("empty_url")))
        assertEquals(json("""{"title":[]}"""), PropertyCodec.encode(decode("empty_title")))
    }

    @Test
    fun newOptionsAreWrittenByName() {
        assertEquals(json("""{"select":{"name":"새 옵션"}}"""), PropertyCodec.encode(NotionValue.Choice("select", opt(null, "새 옵션", null))))
        assertEquals(json("""{"multi_select":[{"id":"t1"},{"name":"새 태그"}]}"""), PropertyCodec.encode(NotionValue.Choices(listOf(opt("t1", null, null), opt(null, "새 태그", null)))))
        assertEquals(json("""{"status":{"name":"진행 중"}}"""), PropertyCodec.encode(NotionValue.Choice("status", opt(null, "진행 중", null))))
    }

    @Test
    fun losslessTypesRoundTripThroughEncodeAndDecode() {
        for (name in listOf("title", "rich_text", "number", "date", "checkbox", "url", "email", "phone_number", "relation", "files", "empty_number", "empty_date", "empty_url")) {
            val value = decode(name)
            val encoded = PropertyCodec.encode(value)
            assertNotNull("$name 쓰기", encoded)
            val again = PropertyCodec.decode(JsonObject(encoded!! + ("type" to JsonPrimitive(value.type))))
            assertEquals(name, value, again)
        }
    }

    @Test
    fun readOnlyAndUnwritableTypesEncodeToNull() {
        for (name in listOf("formula", "rollup", "rollup_array", "created_time", "created_by", "last_edited_time", "last_edited_by", "unique_id", "verification", "button", "place", "future_type", "files_hosted")) {
            assertNull(name, PropertyCodec.encode(decode(name)))
        }
    }

    @Test
    fun longTextIsCutAtTheNotionLimit() {
        val encoded = PropertyCodec.encode(NotionValue.Text("rich_text", "가".repeat(2500)))!!
        val content = encoded["rich_text"]!!.let { (it as kotlinx.serialization.json.JsonArray)[0].jsonObject["text"]!!.jsonObject["content"] as JsonPrimitive }.content
        assertEquals(2000, content.length)
    }

    @Test
    fun malformedPropertiesNeverThrow() {
        assertEquals(NotionValue.Num(null), PropertyCodec.decode(json("""{"type":"number","number":"abc"}""").jsonObject))
        assertEquals(NotionValue.Choice("select", null), PropertyCodec.decode(json("""{"type":"select","select":"x"}""").jsonObject))
        assertEquals(NotionValue.Check(false), PropertyCodec.decode(json("""{"type":"checkbox"}""").jsonObject))
        assertEquals(NotionValue.Choices(emptyList()), PropertyCodec.decode(json("""{"type":"multi_select","multi_select":5}""").jsonObject))
        val noType = json("""{"id":"x"}""").jsonObject
        assertEquals(NotionValue.Raw("", noType), PropertyCodec.decode(noType))
    }
}
