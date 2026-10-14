package com.ysdoing.notioncal.feature.diagnostics

import android.app.ApplicationExitInfo
import android.app.usage.UsageStatsManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.ysdoing.notioncal.core.data.AppFailure
import com.ysdoing.notioncal.core.data.SyncDiagnostics
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** S25-03·04·09, PF-10: 진단 화면이 상태를 보여주고, 실패 시에도 도구를 쓸 수 있다. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xxhdpi")
class DiagnosticsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val normal = DiagnosticsUiState(
        sync = SyncDiagnostics(
            lastSuccess = SyncLogEntity(at = 1_791_890_969_623, trigger = "PERIODIC", result = "OK", durationMs = 811, pulled = 6, pushed = 0, error = null),
            lastFailure = SyncLogEntity(at = 1_791_880_000_000, trigger = "APP_OPEN", result = "ERROR", durationMs = 40, pulled = 0, pushed = 0, error = "Network"),
            nextPeriodicAt = 1_791_891_869_623,
            pendingWrites = 2,
            unseenConflicts = 1,
        ),
        power = PowerStatus(ignoringOptimizations = false, standbyBucket = UsageStatsManager.STANDBY_BUCKET_RARE),
        exits = listOf(
            ExitRecord(1_791_870_000_000, ApplicationExitInfo.REASON_OTHER, "MemoryLimiter:AnonSwap"),
            ExitRecord(1_791_860_000_000, ApplicationExitInfo.REASON_USER_REQUESTED, null),
        ),
    )

    private val calls = mutableListOf<String>()

    private fun show(state: DiagnosticsUiState, onBack: (() -> Unit)? = {}, debug: List<String> = emptyList()) {
        compose.setContent {
            MaterialTheme {
                DiagnosticsScreen(
                    state = state,
                    debugLabels = debug,
                    onBack = onBack,
                    onContinue = { calls += "continue" },
                    onOpenBatteryOptimization = { calls += "battery" },
                    onOpenAppSettings = { calls += "app" },
                    onExport = { calls += "export" },
                    onClearCache = { calls += "clear" },
                    onDebug = { calls += "debug$it" },
                    onDismissNotice = {},
                )
            }
        }
    }

    @Test
    fun showsSyncPowerAndExitStatus() {
        show(normal)
        compose.onNodeWithText("2건").assertIsDisplayed()
        compose.onNodeWithText("최적화됨(동기화가 늦어질 수 있음)").assertIsDisplayed()
        compose.onNodeWithText("드물게 사용(동기화 지연)").assertIsDisplayed()
        compose.onNodeWithText("메모리 한도 종료").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun clearCacheAsksFirst() {
        show(normal)
        compose.onNodeWithText("캐시 초기화").performScrollTo().performClick()
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("초기화").performClick()
        assertEquals(listOf("clear"), calls)
    }

    @Test
    fun failureStillOffersTools() {
        // DB를 열지 못해 동기화 정보가 없어도(sync = null) 화면과 도구는 동작한다.
        show(DiagnosticsUiState(failure = AppFailure(AppFailure.REPEATED_STARTUP_FAILURE, safeMode = true)), onBack = null)
        compose.onNodeWithText("이전 실행이 두 번 연속 첫 화면 전에 끝나 안전 모드로 열었습니다.").assertIsDisplayed()
        compose.onNodeWithText("로그 내보내기").performScrollTo().performClick()
        compose.onNodeWithText("확인하고 월 화면으로").performScrollTo().performClick()
        assertEquals(listOf("export", "continue"), calls)
    }

    @Test
    fun debugMenuOnlyWhenProvided() {
        show(normal, debug = listOf("테스트 크래시"))
        compose.onNodeWithText("테스트 크래시").performScrollTo().performClick()
        assertEquals(listOf("debug0"), calls)
    }

    @Test
    fun screenshotNormal() {
        show(normal)
        compose.onRoot().captureRoboImage("src/test/screenshots/diagnostics_normal.png")
    }

    @Test
    fun screenshotSafeMode() {
        show(DiagnosticsUiState(failure = AppFailure(AppFailure.REPEATED_STARTUP_FAILURE, safeMode = true)), onBack = null)
        compose.onRoot().captureRoboImage("src/test/screenshots/diagnostics_safe_mode.png")
    }

    @Test
    fun screenshotBatteryGuide() {
        compose.setContent { MaterialTheme { BatteryGuideContent(onOpenSettings = {}, onDismiss = {}) } }
        compose.onRoot().captureRoboImage("src/test/screenshots/battery_guide.png")
    }
}
