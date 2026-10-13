package com.ysdoing.notioncal

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ysdoing.notioncal.core.sync.PeriodicSync
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Inject

@HiltAndroidApp
class NotionCalApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var periodicSync: PeriodicSync

    /** 앱 프로세스 동안 사는 범위(설정 구독 등). 메인 스레드를 막지 않게 Default에서 돈다. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // super.onCreate()보다 먼저 켜야 초기화 중 위반도 잡힌다(PF-03).
        StrictModeSetup.enable()
        super.onCreate()
        periodicSync.start(appScope) // NS-06 주기 동기화
    }
}
