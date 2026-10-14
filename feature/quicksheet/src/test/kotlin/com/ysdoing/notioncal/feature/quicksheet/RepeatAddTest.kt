package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

}
