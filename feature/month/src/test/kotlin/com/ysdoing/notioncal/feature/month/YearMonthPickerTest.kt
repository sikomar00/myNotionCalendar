package com.ysdoing.notioncal.feature.month

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.YearMonth

/** MV-03: 가운데 연도를 누르면 연도 목록에서 바로 고르고, 그 해의 월을 고른다. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w411dp-h760dp-xxhdpi")
class YearMonthPickerTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun pickYearFromListThenMonth() {
        var picked: YearMonth? = null
        compose.setContent {
            MaterialTheme { YearMonthPicker(YearMonth.of(2026, 10), onPick = { picked = it }, onDismiss = {}) }
        }
        compose.onNodeWithText("10월").assertExists()
        compose.onNodeWithContentDescription("연도 목록에서 고르기").performClick()
        // 연도 목록: 멀리 있는 해도 스크롤로 닿는다.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("2035년"))
        compose.onNodeWithText("2035년").performClick()

        compose.onNodeWithText("2035년").assertExists() // 목록이 닫히고 가운데 제목만 남는다(둘이면 실패)
        compose.onNodeWithText("3월").performClick()
        assertEquals(YearMonth.of(2035, 3), picked)
    }
}
