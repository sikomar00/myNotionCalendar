package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** PF-10: 첫 화면 전에 연달아 끝난 실행은 안전 모드로, DB 오류는 진단 화면으로, 고장 주입은 1회만. */
@RunWith(RobolectricTestRunner::class)
class AppHealthTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Before
    fun clean() {
        context.noBackupFilesDir.listFiles()?.forEach { it.delete() }
    }

    private fun health() = AppHealth(context, Dispatchers.Unconfined)

    @Test
    fun normalLaunchesNeverEnterSafeMode() = runTest {
        repeat(5) {
            val h = health()
            assertFalse(h.onLaunchStarted())
            h.onFirstFrame()
            assertNull(h.failure.value)
        }
    }

    @Test
    fun twoUnfinishedLaunchesThenSafeMode() = runTest {
        assertFalse(health().onLaunchStarted()) // 1회차: 첫 화면 전에 죽음
        assertFalse(health().onLaunchStarted()) // 2회차: 또 죽음
        val third = health()
        assertTrue(third.onLaunchStarted())
        assertEquals(AppFailure(AppFailure.REPEATED_STARTUP_FAILURE, safeMode = true), third.failure.value)

        third.clear() // 진단 화면에서 확인 → 다음 실행은 정상
        assertFalse(health().onLaunchStarted())
    }

    @Test
    fun reportKeepsFirstFailureKindOnly() {
        val h = health()
        h.report(IllegalStateException("제목 '병원 예약' 포함 메시지"))
        h.report(RuntimeException("두 번째"))
        assertEquals(AppFailure("IllegalStateException"), h.failure.value)
    }

    @Test
    fun injectedFaultFiresOncePerArm() = runTest {
        health().armStartupFault()
        val next = health() // 다음 실행
        try {
            next.checkFault()
            fail("주입한 예외가 나야 한다")
        } catch (e: IllegalStateException) {
            assertEquals(AppHealth.INJECTED_FAULT, e.message)
        }
        next.checkFault() // 같은 프로세스에서 다시 열면 정상
        health().checkFault() // 그다음 실행도 정상
    }

    @Test
    fun databaseResetRequestIsConsumedOnce() = runTest {
        health().requestDatabaseReset()
        val next = health()
        assertTrue(next.consumeDatabaseReset())
        assertFalse(next.consumeDatabaseReset())
        assertFalse(health().consumeDatabaseReset())
    }
}
