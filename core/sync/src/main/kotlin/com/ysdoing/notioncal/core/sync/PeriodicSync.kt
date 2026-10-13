package com.ysdoing.notioncal.core.sync

import com.ysdoing.notioncal.core.data.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

/** NS-06: 설정의 동기화 주기가 바뀔 때마다 주기 작업을 다시 예약한다. 앱 시작 때 한 번 [start]한다. */
@Singleton
class PeriodicSync @Inject constructor(
    private val settings: SettingsRepository,
    private val scheduler: SyncScheduler,
) {
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.settings.map { it.syncIntervalMinutes }.distinctUntilChanged().collect { scheduler.schedulePeriodic(it) }
        }
    }
}
