package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.ColorOption
import com.ysdoing.notioncal.core.data.calendar.EventFilter
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** MV-09 필터 시트: 값 칩·DB 스위치·완료 숨김·모두 보기. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class FilterSheetTest {
    @get:Rule
    val compose = createComposeRule()

    private val sources = listOf(
        FilterSource(
            id = "cal", name = "📅 Calendar", colorHex = "#0B6E99", hasColorProp = true,
            options = listOf(ColorOption("o-event", "Event", "blue"), ColorOption("o-learn", "Learn", "green"), ColorOption("o-habit", "Habit", "red")),
        ),
        FilterSource(id = "work", name = "업무", colorHex = "#D9730D", hasColorProp = false, options = emptyList()),
    )

    private var filter by mutableStateOf(EventFilter())

    private fun setUp() {
        compose.setContent {
            MaterialTheme { Surface { FilterContent(filter, sources, onChange = { filter = it }) } }
        }
    }

    @Test
    fun togglesAndReset() {
        setUp()
        compose.onNodeWithText("모두 보기").assertIsNotEnabled()
        compose.onNodeWithText("Habit").performClick()
        compose.onNodeWithText("값 없음").performClick()
        assertEquals(setOf("cal/o-habit", "cal/"), filter.hiddenOptionKeys)
        compose.onNodeWithText("Habit").performClick() // 다시 누르면 보인다
        assertEquals(setOf("cal/"), filter.hiddenOptionKeys)

        compose.onNodeWithContentDescription("업무").performClick()
        assertEquals(setOf("work"), filter.hiddenSourceIds)
        compose.onNodeWithContentDescription("📅 Calendar").performClick()
        compose.onNodeWithText("Event").assertDoesNotExist() // DB를 숨기면 값 칩도 감춘다

        compose.onNodeWithText("완료한 일정 숨기기").assertExists()
        compose.onNodeWithText("모두 보기").assertIsEnabled().performClick()
        assertEquals(EventFilter(), filter)
    }

    @Test
    fun screenshotFilter() {
        filter = EventFilter(hiddenOptionKeys = setOf("cal/o-habit"), hideDone = true)
        setUp()
        compose.onRoot().captureRoboImage("src/test/screenshots/filter_sheet.png")
    }
}
