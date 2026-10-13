package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** PRD §8.3 쓰기: 속성 ID 키, checkbox·status, 날짜만·시간 포함(+09:00), select, 제목 2,000자. */
class PropertyEncoderTest {
    private val calendar = PropertyMapping("title", "dAtE", "d%3Fne", DoneType.CHECKBOX, "cTgR", "tImE")
    private val tasks = PropertyMapping("title", "nAlJ", "sTaT", DoneType.STATUS, "dAnG", null)

    @Test
    fun onlyChangedFieldsAreSentWithIdKeys() {
        val json = PropertyEncoder(calendar, null, null).encode(PageWrite(done = true))
        assertEquals(setOf("d%3Fne"), json.keys)
        assertEquals("true", json["d%3Fne"]!!.jsonObject["checkbox"]!!.jsonPrimitive.content)
    }

    @Test
    fun titleIsTruncatedTo2000Chars() {
        val json = PropertyEncoder(calendar, null, null).encode(PageWrite(title = "가".repeat(2500)))
        val content = json["title"]!!.jsonObject["title"]!!.jsonArray[0].jsonObject["text"]!!.jsonObject["content"]!!.jsonPrimitive.content
        assertEquals(2000, content.length)
    }

    @Test
    fun allDayDateHasNoTimeOrOffset() {
        val json = PropertyEncoder(calendar, null, null).encode(PageWrite(date = WriteDate(LocalDate.of(2026, 10, 31))))
        val date = json["dAtE"]!!.jsonObject["date"]!!.jsonObject
        assertEquals("2026-10-31", date["start"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, date["end"])
    }

    @Test
    fun rangeAndTimedDatesUseSeoulOffset() {
        val range = PropertyEncoder(calendar, null, null)
            .encode(PageWrite(date = WriteDate(LocalDate.of(2026, 10, 30), LocalDate.of(2026, 11, 2))))
        assertEquals("2026-11-02", range["dAtE"]!!.jsonObject["date"]!!.jsonObject["end"]!!.jsonPrimitive.content)

        val timed = PropertyEncoder(calendar, null, null).encode(
            PageWrite(
                date = WriteDate(
                    LocalDate.of(2026, 10, 13),
                    start = Instant.parse("2026-10-13T06:00:00Z"),
                    end = Instant.parse("2026-10-13T07:30:00Z"),
                ),
            ),
        )
        val date = timed["dAtE"]!!.jsonObject["date"]!!.jsonObject
        assertEquals("2026-10-13T15:00:00+09:00", date["start"]!!.jsonPrimitive.content)
        assertEquals("2026-10-13T16:30:00+09:00", date["end"]!!.jsonPrimitive.content)
    }

    @Test
    fun statusUsesCompleteAndTodoOptionNames() {
        val encoder = PropertyEncoder(tasks, doneOption = "Done", undoneOption = "시작 전")
        assertEquals("Done", encoder.encode(PageWrite(done = true))["sTaT"]!!.jsonObject["status"]!!.jsonObject["name"]!!.jsonPrimitive.content)
        assertEquals("시작 전", encoder.encode(PageWrite(done = false))["sTaT"]!!.jsonObject["status"]!!.jsonObject["name"]!!.jsonPrimitive.content)
    }

    @Test
    fun selectSetAndClear() {
        val encoder = PropertyEncoder(calendar, null, null)
        val set = encoder.encode(PageWrite(setSelect = true, selectOptionId = "opt-habit"))
        assertEquals("opt-habit", set["cTgR"]!!.jsonObject["select"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        val cleared = encoder.encode(PageWrite(setSelect = true, selectOptionId = null))
        assertEquals(JsonNull, cleared["cTgR"]!!.jsonObject["select"])
        assertFalse(encoder.encode(PageWrite(title = "x")).containsKey("cTgR"))
    }

    @Test
    fun keysAreSchemaNamesNotEncodedIds() {
        // 실기기에서 `e%3D~%40` 같은 인코딩된 ID 키는 노션이 무시했다. 보낼 때는 스키마의 이름을 쓴다.
        val schema = NotionJson.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{
              "Name":{"id":"title","name":"Name","type":"title"},
              "Date":{"id":"e%3D~%40","name":"Date","type":"date"},
              "완료":{"id":"XNuP","name":"완료","type":"checkbox"}}}""",
        )
        val mapping = PropertyMapping("title", "e%3D~%40", "XNuP", DoneType.CHECKBOX, null, null)
        val json = PropertyEncoder(mapping, null, null, schema)
            .encode(PageWrite(title = "x", date = WriteDate(LocalDate.of(2026, 10, 20)), done = true))
        assertEquals(setOf("Name", "Date", "완료"), json.keys)
        assertEquals("2026-10-20", json["Date"]!!.jsonObject["date"]!!.jsonObject["start"]!!.jsonPrimitive.content)
    }

    @Test
    fun noDonePropertyMeansDoneIsIgnored() {
        val json = PropertyEncoder(calendar.copy(donePropId = null, doneType = null), null, null).encode(PageWrite(done = true, title = "x"))
        assertTrue(json.keys == setOf("title"))
    }
}
