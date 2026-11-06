package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import org.junit.Assert.assertEquals
import org.junit.Test

/** CN-02 스키마 전체 저장: 숫자 형식·수식·롤업·ID 설정을 읽고, 다시 JSON으로 써도 그대로다(source_schemas 캐시 왕복). */
class SchemaDtoTest {
    private val json = """
        {"id":"ds","properties":{
          "Cost":{"id":"c1","name":"Cost","type":"number","number":{"format":"won"}},
          "Calc":{"id":"f1","name":"Calc","type":"formula","formula":{"expression":"prop(\"Cost\") * 2"}},
          "Sum":{"id":"r1","name":"Sum","type":"rollup","rollup":{"relation_property_name":"Rel","relation_property_id":"rl","rollup_property_name":"Cost","rollup_property_id":"c1","function":"sum"}},
          "ID":{"id":"u1","name":"ID","type":"unique_id","unique_id":{"prefix":"TASK"}},
          "Status":{"id":"s1","name":"Status","type":"status","status":{"options":[{"id":"o1","name":"할 일","color":"gray"}],"groups":[{"id":"g1","name":"To-do","color":"gray","option_ids":["o1"]}]}}}}
    """.trimIndent()

    @Test
    fun readsTheExtraSchemaFieldsAndSurvivesARoundTrip() {
        val schema = NotionJson.decodeFromString(DataSourceDto.serializer(), json)
        val props = schema.properties
        assertEquals("won", props.getValue("Cost").number?.format)
        assertEquals("prop(\"Cost\") * 2", props.getValue("Calc").formula?.expression)
        assertEquals(listOf("rl", "c1", "sum"), props.getValue("Sum").rollup?.let { listOf(it.relationPropertyId, it.rollupPropertyId, it.function) })
        assertEquals("TASK", props.getValue("ID").uniqueId?.prefix)
        assertEquals("g1", props.getValue("Status").status?.groups?.single()?.id)
        val again = NotionJson.decodeFromString(DataSourceDto.serializer(), NotionJson.encodeToString(DataSourceDto.serializer(), schema))
        assertEquals(schema, again)
    }
}
