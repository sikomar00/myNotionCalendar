package com.ysdoing.notioncal.feature.quicksheet

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Phase 11c ED-01: 위젯 행을 누르면 뜨는 편집 시트(투명 액티비티)의 내용. 행이 없으면 닫고, 변경이 있으면 닫기 전에 묻는다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewRowHostTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val title = ViewColumn("t", "이름", "title")
    private val board = ViewBoard("v1", "할 일", "table", listOf("이름"), emptyList(), 1, null, listOf(title))
    private val row = ViewRow("p1", "https://notion.so/p1", listOf("첫째"), typed = mapOf("t" to JsonPrimitive("첫째")))
    private val saved = mutableListOf<Map<String, JsonElement>>()
    private var closed = 0

    private fun show(state: ViewRowState) {
        compose.setContent {
            MaterialTheme {
                ViewRowHost(state, ViewRowActions(onSave = { saved += it; true }, onTrash = {}, onOpen = {}, onClose = { closed++ }))
            }
        }
        compose.waitForIdle()
    }

    private fun pressBack() {
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun aMissingRowClosesTheSheetAtOnce() {
        show(ViewRowState.Missing) // 그 사이 지워졌거나 보기 등록이 사라졌다
        assertEquals(1, closed)
    }

    @Test
    fun whileLoadingNothingIsShownAndNothingCloses() {
        show(ViewRowState.Loading)
        compose.onNodeWithText("저장").assertDoesNotExist()
        assertEquals(0, closed)
    }

    @Test
    fun theReadyRowShowsTheEditorAndSavingClosesIt() {
        show(ViewRowState.Ready(board, row))
        compose.onNodeWithText("이름").performTextInput("!")
        compose.onNodeWithText("저장").performScrollTo().performClick()
        assertEquals(1, saved.size)
        assertEquals(1, closed)
    }

    @Test
    fun backWithoutChangesClosesAndTappingOutsideDoesToo() {
        show(ViewRowState.Ready(board, row))
        pressBack()
        assertEquals(1, closed)
        compose.onNodeWithTag("viewRowScrim").performClick()
        assertEquals(2, closed)
    }

    @Test
    fun backWithUnsavedChangesAsksFirstAndKeepEditingStays() {
        show(ViewRowState.Ready(board, row))
        compose.onNodeWithText("이름").performTextInput("!")
        compose.waitForIdle() // 변경이 있음을 시트 바깥(호스트)에 알리는 효과가 한 프레임 뒤에 돈다
        pressBack()
        compose.onNodeWithText("변경을 버릴까요?").assertExists()
        assertEquals(0, closed)
        compose.onNodeWithText("계속 편집").performClick()
        compose.onNodeWithText("변경을 버릴까요?").assertDoesNotExist()
        pressBack()
        compose.onNodeWithText("버리기").performClick()
        assertEquals(1, closed)
    }
}
