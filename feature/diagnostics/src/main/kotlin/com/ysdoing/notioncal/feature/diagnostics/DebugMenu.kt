package com.ysdoing.notioncal.feature.diagnostics

/** 진단 화면 아래 디버그 메뉴 항목. debug 빌드만 항목을 채우고 release는 빈 목록을 준다(:app 소스 세트). */
data class DebugAction(val label: String, val run: suspend () -> Unit)

fun interface DebugMenu {
    fun actions(): List<DebugAction>
}
