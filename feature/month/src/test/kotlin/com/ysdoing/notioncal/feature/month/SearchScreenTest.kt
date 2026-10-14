package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.CalendarEvent
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate
import java.time.ZonedDateTime

/** MV-10 검색 화면: 입력 → 결과 → 탭, 이전 검색어의 결과는 보이지 않음, 빈 결과 안내. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class SearchScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private fun event(id: String, title: String, date: String, time: String? = null, end: String? = null, done: Boolean = false) = CalendarEvent(
        pageId = id, dataSourceId = "ds", title = title, startDate = LocalDate.parse(date), endDate = end?.let(LocalDate::parse),
        startTime = time?.let { ZonedDateTime.parse("${date}T$it+09:00[Asia/Seoul]").toInstant() }, endTime = null,
        isAllDay = time == null, done = done, colorHex = "#0B6E99", sourceColorHex = "#0B6E99", url = "",
    )

    private val found = listOf(
        event("a", "팀 회의", "2026-10-15", time = "10:00"),
        event("b", "회의 준비 워크숍", "2026-10-20", end = "2026-10-21"),
        event("c", "분기 회의", "2026-09-30", done = true),
    )

    private var query by mutableStateOf("")
    private var results by mutableStateOf<SearchResults?>(null)
    private val picked = mutableListOf<String>()

    private fun setUp() {
        compose.setContent {
            MaterialTheme {
                SearchScreen(query, results, onQueryChange = { query = it }, onBack = {}, onPick = { picked += it.pageId }, autoFocus = false)
            }
        }
    }

    @Test
    fun typeSeeResultsAndPick() {
        setUp()
        compose.onNodeWithText("찾을 일정 제목을 입력하세요.").assertExists()
        compose.onNodeWithText("일정 제목 검색").performTextInput("회의")
        assertEquals("회의", query)
        // 결과가 오기 전(이전 검색어 결과)에는 보이지 않는다.
        results = SearchResults("회", found)
        compose.onNodeWithText("팀 회의").assertDoesNotExist()
        results = SearchResults("회의", found)
        compose.onNodeWithText("2026. 10. 15. (목) 10:00").assertExists()
        compose.onNodeWithText("2026. 10. 20. (화) – 2026. 10. 21.").assertExists()
        compose.onNodeWithText("분기 회의").performClick()
        assertEquals(listOf("c"), picked)
    }

    @Test
    fun noResult() {
        query = "없는말"
        results = SearchResults("없는말", emptyList())
        setUp()
        compose.onNodeWithText("찾는 일정이 없습니다.").assertExists()
    }

    @Test
    fun screenshotSearch() {
        query = "회의"
        results = SearchResults("회의", found)
        setUp()
        compose.onRoot().captureRoboImage("src/test/screenshots/search.png")
    }
}
