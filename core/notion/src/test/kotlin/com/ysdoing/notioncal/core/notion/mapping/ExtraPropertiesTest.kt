package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Test

/** EX-07: select·multi_select·status·number·url 속성의 스키마·값 읽기와 쓰기. */
class ExtraPropertiesTest {
    private val schema = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
          "Name":{"id":"title","name":"Name","type":"title"},
          "Date":{"id":"dAtE","name":"Date","type":"date"},
          "Pri.":{"id":"pRi","name":"Pri.","type":"select","select":{"options":[{"id":"o1","name":"1st","color":"red"}]}},
          "Tags":{"id":"tAg","name":"Tags","type":"multi_select","multi_select":{"options":[
             {"id":"t1","name":"집","color":"blue"},{"id":"t2","name":"회사","color":"green"}]}},
          "State":{"id":"sTa","name":"State","type":"status","status":{"options":[{"id":"s1","name":"Doing","color":"blue"}],"groups":[]}},
          "Cost":{"id":"cOs","name":"Cost","type":"number"},
          "URL":{"id":"uRl","name":"URL","type":"url"},
          "Dday":{"id":"dDy","name":"Dday","type":"formula"}}}""",
    )

    private val page = NotionJson.decodeFromString(
        PageDto.serializer(),
        """{"id":"p1","url":"https://www.notion.so/p1","last_edited_time":"2026-10-14T00:00:00.000Z","properties":{
          "Name":{"id":"title","type":"title","title":[{"plain_text":"회의"}]},
          "Date":{"id":"dAtE","type":"date","date":{"start":"2026-10-14"}},
          "Pri.":{"id":"pRi","type":"select","select":{"id":"o1","name":"1st","color":"red"}},
          "Tags":{"id":"tAg","type":"multi_select","multi_select":[{"id":"t1","name":"집"},{"id":"t2","name":"회사"}]},
          "State":{"id":"sTa","type":"status","status":null},
          "Cost":{"id":"cOs","type":"number","number":12.5},
          "URL":{"id":"uRl","type":"url","url":"https://example.com"},
          "Dday":{"id":"dDy","type":"formula","formula":{"type":"number","number":3}}}}""",
    )

    @Test
    fun schemaListsOnlySupportedTypesWithOptions() {
        val props = schema.extraProperties()
        assertEquals(listOf("Cost", "Pri.", "State", "Tags", "URL"), props.map { it.name })
        assertEquals(listOf("t1", "t2"), props.first { it.name == "Tags" }.options.map { it.id })
        assertEquals(ExtraTypes.STATUS, props.first { it.name == "State" }.type)
    }

    @Test
    fun mapperReadsExtraValues() {
        val mapping = PropertyMapping("title", "dAtE", null, null, null, null)
        val values = NotionPageMapper(mapping, schema).map(page).event!!.extraValues
        assertEquals(PropertyValue(optionIds = listOf("o1")), values["pRi"])
        assertEquals(PropertyValue(optionIds = listOf("t1", "t2")), values["tAg"])
        assertEquals(PropertyValue(optionIds = emptyList()), values["sTa"]) // 비어 있음도 값이다
        assertEquals(PropertyValue(number = 12.5), values["cOs"])
        assertEquals(PropertyValue(text = "https://example.com"), values["uRl"])
        assertEquals(null, values["dDy"]) // 수식은 대상 아님
    }

    @Test
    fun encoderWritesEachTypeWithSchemaNames() {
        val mapping = PropertyMapping("title", "dAtE", null, null, null, null)
        val json = PropertyEncoder(mapping, null, null, schema).encode(
            PageWrite(
                props = mapOf(
                    "pRi" to PropertyWrite(ExtraTypes.SELECT, PropertyValue(optionIds = listOf("o1"))),
                    "tAg" to PropertyWrite(ExtraTypes.MULTI_SELECT, PropertyValue(optionIds = listOf("t2"))),
                    "sTa" to PropertyWrite(ExtraTypes.STATUS, PropertyValue(optionIds = emptyList())),
                    "cOs" to PropertyWrite(ExtraTypes.NUMBER, PropertyValue(number = 3.0)),
                    "uRl" to PropertyWrite(ExtraTypes.URL, PropertyValue(text = " ")),
                ),
            ),
        )
        assertEquals(setOf("Pri.", "Tags", "State", "Cost", "URL"), json.keys)
        assertEquals("o1", json["Pri."]!!.jsonObject["select"]!!.jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals("t2", json["Tags"]!!.jsonObject["multi_select"]!!.jsonArray[0].jsonObject["id"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, json["State"]!!.jsonObject["status"]) // 비우기
        assertEquals("3.0", json["Cost"]!!.jsonObject["number"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, json["URL"]!!.jsonObject["url"]) // 공백만 있으면 비운다
    }
}
