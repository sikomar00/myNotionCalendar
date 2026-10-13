package com.ysdoing.notioncal.core.data

/** NS-06 동기화 시점. sync_log.trigger 값으로 기록한다. */
enum class SyncTrigger {
    APP_OPEN,
    PULL_TO_REFRESH,
    WIDGET_REFRESH,
    PERIODIC,
    NETWORK_RESTORED,
}

/**
 * 화면이 동기화를 요청하는 통로. 구현(:core:sync의 SyncScheduler)만 노션 API에 닿는다.
 * 화면 모듈은 :core:data에만 의존해 API를 직접 부를 수 없다(PRD §7).
 */
interface SyncRequester {
    fun request(trigger: SyncTrigger, dataSourceId: String? = null)

    /** 동기화 작업이 실행 중인지(새로고침 표시용). */
    val isSyncing: kotlinx.coroutines.flow.Flow<Boolean>
}

/** PRD §4 기준 시간대. 날짜 계산·표시는 이 값으로 한다. */
val APP_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")
