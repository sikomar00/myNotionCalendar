package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.ysdoing.notioncal.core.data.view.ActionChange
import com.ysdoing.notioncal.core.data.view.ActionOp
import com.ysdoing.notioncal.core.data.view.ViewAction
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

/**
 * Phase 12c IX-07 행 편집 시트의 액션 버튼 줄: 이 보기의 액션마다 버튼이 있고, 누르면 그 행에 적용한 뒤 닫는다.
 * 저장 전 변경이 있으면 액션이 그 값을 덮으니 막고 이유를 알린다. 적용에 실패하면 시트를 열어 둔 채 알린다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class ViewRowActionButtonsTest {
    @get:Rule
    val compose = createComposeRule()

    private val title = ViewColumn("t", "이름", "title")
    private val day = ViewColumn("dt", "날짜", "date")
    private val board = ViewBoard("v1", "할 일", "table", listOf("이름", "날짜"), emptyList(), 1, null, listOf(title, day))
    private val row = ViewRow("p1", "https://notion.so/p1", listOf("첫째", "2026-11-04"), typed = mapOf("t" to JsonPrimitive("첫째")))
    private val tomorrow = ViewAction("a1", "v1", "내일로", listOf(ActionChange("dt", ActionOp.SHIFT_DAYS, days = 1)))
    private val done = ViewAction("a2", "v1", "완료로", listOf(ActionChange("dt", ActionOp.TODAY)))

    private val applied = mutableListOf<String>()
    private var closed = 0
    private var result = true

    private fun show(vararg actions: ViewAction) {
        compose.setContent {
            MaterialTheme {
                ViewRowEditor(
                    board, row,
                    ViewRowActions(
                        onSave = { true }, onTrash = {}, onOpen = {}, onClose = { closed++ },
                        actions = actions.toList(), onAction = { applied += it.id; result },
                    ),
                )
            }
        }
    }

    @Test
    fun everyActionOfTheViewIsAButtonAndPressingOneAppliesItAndCloses() {
        show(tomorrow, done)
        compose.onNodeWithText("완료로").performScrollTo()
        compose.onNodeWithText("내일로").assertIsEnabled().performClick()
        compose.waitForIdle()
        assertEquals(listOf("a1"), applied)
        assertEquals(1, closed)
    }

    @Test
    fun withoutActionsThereIsNoButtonRowAndNoHint() {
        show()
        compose.onNodeWithText("저장").performScrollTo().assertIsNotEnabled()
        assertEquals(0, compose.onAllNodesWithText("내일로").fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodesWithText("저장하지 않은 변경이 있어서 액션을 쓸 수 없어요. 먼저 저장하세요").fetchSemanticsNodes().size)
    }

    @Test
    fun unsavedEditsDisableTheActionsAndSayWhy() {
        show(tomorrow)
        compose.onNodeWithText("이름").performTextClearance()
        compose.onNodeWithText("이름").performTextInput("둘째")
        compose.onNodeWithText("내일로").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("저장하지 않은 변경이 있어서 액션을 쓸 수 없어요. 먼저 저장하세요").assertExists()
        assertEquals(emptyList<String>(), applied)
    }

    @Test
    fun aFailedActionKeepsTheSheetOpenAndSaysSo() {
        result = false
        show(tomorrow)
        compose.onNodeWithText("내일로").performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithText("적용하지 못했어요. 다시 시도해 주세요").assertExists()
        assertEquals(0, closed)
        assertEquals(listOf("a1"), applied)
    }
}
