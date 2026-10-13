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
}
