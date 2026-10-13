package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** PRD §8.4 두 DB 구조에서 추천 매핑이 프리셋과 같게 나오는지. */
class MappingSuggesterTest {
    private fun schema(json: String) = NotionJson.decodeFromString(DataSourceDto.serializer(), json)

    private val calendar = schema(
        """{"id":"ds1","properties":{
        "Name":{"id":"title","name":"Name","type":"title"},
        "Date":{"id":"dAtE","name":"Date","type":"date"},
        "완료":{"id":"dOnE","name":"완료","type":"checkbox"},
        "Pri.":{"id":"pRi","name":"Pri.","type":"select","select":{"options":[]}},
        "Ctgr":{"id":"cTgR","name":"Ctgr","type":"select","select":{"options":[]}},
        "Time":{"id":"tImE","name":"Time","type":"select","select":{"options":[]}},
        "CreationDate":{"id":"cRe","name":"CreationDate","type":"created_time"}}}""",
    )

    private val tasks = schema(
        """{"id":"ds2","properties":{
        "이름":{"id":"title","name":"이름","type":"title"},
        "날짜":{"id":"nAlJ","name":"날짜","type":"date"},
        "상태":{"id":"sTaT","name":"상태","type":"status","status":{
          "options":[{"id":"o1","name":"시작 전"},{"id":"o2","name":"진행 중"},{"id":"o3","name":"Done"}],
          "groups":[{"name":"To-do","option_ids":["o1"]},{"name":"In progress","option_ids":["o2"]},{"name":"Complete","option_ids":["o3"]}]}},
        "단계":{"id":"dAnG","name":"단계","type":"select","select":{"options":[]}},
        "유형":{"id":"yUh","name":"유형","type":"select","select":{"options":[]}}}}""",
    )

    @Test
    fun calendarPreset() {
        assertEquals(MappingChoice("title", "dAtE", "dOnE", "cTgR", "tImE"), MappingSuggester.suggest(calendar))
    }

    @Test
    fun tasksPresetUsesStatusAndStageColor() {
        assertEquals(MappingChoice("title", "nAlJ", "sTaT", "dAnG", null), MappingSuggester.suggest(tasks))
        val status = tasks.properties.getValue("상태")
        assertEquals("Done" to "시작 전", MappingSuggester.statusOptions(status))
    }

    @Test
    fun databaseWithoutDateCannotBeUsed() {
        assertNull(MappingSuggester.suggest(schema("""{"id":"x","properties":{"N":{"id":"title","name":"N","type":"title"}}}""")))
    }
}
