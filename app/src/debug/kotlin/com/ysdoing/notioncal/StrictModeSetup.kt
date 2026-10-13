package com.ysdoing.notioncal

import android.os.StrictMode
import android.os.strictmode.Violation
import android.util.Log
import java.util.concurrent.Executor

/**
 * 디버그 빌드 전용. 메인 스레드 디스크·네트워크 접근과 누수를 logcat(NotionCalStrictMode 태그)에 남긴다(PF-03).
 * One UI가 앱 프로세스 안에서 일으키는 위반(앱 코드와 무관)은 걸러서, 남은 로그 0건을 기준으로 삼는다.
 */
object StrictModeSetup {
    private const val TAG = "NotionCalStrictMode"

    /** S25 One UI 9.0에서 관측된 프레임워크 내부 위반. 스택에 이 프레임이 있으면 무시한다. */
    private val oemFrames = listOf(
        "android.graphics.Typeface.setFlipFonts", // 삼성 글꼴 스타일 적용(Application.onCreate 기본 구현)
        "android.app.IdsController.", // 삼성 화면 재개 시 SharedPreferences 접근
        "com.samsung.android.rune.InputRune.", // 삼성 키 입력 처리 초기화 시 파일 확인
    )

    private val direct = Executor { it.run() }

    fun enable() {
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyListener(direct, ::report)
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyListener(direct, ::report)
                .build(),
        )
    }

    private fun report(violation: Violation) {
        val fromOem = violation.stackTrace.any { frame ->
            val name = "${frame.className}.${frame.methodName}"
            oemFrames.any { name.startsWith(it) }
        }
        if (fromOem) {
            // 필터가 동작하는지 확인할 수 있게 한 줄만 남긴다(경고 집계 대상 아님).
            Log.d(TAG, "OEM 위반 무시: ${violation.javaClass.simpleName}")
        } else {
            Log.w(TAG, "StrictMode 위반", violation)
        }
    }
}
