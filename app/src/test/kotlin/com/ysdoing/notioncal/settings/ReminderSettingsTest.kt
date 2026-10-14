package com.ysdoing.notioncal.settings

import android.Manifest
import android.app.Application
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.reminders.ReminderRules
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/** EX-01·S25-11 알림 설정: 칩 추가·삭제, 종일 시각 표시, 정확한 알람 상태. */
@RunWith(RobolectricTestRunner::class)
class ReminderSettingsTest {
    @get:Rule
    val compose = createComposeRule()

    private var rules by mutableStateOf(ReminderRules())

    @Before
    fun grant() {
        shadowOf(ApplicationProvider.getApplicationContext<Application>()).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun setUp(exact: Boolean = true) {
        compose.setContent {
            MaterialTheme {
                androidx.compose.foundation.layout.Column {
                    ReminderSection(enabled = true, rules = rules, exactAllowed = exact, onEnable = {}, onRulesChange = { rules = it })
                }
            }
        }
    }

    @Test
    fun addAndRemoveTimedReminders() {
        setUp()
        compose.onNodeWithText("10분 전").assertExists()
        compose.onNodeWithText("09:00").assertExists() // 종일 당일 9시
        compose.onNodeWithText("추가").performClick()
        compose.onNodeWithText("얼마 전").performTextInput("2")
        compose.onNodeWithText("일").performClick()
        compose.clickLastWithText("추가")
        assertEquals(listOf(10, 2880), rules.timedMinutes)
        compose.onNodeWithText("2일 전").assertExists()
        compose.onNodeWithContentDescription("10분 전 알림 빼기").performClick()
        assertEquals(listOf(2880), rules.timedMinutes)
    }

    @Test
    fun showsInexactWarning() {
        setUp(exact = false)
        compose.onNodeWithText("정확한 알람 권한이 없어 알림이 최대 10분 늦을 수 있습니다.").assertExists()
        compose.onNodeWithText("권한 설정 열기").assertExists()
    }

    /** 대화상자의 '추가' 버튼(칩의 '추가'와 같은 글자라 마지막 것을 누른다). */
    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.clickLastWithText(text: String) {
        val nodes = onAllNodes(androidx.compose.ui.test.hasText(text))
        nodes[nodes.fetchSemanticsNodes().size - 1].performClick()
    }
}
