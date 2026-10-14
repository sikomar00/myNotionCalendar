package com.ysdoing.notioncal.feature.diagnostics

/**
 * 진단 화면 아래 디버그 메뉴 항목. debug 빌드만 항목을 채우고 release는 빈 목록을 준다(:app 소스 세트).
 * [run]이 문구를 돌려주면 화면이 결과 대화상자로 보인다(null이면 '실행했습니다' 알림만).
 */
data class DebugAction(val label: String, val run: suspend () -> String?)

/** 디버그 항목의 결과 문구(대화상자). */
data class DebugResult(val label: String, val text: String)

fun interface DebugMenu {
    fun actions(): List<DebugAction>
}
