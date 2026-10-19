package com.ysdoing.notioncal

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource

/** 첫 화면의 하단 탭(사용자 결정 2026-10-19): 📅 달력 · ✅ 습관 · 💰 가계부. */
enum class HomeTab { CALENDAR, HABIT, LEDGER }

/**
 * 하단 탭으로 달력·습관·가계부를 오간다. 탭마다 저장 상태를 따로 두어(SaveableStateHolder) 습관 탭에 다녀와도 월 화면의
 * 보던 달·보기가 그대로다(ViewModel은 같은 화면 항목에 남는다). 아래 시스템 막대 인셋은 탭 막대가 맡고, 안쪽 화면의
 * Scaffold는 남은 인셋만 쓴다(consumeWindowInsets).
 */
@Composable
fun HomeTabs(
    tab: HomeTab,
    onTabChange: (HomeTab) -> Unit,
    calendar: @Composable () -> Unit,
    habit: @Composable () -> Unit,
    ledger: @Composable () -> Unit,
) {
    val holder = rememberSaveableStateHolder()
    Scaffold(
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == HomeTab.CALENDAR,
                    onClick = { onTabChange(HomeTab.CALENDAR) },
                    icon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_calendar)) },
                    modifier = Modifier.testTag("tabCalendar"),
                )
                NavigationBarItem(
                    selected = tab == HomeTab.HABIT,
                    onClick = { onTabChange(HomeTab.HABIT) },
                    icon = { Icon(Icons.Filled.CheckCircle, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_habit)) },
                    modifier = Modifier.testTag("tabHabit"),
                )
                NavigationBarItem(
                    selected = tab == HomeTab.LEDGER,
                    onClick = { onTabChange(HomeTab.LEDGER) },
                    icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null) },
                    label = { Text(stringResource(R.string.tab_ledger)) },
                    modifier = Modifier.testTag("tabLedger"),
                )
            }
        },
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { padding ->
        Box(Modifier.padding(padding).consumeWindowInsets(padding)) {
            holder.SaveableStateProvider(tab.name) {
                when (tab) {
                    HomeTab.CALENDAR -> calendar()
                    HomeTab.HABIT -> habit()
                    HomeTab.LEDGER -> ledger()
                }
            }
        }
    }
}
