package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.QueryResponseDto
import com.ysdoing.notioncal.core.notion.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.LocalDate

/** PRD §8.3·§8.4 매핑: 두 DB의 완료 방식(checkbox·status)을 모두 해석한다. */
class NotionPageMapperTest {
    private fun schema(name: String) = NotionJson.decodeFromString(DataSourceDto.serializer(), fixture(name))
    private fun pages(name: String) = NotionJson.decodeFromString(QueryResponseDto.serializer(), fixture(name)).results

    private val calendarMapping = PropertyMapping(
        titlePropId = "title", datePropId = "dAtE", donePropId = "d%3Fne", doneType = DoneType.CHECKBOX,
        colorPropId = "cTgR", sortPropId = "tImE",
    )
    private val tasksMapping = PropertyMapping(
        titlePropId = "title", datePropId = "nAlJ", donePropId = "sTaT", doneType = DoneType.STATUS,
        colorPropId = "dAnG", sortPropId = null,
    )

    @Test
    fun calendarCheckboxDbMapsTitleColorSortAndDone() {
        val mapper = NotionPageMapper(calendarMapping, schema("data_source_calendar.json"))
        val (study, project) = pages("query_calendar_p1.json").map { mapper.map(it).event!! }

        assertEquals("영어 스터디", study.title)
        assertEquals(LocalDate.of(2026, 10, 13), study.startDate)
        assertTrue(study.isAllDay)
        assertFalse(study.done)
        assertEquals("blue", study.colorKey)
        assertEquals(1, study.sortKey) // 오전 = 옵션 순서 1
        assertEquals(Instant.parse("2026-10-12T05:13:00Z"), study.lastEditedTime)

        assertEquals("바코프로젝트 기획", project.title) // rich text 이어붙이기
        assertTrue(project.done)
        assertEquals("yellow", project.colorKey)
        assertNull(project.sortKey)
    }

    @Test
    fun timedEventAndPageWithoutDate() {
        val mapper = NotionPageMapper(calendarMapping, schema("data_source_calendar.json"))
        val (meeting, noDate) = pages("query_calendar_p2.json").map(mapper::map)

        val event = meeting.event!!
        assertFalse(event.isAllDay)
        assertEquals(LocalDate.of(2026, 10, 13), event.startDate)
        assertEquals(Instant.parse("2026-10-12T23:30:00Z"), event.startTime)
        assertEquals(Instant.parse("2026-10-13T00:30:00Z"), event.endTime)
        assertNull(event.colorKey)

        assertEquals("cal-page-4", noDate.pageId)
        assertNull(noDate.event)
    }

    @Test
    fun statusDbUsesCompleteGroup() {
        val mapper = NotionPageMapper(tasksMapping, schema("data_source_tasks.json"))
        val (done, doing) = pages("query_tasks.json").map { mapper.map(it).event!! }

        assertTrue(done.done)
        assertEquals(LocalDate.of(2026, 10, 30), done.startDate)
        assertEquals(LocalDate.of(2026, 11, 2), done.endDate)
        assertEquals("purple", done.colorKey)
        assertFalse(doing.done)
        assertNull(doing.colorKey)
    }

    @Test
    fun missingMappedPropertiesDoNotCrash() {
        val broken = calendarMapping.copy(titlePropId = "gone", donePropId = "gone", colorPropId = "gone", sortPropId = "gone")
        val mapper = NotionPageMapper(broken, schema("data_source_calendar.json"))
        val event = mapper.map(pages("query_calendar_p1.json").first()).event!!
        assertEquals("", event.title)
        assertFalse(event.done)
        assertNull(event.colorKey)
    }

    @Test
    fun missingDatePropertyDropsEvent() {
        val mapper = NotionPageMapper(calendarMapping.copy(datePropId = "gone"), schema("data_source_calendar.json"))
        assertNull(mapper.map(pages("query_calendar_p1.json").first()).event)
    }

    @Test
    fun trashedPageDropsEvent() {
        val mapper = NotionPageMapper(calendarMapping, schema("data_source_calendar.json"))
        val trashed = pages("query_calendar_p1.json").first().copy(inTrash = true)
        assertNull(mapper.map(trashed).event)
    }
    /** 제목의 언급(@): 공유 안 된 페이지는 'Untitled' 대신 알아볼 수 있게, 공유된 페이지는 노션이 준 이름 그대로. */
    @Test
    fun titleMentions() {
        val mapper = NotionPageMapper(calendarMapping, schema("data_source_calendar.json"))
        fun page(titleParts: String) = NotionJson.decodeFromString(
            com.ysdoing.notioncal.core.notion.dto.PageDto.serializer(),
            """{"id":"m1","url":"","last_edited_time":"2026-10-13T04:12:53.054Z","properties":{
                "Name":{"id":"title","type":"title","title":[$titleParts]},
                "Date":{"id":"dAtE","type":"date","date":{"start":"2026-10-14"}}}}""",
        )
        val unshared = """{"type":"mention","mention":{"type":"page","page":{"id":"p"}},"plain_text":"Untitled","href":"https://www.notion.so/p"}"""
        val shared = """{"type":"mention","mention":{"type":"page","page":{"id":"p"}},"plain_text":"섹션 2: 인사부","href":"https://www.notion.so/p"}"""
        val text = """{"type":"text","text":{"content":" DS6PJS"},"plain_text":" DS6PJS"}"""

        val hidden = mapper.map(page("$unshared,$text")).event!!
        assertEquals("@공유 안 된 페이지 DS6PJS", hidden.title)
        assertTrue(hidden.titleHasMention)
        assertEquals("섹션 2: 인사부 DS6PJS", mapper.map(page("$shared,$text")).event!!.title)
        // 언급 없는 제목, 그리고 진짜 글자 "Untitled"는 그대로 둔다.
        val plain = mapper.map(page("""{"type":"text","text":{"content":"Untitled"},"plain_text":"Untitled"}""")).event!!
        assertEquals("Untitled", plain.title)
        assertFalse(plain.titleHasMention)
    }

    /** HB-02: ✅ 습관 기록의 '습관' relation이 가리키는 🌱 습관 page ID를 읽는다. 연결이 비면 null. */
    @Test
    fun habitLogReadsRelationTarget() {
        val logMapping = PropertyMapping("title", "nAlJ", null, null, null, null, linkPropId = "sB%3Fg")
        val schema = schema("data_source_habit_log.json")
        val (linked, unlinked) = pages("query_habit_log.json").map { NotionPageMapper(logMapping, schema).map(it).event!! }
        assertEquals("habit-meditate", linked.linkPageId)
        assertEquals("명상 2026-10-19", linked.title)
        assertEquals(LocalDate.of(2026, 10, 19), linked.startDate)
        assertNull(unlinked.linkPageId)
        // 연결 속성을 매핑하지 않으면(📅 Calendar 등) relation을 읽지 않는다.
        val plain = NotionPageMapper(logMapping.copy(linkPropId = null), schema).map(pages("query_habit_log.json").first()).event!!
        assertNull(plain.linkPageId)
        // 스키마의 relation 대상(HB-01 등록 때 🌱 습관을 찾는 데 쓴다).
        val relation = schema.properties.getValue("습관").relation!!
        assertEquals("cccccccc-0000-4000-8000-000000000003", relation.dataSourceId)
        assertEquals("cccccccc-0000-4000-8000-0000000000db", relation.databaseId)
    }
}
