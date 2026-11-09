package com.ysdoing.notioncal.core.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 사용자가 정한 기본값(2026-11-02): 주기 동기화 30분, 주 번호 표시 끔. */
class AppSettingsDefaultsTest {
    @Test
    fun syncIntervalDefaultsToThirtyMinutes() {
        assertEquals(30, AppSettings().syncIntervalMinutes)
        assertTrue(AppSettings.DEFAULT_SYNC_INTERVAL in AppSettings.SYNC_INTERVALS)
    }

    @Test
    fun weekNumbersAreOffByDefault() {
        assertFalse(AppSettings().showWeekNumbers)
    }
}
