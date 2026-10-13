package com.ysdoing.notioncal.core.sync

/** NS-06 동기화 시점. sync_log.trigger 값으로 기록한다. */
enum class SyncTrigger {
    APP_OPEN,
    PULL_TO_REFRESH,
    WIDGET_REFRESH,
    PERIODIC,
    NETWORK_RESTORED,
}
