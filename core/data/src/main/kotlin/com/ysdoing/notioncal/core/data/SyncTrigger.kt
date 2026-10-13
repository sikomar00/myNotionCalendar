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

/** NS-04: 아웃박스에 쌓인 쓰기를 노션으로 보내 달라는 요청. 구현은 :core:sync(OutboxWorker). */
fun interface OutboxRequester {
    fun request()

    companion object {
        val None = OutboxRequester { }
    }
}

/** PRD §4 기준 시간대. 날짜 계산·표시는 이 값으로 한다. */
val APP_ZONE: java.time.ZoneId = java.time.ZoneId.of("Asia/Seoul")

/** 위젯 → 앱·퀵 시트 진입 약속. :widget은 :feature에 의존할 수 없어 문자열 action으로 연다. */
object AppIntents {
    /** yyyy-MM-dd */
    const val EXTRA_DATE = "com.ysdoing.notioncal.extra.DATE"

    /** 퀵 시트(WI-01·02·05). EXTRA_MODE = day|add|edit */
    const val ACTION_QUICK_SHEET = "com.ysdoing.notioncal.action.QUICK_SHEET"
    const val EXTRA_MODE = "com.ysdoing.notioncal.extra.MODE"
    const val EXTRA_PAGE_ID = "com.ysdoing.notioncal.extra.PAGE_ID"
    const val MODE_DAY = "day"
    const val MODE_ADD = "add"
    const val MODE_EDIT = "edit"
}
