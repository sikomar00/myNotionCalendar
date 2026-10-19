package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.HabitPropIds
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.sourceKind
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Instant

/** HB-01 등록: §8.4 두 프리셋에서 추천 매핑이 나오고, 두 행(✅ HABIT_LOG·🌱 HABIT)을 한 쌍으로 만든다. */
class HabitRegistrationTest {
    private val notion = FakeNotionStore { Instant.EPOCH }
    private fun schema(json: String) = NotionJson.decodeFromString(DataSourceDto.serializer(), json)

    @Test
    fun logPresetFindsTitleDateAndRelation() {
        assertEquals(HabitLogChoice("title", "nAlJ", "rEl"), HabitSuggester.suggestLog(notion.logSchema))
        val target = HabitSuggester.relationTarget(notion.logSchema, "rEl")!!
        assertEquals(FakeNotionStore.HABITS, target.dataSourceId)
    }

    @Test
    fun calendarWithSelfRelationStillSuggestsLogButDateIsRequired() {
        // 📅 Calendar도 relation(SubItem)이 있어 '습관 기록' 종류는 사용자가 고른다. 날짜가 없으면 기록 DB로 쓸 수 없다.
        val noDate = schema(
            """{"id":"h","properties":{"이름":{"id":"title","name":"이름","type":"title"},
               "기록":{"id":"gIrO","name":"기록","type":"relation","relation":{"data_source_id":"log"}}}}""",
        )
        assertNull(HabitSuggester.suggestLog(noDate))
        assertEquals(true, HabitSuggester.looksLikeHabitDatabase(noDate))
        assertEquals(false, HabitSuggester.looksLikeHabitDatabase(notion.logSchema))
    }

    @Test
    fun habitPresetMatchesPropertyNamesAndTypes() {
        assertEquals(
            HabitChoice(titlePropId = "title", emoji = "eMoJ", weeklyTarget = "gOaL", active = "aCtV", startDate = "sTrT", sortOrder = "oRdR"),
            HabitSuggester.suggestHabit(notion.habitSchema),
        )
        // 이름이 다르면 추측하지 않는다(숫자 속성 두 개를 바꿔 잡지 않게).
        val renamed = schema(
            """{"id":"h","properties":{"Name":{"id":"title","name":"Name","type":"title"},
               "N1":{"id":"n1","name":"N1","type":"number"},"N2":{"id":"n2","name":"N2","type":"number"}}}""",
        )
        assertEquals(HabitChoice("title", null, null, null, null, null), HabitSuggester.suggestHabit(renamed))
        val candidates = HabitSuggester.candidates(renamed)
        assertEquals(listOf("n1", "n2"), candidates.numbers.map { it.id })
    }

    @Test
    fun registrationBuildsPairAndKeepsExistingState() {
        val log = HabitTarget("log", "db-log", "✅ 습관 기록")
        val habit = HabitTarget(FakeNotionStore.HABITS, "db-habits", "🌱 습관")
        val habitChoice = HabitChoice("title", "eMoJ", "gOaL", "aCtV", "sTrT", "oRdR")
        val (logRow, habitRow) = habitRows(log, HabitLogChoice("title", "nAlJ", "rEl"), habit, habitChoice, "#0F7B6C", null, null, nextPosition = 3)

        assertEquals(SourceKind.HABIT_LOG, logRow.sourceKind)
        assertEquals("nAlJ", logRow.datePropId)
        assertEquals("rEl", logRow.linkPropId)
        assertEquals(FakeNotionStore.HABITS, logRow.linkDataSourceId)
        assertNull(logRow.donePropId)
        assertNull(logRow.colorPropId)
        assertEquals(3, logRow.position)

        assertEquals(SourceKind.HABIT, habitRow.sourceKind)
        assertNull(habitRow.datePropId)
        assertEquals(HabitPropIds("eMoJ", "gOaL", "aCtV", "sTrT", "oRdR"), HabitPropIds.parse(habitRow.habitPropIds))
        assertEquals(4, habitRow.position)

        // 다시 등록(NS-09 다시 연결)해도 켜기 상태·위치·동기화 기준은 지키고, 매핑이 바뀐 쪽만 처음부터 다시 받는다.
        val (again, habitAgain) = habitRows(
            log, HabitLogChoice("title", "nAlJ", "rEl"), habit, habitChoice.copy(emoji = null), "#0F7B6C",
            logRow.copy(enabled = false, lastSyncAt = 500, position = 7, schemaIssues = "[]"),
            habitRow.copy(enabled = false, lastSyncAt = 500, position = 8),
            nextPosition = 9,
        )
        assertEquals(false, again.enabled)
        assertEquals(7, again.position)
        assertEquals(500L, again.lastSyncAt)
        assertNull(again.schemaIssues)
        assertEquals(8, habitAgain.position)
        assertNull(habitAgain.lastSyncAt)
    }
}
