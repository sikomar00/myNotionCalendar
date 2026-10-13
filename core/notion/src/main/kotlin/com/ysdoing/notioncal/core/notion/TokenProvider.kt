package com.ysdoing.notioncal.core.notion

/**
 * 노션 내부 연결 토큰 공급자. 구현은 :core:sync 에서 암호화 저장소(SEC-01)와 연결한다.
 * OkHttp 스레드에서 호출되므로 블로킹 구현이어도 된다(메인 스레드에서는 부르지 않는다).
 */
fun interface TokenProvider {
    fun token(): String?
}
