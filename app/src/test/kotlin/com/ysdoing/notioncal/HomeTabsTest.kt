package com.ysdoing.notioncal

import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** 하단 탭: 습관 탭에 다녀와도 달력 탭의 화면 상태(보던 달 등 rememberSaveable)가 그대로다. */
@RunWith(RobolectricTestRunner::class)
class HomeTabsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun calendarStateSurvivesTabSwitch() {
        compose.setContent {
            var tab by rememberSaveable { mutableStateOf(HomeTab.CALENDAR) }
            MaterialTheme {
                HomeTabs(
                    tab = tab,
                    onTabChange = { tab = it },
                    calendar = {
                        var month by rememberSaveable { mutableIntStateOf(10) }
                        Button(onClick = { month++ }) { Text("${month}월") }
                    },
                    habit = { Text("습관 화면") },
                    ledger = { Text("가계부 화면") },
                )
            }
        }
        compose.onNodeWithText("10월").performClick()
        compose.onNodeWithText("11월").assertExists()

        compose.onNodeWithTag("tabHabit").performClick()
        compose.onNodeWithText("습관 화면").assertExists()
        compose.onNodeWithText("11월").assertDoesNotExist()

        compose.onNodeWithTag("tabLedger").performClick()
        compose.onNodeWithText("가계부 화면").assertExists()

        compose.onNodeWithTag("tabCalendar").performClick()
        compose.onNodeWithText("11월").assertExists()
    }

    @Test
    fun backFromHabitOrLedgerReturnsToTheCalendarTabFirst() {
        // Material 하단 탭: 시작 탭이 아니면 뒤로가기는 앱을 닫지 않고 시작 탭(달력)으로 간다.
        compose.setContent {
            var tab by rememberSaveable { mutableStateOf(HomeTab.CALENDAR) }
            MaterialTheme {
                HomeTabs(tab = tab, onTabChange = { tab = it }, calendar = { Text("달력 화면") }, habit = { Text("습관 화면") }, ledger = { Text("가계부 화면") })
            }
        }
        compose.onNodeWithTag("tabLedger").performClick()
        compose.onNodeWithText("가계부 화면").assertExists()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithText("달력 화면").assertExists()
        assertFalse(compose.activity.isFinishing)

        // 달력 탭에서는 뒤로가기를 가로채지 않는다(앱의 원래 동작대로 닫힌다).
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        assertTrue(compose.activity.isFinishing)
    }
}
