package com.ysdoing.notioncal.settings

import com.ysdoing.notioncal.core.data.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11f ST-02·03·05: 설정 항목은 정확히 한 카테고리에 있고(2026-11-03 결정: 날씨 → 표시, 디데이 → 데이터베이스), 첫 카테고리는
 * 바로가기 인자 > 토큰 없음이면 연결 > 마지막으로 본 카테고리, 문제 배지는 조건이 있는 카테고리에만 붙는다.
 */
class SettingsCategoriesTest {
    @Test
    fun everyCategoryHasItemsAndEachItemBelongsToExactlyOne() {
        SettingsTab.entries.forEach { tab -> assertTrue("$tab 항목이 없다", SettingsItem.entries.any { it.tab == tab }) }
        assertEquals(SettingsItem.entries.size, SettingsItem.entries.map { it.name }.toSet().size)
        assertEquals(SettingsItem.entries.size, SettingsTab.entries.sumOf { tab -> itemsOf(tab).size }) // 누락·중복 0
    }

    @Test
    fun weatherIsUnderDisplayAndDdayUnderDatabases() {
        assertEquals(SettingsTab.DISPLAY, SettingsItem.WEATHER.tab)
        assertEquals(SettingsTab.DATABASE, SettingsItem.DDAY.tab)
        assertEquals(SettingsTab.CONNECTION, SettingsItem.TOKEN.tab)
        assertEquals(SettingsTab.NOTIFICATIONS, SettingsItem.REMINDERS.tab)
        assertEquals(SettingsTab.SYNC, SettingsItem.SYNC_INTERVAL.tab)
        assertEquals(SettingsTab.ABOUT, SettingsItem.DIAGNOSTICS.tab)
    }

    @Test
    fun theStartTabPrefersTheShortcutThenAMissingTokenThenTheLastOne() {
        assertEquals(SettingsTab.DATABASE, settingsStartTab(shortcut = SettingsTab.DATABASE, tokenSaved = false, last = SettingsTab.SYNC))
        assertEquals(SettingsTab.CONNECTION, settingsStartTab(shortcut = null, tokenSaved = false, last = SettingsTab.SYNC))
        assertEquals(SettingsTab.SYNC, settingsStartTab(shortcut = null, tokenSaved = true, last = SettingsTab.SYNC))
        assertEquals(SettingsTab.CONNECTION, settingsStartTab(shortcut = null, tokenSaved = true, last = null)) // 첫 실행
    }

    @Test
    fun badgesFollowTheirConditions() {
        val none = SettingsFacts(tokenSaved = true, connectionFailed = false, schemaProblem = false, remindersBlocked = false)
        assertEquals(emptySet<SettingsTab>(), settingsBadges(none))
        assertEquals(setOf(SettingsTab.CONNECTION), settingsBadges(none.copy(tokenSaved = false)))
        assertEquals(setOf(SettingsTab.CONNECTION), settingsBadges(none.copy(connectionFailed = true)))
        assertEquals(setOf(SettingsTab.DATABASE), settingsBadges(none.copy(schemaProblem = true)))
        assertEquals(setOf(SettingsTab.NOTIFICATIONS), settingsBadges(none.copy(remindersBlocked = true)))
        assertEquals(emptySet<SettingsTab>(), settingsBadges(none.copy(tokenSaved = null))) // 아직 모르면 붙이지 않는다
        assertEquals(setOf(SettingsTab.CONNECTION, SettingsTab.DATABASE, SettingsTab.NOTIFICATIONS), settingsBadges(SettingsFacts(false, true, true, true)))
    }
}
