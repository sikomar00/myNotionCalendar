package com.ysdoing.notioncal.feature.quicksheet

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.ColorOption
import com.ysdoing.notioncal.core.data.EditableEvent
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime

/** WI-01·02·03·05 퀵 시트 화면: 입력 → 쓰기 요청 값, 바뀐 필드만 저장, 체크 토글. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class QuickSheetUiTest {
    @get:Rule
    val compose = createComposeRule()

    private val source = DataSourceEntity(
        dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
        datePropId = "d", donePropId = "done", doneType = "CHECKBOX", doneOption = null, undoneOption = null,
        colorPropId = "c", sortPropId = null,
    )
    private val day = LocalDate.of(2026, 10, 13)

    private fun event(id: String, title: String, done: Boolean = false, time: String? = null) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = title, startDate = day, endDate = null,
        startTime = time?.let(Instant::parse), endTime = time?.let { Instant.parse(it).plusSeconds(3600) },
        isAllDay = time == null, done = done, colorHex = "#0B6E99", sourceColorHex = "#0B6E99",
        url = "https://www.notion.so/$id", pending = id == "p2",
    )

    @Test
    fun addFormSubmitsTitleDateAndDefaultSource() {
        var added: List<Any?>? = null
        compose.setContent {
            MaterialTheme {
                AddForm(day, listOf(source), "ds", autoFocus = false, onAdd = { added = listOf(it.title, it.date, it.time, it.dataSourceId) })
            }
        }
        compose.onNodeWithText("제목").performTextInput("영어 스터디")
        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf("영어 스터디", day, null, "ds"), added)
    }

    @Test
    fun quickInputFillsDateTimeAndTitle() {
        // WI-07: '내일 3시 회의' → 다음 날 15:00~16:00, 제목은 '회의'.
        var added: NewEvent? = null
        compose.setContent {
            MaterialTheme { AddForm(day, listOf(source), "ds", autoFocus = false, onAdd = { added = it }, today = day) }
        }
        compose.onNodeWithText("제목").performTextInput("내일 3시 회의")
        compose.onNodeWithText("제목: 회의").assertExists() // 해석 미리보기
        compose.onNodeWithText("추가").performClick()
        assertEquals(NewEvent("회의", day.plusDays(1), TimeRange(LocalTime.of(15, 0), LocalTime.of(16, 0)), "ds"), added)
    }

    @Test
    fun quickInputRangeBecomesMultiDayEvent() {
        var added: NewEvent? = null
        compose.setContent {
            MaterialTheme { AddForm(day, listOf(source), "ds", autoFocus = false, onAdd = { added = it }, today = day) }
        }
        compose.onNodeWithText("제목").performTextInput("20일~22일 출장")
        compose.onNodeWithText("추가").performClick()
        assertEquals(NewEvent("출장", LocalDate.of(2026, 10, 20), null, "ds", endDate = LocalDate.of(2026, 10, 22)), added)
        assertEquals("2026-10-22", dateValueOf(added!!.date, null, added!!.endDate).endDate)
    }

    @Test
    fun plainTitleKeepsChosenDay() {
        var added: NewEvent? = null
        compose.setContent {
            MaterialTheme { AddForm(day, listOf(source), "ds", autoFocus = false, onAdd = { added = it }, today = day) }
        }
        compose.onNodeWithText("제목").performTextInput("장보기")
        compose.onNodeWithText("추가").performClick()
        assertEquals(NewEvent("장보기", day, null, "ds"), added)
    }

    @Test
    fun editorSendsOnlyChangedFields() {
        var saved: EventChange? = null
        val item = EditableEvent(event("p1", "영어 스터디"), "opt-learn", listOf(ColorOption("opt-learn", "Learn", "blue")), "Calendar")
        compose.setContent { MaterialTheme { EventEditorForm(item, onSave = { saved = it }, onDelete = {}) } }

        compose.onNodeWithText("영어 스터디").performTextReplacement("영어 회화")
        compose.onNodeWithText("저장").performClick()

        assertEquals(EventChange(title = "영어 회화"), saved)
    }

    @Test
    fun editorDoneToggleOnly() {
        var saved: EventChange? = null
        val item = EditableEvent(event("p1", "영어 스터디"), null, emptyList(), "Calendar")
        compose.setContent { MaterialTheme { EventEditorForm(item, onSave = { saved = it }, onDelete = {}) } }
        compose.onNodeWithText("완료").performClick() // 줄 전체가 토글이라 글자를 눌러도 체크된다
        compose.onNodeWithText("저장").performClick()
        assertEquals(true, saved!!.done)
        assertNull(saved!!.title)
        assertNull(saved!!.date)
    }

    @Test
    fun dayListToggle() {
        var toggled: Pair<String, Boolean>? = null
        compose.setContent {
            MaterialTheme { DayList(listOf(event("p1", "영어 스터디")), onToggle = { e, d -> toggled = e.pageId to d }, onOpen = {}) }
        }
        compose.onNodeWithContentDescription("영어 스터디 완료 표시").performClick()
        assertEquals("p1" to true, toggled)
    }

    @Test
    fun addFormSurvivesProcessRecreation() {
        // PF-06: 활동 유지 안 함·프로세스 회수 뒤에도 입력 중인 제목과 시간이 남는다.
        var added: List<Any?>? = null
        val restoration = StateRestorationTester(compose)
        restoration.setContent {
            MaterialTheme {
                AddForm(day, listOf(source), "ds", autoFocus = false, onAdd = { added = listOf(it.title, it.date, it.time, it.dataSourceId) })
            }
        }
        compose.onNodeWithText("제목").performTextInput("영어 스터디")
        compose.onNodeWithContentDescription("시간 지정").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("추가").performClick()
        assertEquals(listOf("영어 스터디", day, TimeRange(LocalTime.of(9, 0), LocalTime.of(10, 0)), "ds"), added)
    }

    @Test
    fun editorKeepsTurnedOffTimeAfterRecreation() {
        // 원래 시간 일정에서 시간을 끈 상태가 복원 뒤 원래 시간으로 되돌아가면 안 된다.
        var saved: EventChange? = null
        val item = EditableEvent(event("p1", "회의", time = "2026-10-13T01:00:00Z"), null, emptyList(), "Calendar")
        val restoration = StateRestorationTester(compose)
        restoration.setContent { MaterialTheme { EventEditorForm(item, onSave = { saved = it }, onDelete = {}) } }
        compose.onNodeWithContentDescription("시간 지정").performClick()

        restoration.emulateSavedInstanceStateRestore()

        compose.onNodeWithText("저장").performClick()
        assertEquals(dateValueOf(day, null), saved!!.date)
    }

    @Test
    fun timeRangeSaverRoundTrip() {
        val range = TimeRange(LocalTime.of(15, 0), LocalTime.of(16, 30))
        val scope = androidx.compose.runtime.saveable.SaverScope { true }
        with(TimeRangeSaver) {
            assertEquals(range, restore(scope.save(range)!!))
            assertNull(restore(scope.save(null)!!))
        }
    }

    @Test
    fun dateValueUsesSeoulTime() {
        val v = dateValueOf(day, TimeRange(LocalTime.of(15, 0), LocalTime.of(16, 30)))
        assertEquals(day.atTime(15, 0).atZone(APP_ZONE).toInstant().toEpochMilli(), v.startTime)
        assertEquals(90 * 60_000L, v.endTime!! - v.startTime!!)
        // 종료가 시작보다 이르면 1시간으로 맞춘다.
        val fixed = dateValueOf(day, TimeRange(LocalTime.of(15, 0), LocalTime.of(14, 0)))
        assertEquals(3_600_000L, fixed.endTime!! - fixed.startTime!!)
        assertEquals("2026-10-13", dateValueOf(day, null).startDate)
        assertNull(dateValueOf(day, null).startTime)
    }

    @Test
    fun screenshotDaySheet() {
        compose.setContent {
            MaterialTheme {
                Surface {
                    Column {
                        AddForm(day, listOf(source), "ds", autoFocus = false, onAdd = {})
                        DayList(
                            listOf(event("p1", "영어 스터디"), event("p2", "회의", time = "2026-10-13T01:00:00Z"), event("p3", "운동", done = true)),
                            onToggle = { _, _ -> },
                            onOpen = {},
                        )
                    }
                }
            }
        }
        compose.onRoot().captureRoboImage("src/test/screenshots/quicksheet_day.png")
    }

    @Test
    fun screenshotEditor() {
        val item = EditableEvent(
            event("p1", "영어 스터디", time = "2026-10-13T06:00:00Z"),
            "opt-learn",
            listOf(ColorOption("opt-event", "Event", "yellow"), ColorOption("opt-learn", "Learn", "blue"), ColorOption("opt-habit", "Habit", "red")),
            "Calendar",
        )
        compose.setContent { MaterialTheme { Surface { EventEditorForm(item, onSave = {}, onDelete = {}) } } }
        compose.onRoot().captureRoboImage("src/test/screenshots/quicksheet_edit.png")
    }
}
