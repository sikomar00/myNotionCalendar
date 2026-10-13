package com.ysdoing.notioncal.core.data

/**
 * WG-05: 위젯에 보이는 데이터가 바뀐 지점마다 부른다(PRD §7-3). 구현(:widget)이 짧은 시간의 요청을 묶어
 * 모든 위젯을 한 번 갱신한다. 동기화·저장소 코드는 위젯 모듈을 몰라도 된다.
 */
fun interface WidgetRefresher {
    fun requestUpdate()

    companion object {
        /** 테스트·위젯이 없는 구성용. */
        val None = WidgetRefresher { }
    }
}
