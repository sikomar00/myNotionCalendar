package com.ysdoing.notioncal.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * S25-05·§3: 120Hz에서 월 스와이프 프레임. 목표는 느린 프레임 5% 미만.
 * frameOverrunMs가 0보다 큰 프레임을 느린 프레임으로 센다(앱 안 JankStats 로그와 함께 docs/metrics.md에 기록).
 */
@RunWith(AndroidJUnit4::class)
class MonthSwipeBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun swipeMonths() = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(BaselineProfileMode.Require),
        iterations = 5,
        startupMode = StartupMode.WARM,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            waitForMonthGrid()
        },
    ) {
        swipeMonths(3, Direction.LEFT)
        swipeMonths(3, Direction.RIGHT)
    }
}
