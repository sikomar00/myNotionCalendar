package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isToggleable
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/**
 * NS-10 반복 일정 추가 흐름. 대화상자를 여러 번 띄우므로 네이티브 그래픽 모드 없이 돌린다
 * (QuickSheetUiTest의 NATIVE 모드에서는 대화상자가 뜬 뒤 Compose가 쉬지 않는 Robolectric 문제가 있었다).
 */
@RunWith(RobolectricTestRunner::class)
class RepeatAddTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = DataSourceEntity(
        dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
        datePropId = "d", donePropId = null, doneType = null, doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null,
    )
    private val day = LocalDate.of(2026, 10, 13)

    /** NS-10: 반복을 고르면 '추가' 때 개수·미리보기를 확인받고, 확인하면 날짜마다 하나씩 만든다. */
    @Test
    fun repeatedAddConfirmsCountThenCreatesEach() {
        var single: NewEvent? = null
        var repeated: List<NewEvent>? = null
        compose.setContent {
            MaterialTheme {
                AddForm(
                    day, listOf(source), "ds", autoFocus = false, today = day,
                    onAdd = { single = it }, onAddRepeated = { repeated = it },
                )
            }
        }
        compose.onNodeWithText("제목").performTextInput("3333")
        compose.onNodeWithText("반복 안 함").performClick()
        compose.onAllNodesWithText("일")[0].performClick() // 종류: 매일
        compose.onAllNodes(hasSetTextAction()).let { it[it.fetchSemanticsNodes().size - 1] }.performTextReplacement("3")
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("매일, 3번").assertExists()
        compose.onNodeWithText("추가").performClick()

        compose.onNodeWithText("반복 일정 3개 만들기").assertExists()
        compose.onNodeWithText("10/13(화), 10/14(수), 10/15(목)").assertExists()
        compose.onNodeWithText("3개 만들기").performClick()
        assertNull(single)
        assertEquals(listOf(day, day.plusDays(1), day.plusDays(2)), repeated!!.map { it.date })
        assertEquals(setOf("3333"), repeated!!.map { it.title }.toSet())
        compose.onNodeWithText("반복 안 함").assertExists() // 만든 뒤 반복은 꺼진다
    }

    /** 반복 끝 조건: 라디오로 '횟수'·'날짜까지'를 고르고, 고르지 않은 쪽 입력은 꺼진다. 간격은 단위와 함께 보인다. */
    @Test
    fun endConditionRadiosSwitchInputs() {
        compose.setContent {
            MaterialTheme {
                AddForm(day, listOf(source), "ds", autoFocus = false, today = day, onAdd = {}, onAddRepeated = {})
            }
        }
        compose.onNodeWithText("반복 안 함").performClick()
        compose.onNodeWithText("1주마다").assertExists()
        compose.onNodeWithContentDescription("간격 늘리기").performClick()
        compose.onNodeWithText("2주마다").assertExists()

        val count = compose.onNodeWithText("10") // 횟수 칸(비활성이면 입력 동작이 없어지므로 값으로 찾는다)
        count.assertIsEnabled()
        compose.onNodeWithText("2027. 1. 13.").performScrollTo().assertIsNotEnabled()

        compose.onNodeWithText("날짜까지").performScrollTo().performClick()
        count.assertIsNotEnabled()
        compose.onNodeWithText("2027. 1. 13.").assertIsEnabled()
        compose.onNodeWithText("저장").performClick()
        compose.onNodeWithText("2주마다 화, 1/13까지").assertExists()
    }

    /** 시간을 켜도 반복·DB 칩이 밀려 잘리지 않는다: 날짜·시간 줄 아래 따로 두고, DB 칩은 가로 스크롤로 닿는다. */
    @Test
    fun chipsStayReachableWhenTimeIsOn() {
        val courses = source.copy(dataSourceId = "ds2", name = "Courses")
        compose.setContent {
            MaterialTheme {
                AddForm(day, listOf(source, courses), "ds", autoFocus = false, today = day, onAdd = {}, onAddRepeated = {})
            }
        }
        compose.onNode(isToggleable() and hasText("종일")).performClick() // 종일 끄기 → 시작·종료 칩
        compose.onNodeWithText("09:00").assertExists()

        val date = compose.onNodeWithText("10월 13일 (화)").fetchSemanticsNode().boundsInRoot
        val repeat = compose.onNodeWithText("반복 안 함").fetchSemanticsNode().boundsInRoot
        assertTrue("반복 칩은 날짜·시간 줄 아래에 있다", repeat.top >= date.bottom)
        compose.onNodeWithText("Calendar").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Courses").performScrollTo().assertIsDisplayed()
    }

    /** '종일' 스위치는 켜짐 = 종일 일정. 끄면 시간 칩이 나오고, '종일' 글자는 그대로 남는다(다른 캘린더 앱과 같은 방향). */
    @Test
    fun allDaySwitchOnMeansAllDay() {
        var added: NewEvent? = null
        compose.setContent {
            MaterialTheme { AddForm(day, listOf(source), "ds", autoFocus = false, today = day, onAdd = { added = it }) }
        }
        val allDay = compose.onNode(isToggleable() and hasText("종일"))
        allDay.assertIsOn()
        compose.onNodeWithText("09:00").assertDoesNotExist()

        allDay.performClick()
        allDay.assertIsOff()
        compose.onNodeWithText("09:00").assertExists()
        compose.onNodeWithText("제목").performTextInput("회의")
        compose.onNodeWithText("추가").performClick()
        assertEquals("09:00", added!!.time!!.start.toString())
    }
}
