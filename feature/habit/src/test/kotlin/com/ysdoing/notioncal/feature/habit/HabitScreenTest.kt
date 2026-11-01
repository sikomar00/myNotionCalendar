package com.ysdoing.notioncal.feature.habit

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.core.data.db.HabitEntity
import com.ysdoing.notioncal.core.data.habit.HabitBoard
import com.ysdoing.notioncal.core.data.habit.HabitStats
import com.ysdoing.notioncal.core.data.habit.HabitSummary
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.DayOfWeek
import java.time.LocalDate

/** HB-02·HB-04 습관 탭: 습관마다 7일 칸(오늘 오른쪽 끝), 상태 문구, 칸을 누르면 그날 토글, 빈 상태 안내. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class HabitScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 21)

    private fun summary(id: String, name: String, emoji: String?, target: Int, done: Set<LocalDate>) = HabitSummary(
        HabitEntity(id, "habits", name, emoji, target, true, LocalDate.of(2026, 10, 1), null),
        HabitStats.status(done, today, DayOfWeek.SUNDAY, target, LocalDate.of(2026, 10, 1)),
    )

    private val meditation = (15..19).map { LocalDate.of(2026, 10, it) }.toSet() // 어제(20일) 쉼
    private val board = HabitBoard(
        today,
        listOf(
            summary("h-med", "명상", "🧘", 7, meditation),
            summary("h-walk", "산책", "🚶", 3, setOf(today)),
        ),
        mapOf("h-med" to meditation, "h-walk" to setOf(today)),
    )

    @Test
    fun showsHabitsWithLabelsAndTogglesDays() {
        val toggled = mutableListOf<Pair<String, LocalDate>>()
        compose.setContent { MaterialTheme { HabitScreen(board, onToggle = { id, date -> toggled += id to date }, onOpenSettings = {}) } }

        compose.onNodeWithText("오늘 1/2").assertExists()
        compose.onNodeWithText("어제 쉼 · 오늘 하면 이어짐").assertExists()
        compose.onNodeWithText("이번 주 1/3 · 2번 남음").assertExists()
        compose.onAllNodesWithTag("habitToday").assertCountEquals(2)
        compose.onAllNodesWithTag("habitDay").assertCountEquals(12)

        compose.onNodeWithContentDescription("명상, 10월 21일 수").assertIsOff().performClick()
        compose.onNodeWithContentDescription("명상, 10월 19일 월").assertIsOn().performClick()
        compose.onNodeWithContentDescription("산책, 10월 21일 수").assertIsOn()
        assertEquals(listOf("h-med" to today, "h-med" to LocalDate.of(2026, 10, 19)), toggled)
        compose.onNodeWithContentDescription("10월 기록: 5일 함").assertExists()
    }

    @Test
    fun emptyBoardExplainsRegistration() {
        var opened = 0
        compose.setContent { MaterialTheme { HabitScreen(HabitBoard(today, emptyList()), onToggle = { _, _ -> }, onOpenSettings = { opened++ }) } }
        compose.onNodeWithText("등록한 습관이 없습니다").assertExists()
        compose.onNodeWithText("설정 열기").performClick()
        assertEquals(1, opened)
    }
}
