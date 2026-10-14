package com.ysdoing.notioncal.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.Direction
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PF-01: 시작 → 월 그리드 → 월 넘기기 → 날짜 시트 경로의 Baseline Profile을 만든다.
 * 실행: `gradlew :app:generateBaselineProfile` (S25 연결 상태). 결과는 app/src/release/generated/baselineProfiles에 저장된다.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule
    val rule = BaselineProfileRule()

    @Test
    fun generate() = rule.collect(packageName = TARGET_PACKAGE, includeInStartupProfile = true) {
        pressHome()
        startActivityAndWait()
        waitForMonthGrid()
        swipeMonths(2, Direction.LEFT)
        swipeMonths(2, Direction.RIGHT)
        openAndCloseDaySheet()
    }
}
