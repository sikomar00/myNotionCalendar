package com.ysdoing.notioncal.settings

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Create
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.ysdoing.notioncal.R
import com.ysdoing.notioncal.core.data.SettingsTab

/**
 * ST-02 설정 항목 전부와 그 카테고리. 화면은 이 목록을 순서대로 그리고 항목마다 `when`으로 그릴 내용을 정하므로(빠지면 컴파일이 막는다)
 * 모든 항목은 정확히 한 카테고리에 있다. 날씨는 표시, 디데이는 데이터베이스(2026-11-03 결정).
 */
enum class SettingsItem(val tab: SettingsTab) {
    TOKEN(SettingsTab.CONNECTION),
    DATABASES(SettingsTab.DATABASE),
    DEFAULT_DATABASE(SettingsTab.DATABASE),
    DDAY(SettingsTab.DATABASE),
    WEEK_START(SettingsTab.DISPLAY),
    HIDE_DONE(SettingsTab.DISPLAY),
    HOLIDAYS(SettingsTab.DISPLAY),
    WEEK_NUMBERS(SettingsTab.DISPLAY),
    DYNAMIC_COLOR(SettingsTab.DISPLAY),
    WEATHER(SettingsTab.DISPLAY),
    REMINDERS(SettingsTab.NOTIFICATIONS),
    SYNC_INTERVAL(SettingsTab.SYNC),
    DIAGNOSTICS(SettingsTab.ABOUT),
    APP_VERSION(SettingsTab.ABOUT),
    WIDGET_LIST(SettingsTab.WIDGETS),
}

internal fun itemsOf(tab: SettingsTab): List<SettingsItem> = SettingsItem.entries.filter { it.tab == tab }

/** 문제 배지를 정하는 사실들. [tokenSaved]가 null이면 아직 읽는 중이라 배지를 붙이지 않는다. */
internal data class SettingsFacts(
    val tokenSaved: Boolean?,
    /** 마지막 연결 테스트가 실패했다. */
    val connectionFailed: Boolean,
    /** 어느 DB든 매핑한 속성이 사라졌거나 종류가 바뀌었다. */
    val schemaProblem: Boolean,
    /** 알림을 켰는데 권한·시스템 설정이 막고 있다. */
    val remindersBlocked: Boolean,
    /** 가장 최근 동기화가 실패한 이유(월 화면 배너와 같은 근거). 성공이면 null. */
    val syncProblem: SyncProblem?,
)

/** 가장 최근 동기화 실패의 종류. 인증 오류는 연결, 공유 안 됨은 데이터베이스, 그 밖은 동기화 카테고리의 문제다. */
enum class SyncProblem { AUTH, NOT_SHARED, OTHER }

/** 동기화 기록(결과, 오류)에서 문제 종류를 구한다. 기록이 없거나 성공이면 null. */
internal fun syncProblemOf(latest: Pair<String, String?>?): SyncProblem? = when {
    latest == null || latest.first == "OK" -> null
    latest.second == "Unauthorized" -> SyncProblem.AUTH
    latest.second == "NotShared" -> SyncProblem.NOT_SHARED
    else -> SyncProblem.OTHER
}

/** ST-03: 손볼 것이 있는 카테고리. 동기화의 '절전 대상'은 값을 읽는 길이 아직 없어 뺐다. */
internal fun settingsBadges(f: SettingsFacts): Set<SettingsTab> = buildSet {
    if (f.tokenSaved == false || f.connectionFailed || f.syncProblem == SyncProblem.AUTH) add(SettingsTab.CONNECTION)
    if (f.schemaProblem || f.syncProblem == SyncProblem.NOT_SHARED) add(SettingsTab.DATABASE)
    if (f.syncProblem == SyncProblem.OTHER) add(SettingsTab.SYNC)
    if (f.remindersBlocked) add(SettingsTab.NOTIFICATIONS)
}

