package com.ysdoing.notioncal.core.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** Phase 11f ST-01·04·05 설정 카테고리 id와 마지막으로 본 카테고리 저장. */
class SettingsTabTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun idsRoundTripAndAnUnknownIdIsNull() {
        SettingsTab.entries.forEach { assertEquals(it, SettingsTab.fromId(it.id)) }
        assertNull(SettingsTab.fromId("nope"))
        assertNull(SettingsTab.fromId(null))
        assertEquals(7, SettingsTab.entries.size) // 연결·데이터베이스·표시·알림·동기화·정보·위젯
        assertEquals(SettingsTab.entries.size, SettingsTab.entries.map { it.id }.toSet().size)
    }

    @Test
    fun theLastSettingsTabIsRememberedAndStartsEmpty() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val repo = SettingsRepository(store, WidgetRefresher {})
        assertNull(repo.settings.first().lastSettingsTab)
        repo.setLastSettingsTab(SettingsTab.NOTIFICATIONS)
        assertEquals(SettingsTab.NOTIFICATIONS, repo.settings.first().lastSettingsTab)
    }

    /** 위젯 이동값을 되돌린 날: 처음은 비어 있고(그래서 첫 방송에서도 되돌린다), 적은 날을 그대로 읽는다. */
    @Test
    fun widgetOffsetsDayStartsEmptyAndRoundTrips() = runTest {
        val scope = TestScope(UnconfinedTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File(tmp.root, "day.preferences_pb") }
        val repo = SettingsRepository(store, WidgetRefresher {})
        assertNull(repo.widgetOffsetsDay())
        val day = java.time.LocalDate.of(2026, 11, 8)
        repo.setWidgetOffsetsDay(day)
        assertEquals(day, repo.widgetOffsetsDay())
    }
}
