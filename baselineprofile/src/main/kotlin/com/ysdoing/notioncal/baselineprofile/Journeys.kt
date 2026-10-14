package com.ysdoing.notioncal.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until

/** 측정 대상 앱. benchmarkRelease 빌드는 release와 같은 ID를 쓴다(사용자가 토큰·DB를 직접 등록해 둔 상태). */
const val TARGET_PACKAGE = "com.ysdoing.notioncal"

private const val WAIT_MS = 10_000L

/** 월 그리드의 날짜 칸이 보일 때까지 기다린다(설명: "10월 14일, 일정 3개"). */
fun MacrobenchmarkScope.waitForMonthGrid() {
    check(device.wait(Until.hasObject(By.descContains("일, 일정")), WAIT_MS)) { "월 그리드가 뜨지 않았습니다" }
}

/** 월을 [times]번 넘긴다. 왼쪽으로 밀면 다음 달. */
fun MacrobenchmarkScope.swipeMonths(times: Int, direction: Direction) {
    repeat(times) {
        val grid = device.findObject(By.scrollable(true)) ?: device.findObject(By.descContains("일, 일정"))
        grid?.let {
            it.setGestureMargin(device.displayWidth / 10)
            it.swipe(direction, 0.8f, 3_000)
        }
        device.waitForIdle()
    }
}

/** 오늘 칸을 눌러 날짜 시트를 열고 닫는다(MV-04 경로). */
fun MacrobenchmarkScope.openAndCloseDaySheet() {
    device.findObject(By.descContains("일, 일정"))?.click() ?: return
    device.wait(Until.hasObject(By.text("추가").pkg(TARGET_PACKAGE)), 3_000)
    device.pressBack()
    device.waitForIdle()
}
