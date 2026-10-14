package com.ysdoing.notioncal.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PF-01·§3: 콜드 스타트 → 월 화면. 목표는 10회 중앙값 1.0초 이하.
 * - timeToInitialDisplay: 첫 프레임.
 * - timeToFullDisplay: 이번 달 일정을 그린 시점(MonthScreen의 ReportDrawnWhen). 목표는 이 값으로 판단한다.
 * 실행: `gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest`
 */
@RunWith(AndroidJUnit4::class)
class StartupBenchmark {
    @get:Rule
    val rule = MacrobenchmarkRule()

    @Test
    fun startupWithoutProfile() = startup(CompilationMode.None())

    @Test
    fun startupWithBaselineProfile() = startup(CompilationMode.Partial(BaselineProfileMode.Require))

    private fun startup(mode: CompilationMode) = rule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = mode,
        iterations = 10,
        startupMode = StartupMode.COLD,
    ) {
        pressHome()
        startActivityAndWait()
        waitForMonthGrid()
    }
}
