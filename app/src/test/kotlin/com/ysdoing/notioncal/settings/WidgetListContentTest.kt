package com.ysdoing.notioncal.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.ysdoing.notioncal.widget.PlacedWidget
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Phase 11f ST-07(최소판) 설정의 '위젯' 목록: 읽는 중엔 제목만, 비면 안내, 있으면 종류·요약과 눌러서 설정 열기. */
@RunWith(RobolectricTestRunner::class)
class WidgetListContentTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun whileLoadingOnlyTheTitleShows() {
        compose.setContent { MaterialTheme { WidgetListContent(null) {} } }
        compose.onNodeWithText("배치한 위젯").assertExists()
        compose.onNodeWithText("배치한 위젯이 없어요", substring = true).assertDoesNotExist()
    }

    @Test
    fun anEmptyListSaysHowToAddOne() {
        compose.setContent { MaterialTheme { WidgetListContent(emptyList()) {} } }
        compose.onNodeWithText("배치한 위젯이 없어요", substring = true).assertExists()
    }

    @Test
    fun eachWidgetShowsItsKindAndSummaryAndOpensItsSettingsOnPress() {
        val opened = mutableListOf<Int>()
        val widgets = listOf(PlacedWidget(7, "노션 위젯", "탭 2개: 할 일, 독서"), PlacedWidget(9, "월간 달력", ""))
        compose.setContent { MaterialTheme { Column { WidgetListContent(widgets) { opened += it } } } } // 실제 페이지처럼 세로로 쌓는다(안 그러면 줄이 겹쳐 눌린다)
        compose.onNodeWithText("탭 2개: 할 일, 독서").assertExists()
        compose.onNodeWithContentDescription("노션 위젯, 탭 2개: 할 일, 독서, 설정 열기").performClick()
        compose.onNodeWithContentDescription("월간 달력, 설정 열기").performClick() // 요약이 없으면 종류만 읽는다
        assertEquals(listOf(7, 9), opened)
    }
}