/** ST-05 첫 카테고리: 바로가기 인자 > 토큰 없음이면 연결 > 토큰은 있는데 DB가 없으면 데이터베이스 > 마지막으로 본 카테고리 > 연결. */
internal fun settingsStartTab(shortcut: SettingsTab?, tokenSaved: Boolean?, hasSources: Boolean, last: SettingsTab?): SettingsTab = when {
    shortcut != null -> shortcut
    tokenSaved == false -> SettingsTab.CONNECTION
    tokenSaved == true && !hasSources -> SettingsTab.DATABASE
    else -> last ?: SettingsTab.CONNECTION
}

/** ST-04 설정 경로. 바로가기 카테고리가 있으면 `?tab=<id>`를 붙인다. */
internal fun settingsRoute(tab: SettingsTab? = null): String = if (tab == null) "settings" else "settings?tab=${tab.id}"

@StringRes
internal fun SettingsTab.label(): Int = when (this) {
    SettingsTab.CONNECTION -> R.string.settings_tab_connection
    SettingsTab.DATABASE -> R.string.settings_tab_database
    SettingsTab.DISPLAY -> R.string.settings_tab_display
    SettingsTab.NOTIFICATIONS -> R.string.settings_tab_notifications
    SettingsTab.SYNC -> R.string.settings_tab_sync
    SettingsTab.ABOUT -> R.string.settings_tab_about
    SettingsTab.WIDGETS -> R.string.settings_tab_widgets
}

private fun SettingsTab.icon(): ImageVector = when (this) {
    SettingsTab.CONNECTION -> Icons.Filled.Lock
    SettingsTab.DATABASE -> Icons.AutoMirrored.Filled.List
    SettingsTab.DISPLAY -> Icons.Filled.Create
    SettingsTab.NOTIFICATIONS -> Icons.Filled.Notifications
    SettingsTab.SYNC -> Icons.Filled.Refresh
    SettingsTab.ABOUT -> Icons.Filled.Info
    SettingsTab.WIDGETS -> Icons.Filled.Home
}

private const val WIDE_MIN_DP = 600

@Composable
private fun TabIcon(tab: SettingsTab, problem: Boolean) {
    BadgedBox(badge = { if (problem) Badge() }) { Icon(tab.icon(), contentDescription = null) }
}

/**
 * ST-01 카테고리 틀. 폭 600dp 미만은 위쪽 스크롤 탭 줄(아이콘 + 글자), 이상은 왼쪽 내비게이션 레일 + 오른쪽 내용이다. 탭은 누르기로만 바꾼다
 * (좌우 밀기는 슬라이더·시스템 뒤로 제스처와 부딪친다). 문제 있는 카테고리는 점과 함께 '문제 있음'으로 읽힌다(ST-03, 색·점만으로 구분하지 않는다).
 */
@Composable
internal fun SettingsCategoryNav(
    selected: SettingsTab,
    badges: Set<SettingsTab>,
    onSelect: (SettingsTab) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val problem = stringResource(R.string.settings_tab_problem)
    fun tabModifier(tab: SettingsTab) = Modifier.testTag("settingsTab_${tab.id}").semantics { if (tab in badges) stateDescription = problem }
    if (LocalConfiguration.current.screenWidthDp >= WIDE_MIN_DP) {
        Row(modifier.fillMaxSize()) {
            NavigationRail(Modifier.testTag("settingsRail")) {
                // 가로로 돌린 폰은 폭이 600dp를 넘어도 높이가 모자라 7개가 다 안 들어가니 레일 안을 밀어서 볼 수 있게 한다.
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SettingsTab.entries.forEach { tab ->
                        NavigationRailItem(
                            selected = tab == selected,
                            onClick = { onSelect(tab) },
                            icon = { TabIcon(tab, tab in badges) },
                            label = { Text(stringResource(tab.label())) },
                            modifier = tabModifier(tab),
                        )
                    }
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    } else {
        Column(modifier.fillMaxSize()) {
            PrimaryScrollableTabRow(selectedTabIndex = selected.ordinal, edgePadding = 8.dp, modifier = Modifier.testTag("settingsTabRow")) {
                SettingsTab.entries.forEach { tab ->
                    Tab(
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        text = { Text(stringResource(tab.label())) },
                        icon = { TabIcon(tab, tab in badges) },
                        modifier = tabModifier(tab),
                    )
                }
            }
            Box(Modifier.weight(1f)) { content() }
        }
    }
}
