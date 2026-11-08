package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.EditableEvent
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.designsystem.DiscardChangesDialog
import com.ysdoing.notioncal.core.designsystem.DiscardGuard
import com.ysdoing.notioncal.core.designsystem.rememberDiscardGuard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * ED-04를 일정에도(종합 진단 1단계): 편집·추가 폼은 저장하지 않은 내용이 생기고 사라지는 것을 알린다. 날짜 시트·위젯 퀵 시트는 이 값으로
 * 닫기 전에 '변경을 버릴까요?'를 묻는다. 고친 것을 되돌리면 다시 '없음'이고, 폼이 사라지면(목록으로 돌아감) '없음'으로 알린다.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class EditorDirtyTest {
    @get:Rule
    val compose = createComposeRule()

    private val day = LocalDate.of(2026, 10, 13)
    private val event = CalendarEvent(
        pageId = "p1", dataSourceId = "ds", title = "영어 스터디", startDate = day, endDate = null, startTime = null, endTime = null,
        isAllDay = true, done = false, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )
    private val source = DataSourceEntity(
        dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
        datePropId = "d", donePropId = "done", doneType = "CHECKBOX", doneOption = null, undoneOption = null,
        colorPropId = "c", sortPropId = null,
    )

    @Test
    fun editingReportsUnsavedChangesAndRevertingClearsThem() {
        var dirty = false
        compose.setContent {
            MaterialTheme { EventEditorForm(EditableEvent(event, null, emptyList(), "Calendar"), onSave = {}, onDelete = {}, onDirtyChange = { dirty = it }) }
        }
        compose.waitForIdle()
        assertFalse(dirty)
        compose.onNodeWithText("영어 스터디").performTextReplacement("영어 회화")
        compose.waitForIdle()
        assertTrue(dirty)
        compose.onNodeWithText("영어 회화").performTextReplacement("영어 스터디") // 원래대로 돌리면 고친 것이 없다
        compose.waitForIdle()
        assertFalse(dirty)
        compose.onNodeWithText("완료").performClick()
        compose.waitForIdle()
        assertTrue(dirty)
    }

    @Test
    fun aHalfTypedNewEventCountsAndLeavingTheFormClearsIt() {
        val reports = mutableListOf<Boolean>()
        var shown by mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (shown) AddForm(day, listOf(source), "ds", autoFocus = false, onAdd = {}, onDirtyChange = { reports += it }) else Text("목록")
            }
        }
        compose.onNodeWithText("제목").performTextInput("장보")
        compose.waitForIdle()
        assertEquals(true, reports.last())
        shown = false // 같은 시트에서 폼이 빠지면(편집 → 목록) 고친 내용도 없는 것으로 알린다
        compose.waitForIdle()
        assertEquals(false, reports.last())
    }

    @Test
    fun theSharedDiscardDialogKeepsOrDiscards() {
        var discarded = false
        var kept = false
        compose.setContent { MaterialTheme { DiscardChangesDialog(onDiscard = { discarded = true }, onKeep = { kept = true }) } }
        compose.onNodeWithText("변경을 버릴까요?").assertExists()
        compose.onNodeWithText("계속 편집").performClick()
        compose.onNodeWithText("버리기").performClick()
        assertTrue(kept && discarded)
    }

    @Test
    fun aNotionTitleWithSpacesAroundItIsNotAnUnsavedChange() {
        var dirty = true
        val spaced = event.copy(title = "회의 ")
        compose.setContent {
            MaterialTheme { EventEditorForm(EditableEvent(spaced, null, emptyList(), "Calendar"), onSave = {}, onDelete = {}, onDirtyChange = { dirty = it }) }
        }
        compose.waitForIdle()
        assertFalse(dirty) // 열고 아무것도 안 고쳤으면 닫을 때 묻지 않는다
    }

    @Test
    fun theSharedGuardClosesAtOnceWhenCleanAndAsksFirstWhenDirty() {
        var closed = 0
        lateinit var guard: DiscardGuard
        compose.setContent { MaterialTheme { guard = rememberDiscardGuard(onClose = { closed++ }) } }
        compose.runOnIdle { assertTrue(guard.mayClose()) } // 고친 것이 없으면 바로 닫아도 된다
        compose.runOnIdle { guard.dirty = true; assertFalse(guard.mayClose()) }
        compose.onNodeWithText("변경을 버릴까요?").assertExists()
        compose.onNodeWithText("계속 편집").performClick()
        compose.onNodeWithText("변경을 버릴까요?").assertDoesNotExist()
        assertEquals(0, closed)
        compose.runOnIdle { guard.mayClose() }
        compose.onNodeWithText("버리기").performClick()
        assertEquals(1, closed)
    }
}
