package com.ysdoing.notioncal

import android.app.Application
import android.content.ComponentCallbacks
import android.content.res.Configuration as ResConfiguration
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.sync.PeriodicSync
import com.ysdoing.notioncal.crash.CrashReporting
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltAndroidApp
class NotionCalApp : Application(), Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var periodicSync: PeriodicSync

    @Inject
    lateinit var widgetRefresher: WidgetRefresher

    /** 앱 프로세스 동안 사는 범위(설정 구독 등). 메인 스레드를 막지 않게 Default에서 돈다. */
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()

    override fun onCreate() {
        // super.onCreate()보다 먼저 켜야 초기화 중 위반도 잡힌다(PF-03).
        StrictModeSetup.enable()
        super.onCreate()
        // PF-04: 수집 초기화는 디스크를 쓰므로 백그라운드에서 한다(PF-03). 그 사이 몇 ms의 크래시는 놓칠 수 있다.
        // 성능 측정용 빌드(benchmarkRelease·nonMinifiedRelease)는 수집하지 않는다.
        if (BuildConfig.BUILD_TYPE in REPORTING_BUILD_TYPES) appScope.launch(Dispatchers.IO) {
            CrashReporting.start(
                context = this@NotionCalApp,
                dsn = BuildConfig.SENTRY_DSN,
                environment = BuildConfig.BUILD_TYPE,
                release = "${BuildConfig.APPLICATION_ID}@${BuildConfig.VERSION_NAME}+${BuildConfig.VERSION_CODE}",
            )
        }
        periodicSync.start(appScope) // NS-06 주기 동기화
        registerComponentCallbacks(WidgetConfigWatcher(resources.configuration, widgetRefresher))
    }

    private companion object {
        val REPORTING_BUILD_TYPES = setOf("debug", "release")
    }
}

/**
 * S25-06: 위젯 줄 높이는 그릴 때의 기기 글꼴 크기·화면 밀도로 계산한다(WidgetMetrics). 설정에서 이 값을 바꾸면
 * 런처는 글자만 새 배율로 다시 그려 예전 줄 높이에 잘리므로, 바뀌는 즉시 위젯을 다시 그린다.
 * 프로세스가 없을 때 바뀐 경우는 다음 위젯 갱신(동기화·자정·조작) 때 맞춰진다.
 */
private class WidgetConfigWatcher(
    initial: ResConfiguration,
    private val widgetRefresher: WidgetRefresher,
) : ComponentCallbacks {
    private var fontScale = initial.fontScale
    private var densityDpi = initial.densityDpi

    override fun onConfigurationChanged(newConfig: ResConfiguration) {
        if (newConfig.fontScale != fontScale || newConfig.densityDpi != densityDpi) {
            fontScale = newConfig.fontScale
            densityDpi = newConfig.densityDpi
            widgetRefresher.requestUpdate()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() = Unit
}
