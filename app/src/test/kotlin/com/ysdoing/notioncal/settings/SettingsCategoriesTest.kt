package com.ysdoing.notioncal.settings

import com.ysdoing.notioncal.core.data.SettingsTab
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Phase 11f ST-02·03·05: 날씨는 표시, 디데이는 데이터베이스(2026-11-03 결정). 첫 카테고리는 바로가기 인자 > 토큰 없음이면 연결 >
 * (토큰은 있는데 DB가 없으면 데이터베이스) > 마지막으로 본 카테고리. 문제 배지는 조건이 있는 카테고리에만 붙는다.
 */
class SettingsCategoriesTest {
    private val ok = SettingsFacts(tokenSaved = true, connectionFailed = false, schemaProblem = false, remindersBlocked = false, syncProblem = null)

    @Test
    fun everyCategoryHasItsOwnItemsInTheOrderTheyAreDeclared() {
        SettingsTab.entries.forEach { tab -> assertTrue("$tab 항목이 없다", itemsOf(tab).isNotEmpty()) }
        assertEquals(SettingsItem.entries.toList(), SettingsTab.entries.flatMap(::itemsOf)) // 카테고리 순서로 모아도 전체가 그대로(항목이 어느 한 곳에 몰리지 않는다)
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
    fun headedItemsAreSeparatedByADividerExceptAtTheTopOfAPage() {
        val database = pageEntries(SettingsTab.DATABASE)
        assertEquals(listOf(SettingsItem.DATABASES, SettingsItem.DEFAULT_DATABASE, SettingsItem.DDAY), database.map { it.item })
        assertEquals(listOf(false, false, true), database.map { it.dividerBefore }) // 첫 항목은 구분선이 없고, 머리가 있는 디데이는 앞과 나눈다
        assertFalse(pageEntries(SettingsTab.CONNECTION).single().dividerBefore)
        assertEquals(listOf(false, false, false, false, false, true), pageEntries(SettingsTab.DISPLAY).map { it.dividerBefore }) // 날씨만 머리가 있다
    }

    @Test
    fun theStartTabPrefersTheShortcutThenAMissingTokenThenNoDatabasesThenTheLastOne() {
        assertEquals(SettingsTab.SYNC, settingsStartTab(SettingsTab.SYNC, tokenSaved = false, hasSources = false, last = SettingsTab.ABOUT))
        assertEquals(SettingsTab.CONNECTION, settingsStartTab(null, tokenSaved = false, hasSources = true, last = SettingsTab.SYNC))
        assertEquals(SettingsTab.DATABASE, settingsStartTab(null, tokenSaved = true, hasSources = false, last = SettingsTab.ABOUT)) // 토큰은 있는데 DB가 없다
        assertEquals(SettingsTab.SYNC, settingsStartTab(null, tokenSaved = true, hasSources = true, last = SettingsTab.SYNC))
        assertEquals(SettingsTab.CONNECTION, settingsStartTab(null, tokenSaved = true, hasSources = true, last = null)) // 첫 실행
    }

    @Test
    fun badgesFollowTheirConditions() {
        assertEquals(emptySet<SettingsTab>(), settingsBadges(ok))
        assertEquals(setOf(SettingsTab.CONNECTION), settingsBadges(ok.copy(tokenSaved = false)))
        assertEquals(setOf(SettingsTab.CONNECTION), settingsBadges(ok.copy(connectionFailed = true)))
        assertEquals(setOf(SettingsTab.CONNECTION), settingsBadges(ok.copy(syncProblem = SyncProblem.AUTH))) // 월 화면 인증 배너와 같은 근거
        assertEquals(setOf(SettingsTab.DATABASE), settingsBadges(ok.copy(schemaProblem = true)))
        assertEquals(setOf(SettingsTab.DATABASE), settingsBadges(ok.copy(syncProblem = SyncProblem.NOT_SHARED)))
        assertEquals(setOf(SettingsTab.SYNC), settingsBadges(ok.copy(syncProblem = SyncProblem.OTHER)))
        assertEquals(setOf(SettingsTab.NOTIFICATIONS), settingsBadges(ok.copy(remindersBlocked = true)))
        assertEquals(emptySet<SettingsTab>(), settingsBadges(ok.copy(tokenSaved = null))) // 아직 모르면 붙이지 않는다
        assertEquals(
            setOf(SettingsTab.CONNECTION, SettingsTab.DATABASE, SettingsTab.NOTIFICATIONS),
            settingsBadges(SettingsFacts(tokenSaved = false, connectionFailed = true, schemaProblem = true, remindersBlocked = true, syncProblem = null)),
        )
    }

    @Test
    fun theLatestSyncResultMapsToAProblemKind() {
        assertEquals(null, syncProblemOf(null))
        assertEquals(null, syncProblemOf("OK" to null))
        assertEquals(SyncProblem.AUTH, syncProblemOf("ERROR" to "Unauthorized"))
        assertEquals(SyncProblem.NOT_SHARED, syncProblemOf("ERROR" to "NotShared"))
        assertEquals(SyncProblem.OTHER, syncProblemOf("ERROR" to "Network"))
        assertEquals(SyncProblem.OTHER, syncProblemOf("ERROR" to null))
    }
}
