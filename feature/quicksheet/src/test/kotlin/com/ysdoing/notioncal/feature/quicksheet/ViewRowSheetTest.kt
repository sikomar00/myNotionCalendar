package com.ysdoing.notioncal.feature.quicksheet

import androidx.activity.ComponentActivity
import androidx.activity.ComponentDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.view.ViewBoard
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewRow
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog

/** Phase 11b ED-04: 저장 안 한 변경이 있으면 시트를 닫기 전에 '변경을 버릴까요?'를 먼저 묻는다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewRowSheetTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private val title = ViewColumn("t", "이름", "title")
    private val board = ViewBoard("v1", "할 일", "table", listOf("이름"), emptyList(), 1, null, listOf(title))
    private val row = ViewRow("p1", "https://notion.so/p1", listOf("첫째"), typed = mapOf("t" to JsonPrimitive("첫째")))
    private var dismissed = 0

    private fun show() {
        compose.setContent {
            MaterialTheme {
                ViewRowSheet(board, row, ViewRowActions(onSave = { true }, onTrash = {}, onOpen = {}, onClose = { dismissed++ }))
            }
        }
        compose.waitForIdle()
    }

    /** 뒤로 가기 키: 시트는 자기 창(대화상자)에서 뒤로 가기를 받는다. */
    private fun pressBack() {
        compose.runOnUiThread { (ShadowDialog.getLatestDialog() as ComponentDialog).onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
    }

    @Test
    fun closingWithoutChangesDismissesAtOnce() {
        show()
        pressBack()
        assertEquals(1, dismissed)
        compose.onNodeWithText("변경을 버릴까요?").assertDoesNotExist()
    }

    @Test
    fun closingWithUnsavedChangesAsksFirstAndDiscardingCloses() {
        show()
        compose.onNodeWithText("이름").performTextInput("!")
        pressBack()
        compose.onNodeWithText("변경을 버릴까요?").assertExists()
        assertEquals(0, dismissed) // 묻는 동안은 닫지 않는다
        compose.onNodeWithText("버리기").performClick()
        assertEquals(1, dismissed)
    }

    @Test
    fun keepEditingLeavesTheSheetAndTheTypedTextAlone() {
        show()
        compose.onNodeWithText("이름").performTextInput("!")
        pressBack()
        compose.onNodeWithText("계속 편집").performClick()
        compose.onNodeWithText("변경을 버릴까요?").assertDoesNotExist()
        compose.onNodeWithText("!첫째").assertExists() // 입력은 커서(맨 앞)에 들어간다. 시트와 입력한 글이 그대로다
        assertEquals(0, dismissed)
    }
}
