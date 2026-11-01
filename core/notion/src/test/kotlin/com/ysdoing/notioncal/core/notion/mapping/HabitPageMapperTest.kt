package com.ysdoing.notioncal.core.notion.mapping

import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.QueryResponseDto
import com.ysdoing.notioncal.core.notion.fixture
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate

/** HB-01 🌱 습관 행 읽기. 값이 비거나 속성을 매핑하지 않으면 기본값(주 목표 7, 진행 중, 시작일 없음, 순서 없음). */
class HabitPageMapperTest {
    private fun pages() = NotionJson.decodeFromString(QueryResponseDto.serializer(), fixture("query_habits.json")).results

    private val mapping = HabitMapping(
        titlePropId = "title", emojiPropId = "eMoJ", weeklyTargetPropId = "gOaL", activePropId = "aCtV",
        startDatePropId = "sTrT", sortOrderPropId = "oRdR",
    )

    @Test
    fun readsHabitRowsWithDefaults() {
        val (meditate, blank, odd, trashed) = pages().map(HabitPageMapper(mapping)::map)
        assertEquals(
            RemoteHabit("habit-meditate", "명상", "🧘", 3, true, LocalDate.of(2026, 10, 19), 2.0, "https://www.notion.so/habit-meditate"),
            meditate,
        )
        assertEquals(RemoteHabit("habit-blank", "빈 습관", null, 7, false, null, null, "https://www.notion.so/habit-blank"), blank)
        // 주 목표 4.6 → 5(반올림), 진행 중 속성 없음 → 진행 중, 시간 포함 시작일 → 서울 날짜.
        assertEquals(5, odd!!.weeklyTarget)
        assertEquals(true, odd.active)
        assertEquals(LocalDate.of(2026, 10, 19), odd.startDate)
        assertNull(trashed) // 휴지통의 습관은 없는 것으로 본다
    }

    @Test
    fun unmappedPropertiesUseDefaults() {
        val habit = HabitPageMapper(HabitMapping(titlePropId = "title")).map(pages().first())!!
        assertEquals(RemoteHabit("habit-meditate", "명상", null, 7, true, null, null, "https://www.notion.so/habit-meditate"), habit)
    }

    @Test
    fun weeklyTargetIsClampedToOneToSeven() {
        fun target(value: String) = HabitPageMapper(mapping).map(
            NotionJson.decodeFromString(
                PageDto.serializer(),
                """{"id":"h","last_edited_time":"2026-10-19T00:00:00.000Z","properties":{
                   "이름":{"id":"title","type":"title","title":[{"plain_text":"x"}]},
                   "주 목표":{"id":"gOaL","type":"number","number":$value}}}""",
            ),
        )!!.weeklyTarget
        assertEquals(7, target("9"))
        assertEquals(1, target("0"))
        assertEquals(1, target("-2"))
        assertEquals(2, target("1.5"))
    }
}
