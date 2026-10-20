package com.ysdoing.notioncal.feature.dday

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.core.data.CalendarEvent
import com.ysdoing.notioncal.core.data.dday.Dday
import com.ysdoing.notioncal.core.data.dday.DdayMath
import com.ysdoing.notioncal.core.data.dday.DdayMode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/** DD-01·02 디데이 화면: 고정 목록 표기, 세는 방식·해제, 일정 찾아 고정, 풀린 디데이 안내. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w384dp-h832dp-xxhdpi")
class DdayScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val today = LocalDate.of(2026, 10, 19)

    private fun dday(id: String, title: String, start: LocalDate, mode: DdayMode = DdayMode.DOWN) =
        Dday(id, title, start, null, mode, 7, "#0B6E99", DdayMath.label(start, null, today, mode))

    private fun event(id: String, title: String, date: LocalDate) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = title, startDate = date, endDate = null, startTime = null, endTime = null,
        isAllDay = true, done = false, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    @Test
    fun pinnedRowsShowLabelModeAndUnpin() {
        val modes = mutableListOf<Pair<String, DdayMode>>()
        val unpinned = mutableListOf<String>()
        val state = DdayUiState(pins = listOf(dday("exam", "기말고사", today.plusDays(12)), dday("old", "지난 행사", today.minusDays(10))))
        compose.setContent {
            MaterialTheme { DdayScreen(state, DdayActions(onMode = { id, m -> modes += id to m }, onUnpin = { unpinned += it })) }
        }
        compose.onNodeWithText("D-12").assertExists()
        compose.onNodeWithText("10/31 토").assertExists()
        compose.onNodeWithText("10/9 금").assertExists()
        compose.onNodeWithText("지난 지 오래돼 위젯에서 숨김").assertExists()
        compose.onNodeWithContentDescription("기말고사 고정 해제").performClick()
        assertEquals(listOf("exam"), unpinned)
        compose.onAllNodes(androidx.compose.ui.test.hasText("N일째"))[0].performClick()
        assertEquals(listOf("exam" to DdayMode.DAYS), modes)
    }

    @Test
    fun searchResultsPinAndNotice() {
        val pinned = mutableListOf<String>()
        var dismissed = 0
        val state = DdayUiState(
            query = "결혼",
            results = listOf(event("wedding", "결혼식", LocalDate.of(2027, 6, 12))),
            removedNotice = setOf("회식"),
        )
        compose.setContent {
            MaterialTheme { DdayScreen(state, DdayActions(onPin = { pinned += it }, onDismissNotice = { dismissed++ })) }
        }
        compose.onNodeWithText("고정한 디데이가 없습니다. 일정 편집 시트에서 '디데이로 고정'을 누르거나 아래에서 일정을 찾아 고정하세요.").assertExists()
        compose.onNodeWithText("고정").performClick()
        assertEquals(listOf("wedding"), pinned)
        compose.onNodeWithText("노션에서 지워져 고정을 풀었습니다: 회식").assertExists()
        compose.onNodeWithText("확인").performClick()
        assertEquals(1, dismissed)
    }
}
