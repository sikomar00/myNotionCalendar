package com.ysdoing.notioncal.core.sync

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.db.WeatherDayEntity
import com.ysdoing.notioncal.core.data.db.WeatherHourEntity
import com.ysdoing.notioncal.core.data.weather.WeatherRepository
import com.ysdoing.notioncal.core.weather.WeatherClient
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.io.IOException
import java.time.LocalDate
import java.time.LocalDateTime
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * WX-01 예보 받기. 네트워크는 여기(:core:sync)와 :core:weather에서만 쓴다. 화면·위젯은 [WeatherRepository](Room)만 읽는다.
 * 실패하면 캐시를 지우지 않는다. 로그에 좌표를 남기지 않는다(WX-02).
 */
@Singleton
class WeatherUpdater @Inject constructor(
    private val client: WeatherClient,
    private val weather: WeatherRepository,
    private val settings: SettingsRepository,
) {
    /** 받아서 저장했으면 true. 꺼져 있거나 위치가 없거나 실패하면 false. */
    suspend fun refresh(): Boolean {
        val s = settings.settings.first()
        val place = s.weatherPlace?.rounded() ?: return false
        if (!s.weatherEnabled) return false
        val forecast = try {
            client.fetch(place.lat, place.lon)
        } catch (e: IOException) {
            Log.w(TAG, "예보 받기 실패: ${e.javaClass.simpleName}")
            return false
        }
        val days = forecast.days.mapNotNull { d ->
            runCatching { LocalDate.parse(d.date) }.getOrNull()?.let { WeatherDayEntity(it, d.code, d.tMax, d.tMin, d.pop, fetchedAt = 0) }
        }
        val hours = forecast.hours.mapNotNull { h ->
            runCatching { LocalDateTime.parse(h.time).atZone(APP_ZONE).toInstant().toEpochMilli() }.getOrNull()
                ?.let { WeatherHourEntity(it, h.code, h.temp, h.pop) }
        }
        weather.save(days, hours)
        return true
    }

    private companion object {
        const val TAG = "NotionCalWeather"
    }
}

@HiltWorker
class WeatherWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted params: WorkerParameters,
    private val updater: WeatherUpdater,
) : CoroutineWorker(context, params) {
    // 실패해도 다시 시도를 쌓지 않는다. 다음 주기(3시간)나 앱 열기에서 다시 받는다.
    override suspend fun doWork(): Result {
        updater.refresh()
        return Result.success()
    }
}

/** WX-01 예약: 날씨를 켜고 위치가 있으면 3시간 주기, 앱을 열 때 3시간이 지났으면 한 번. 끄면 취소한다. */
@Singleton
class WeatherScheduler @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: SettingsRepository,
    private val weather: WeatherRepository,
) {
    private val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** 앱 시작 때 한 번. 켜기·끄기·위치 변경을 따라 주기 작업을 맞춘다. */
    fun start(scope: CoroutineScope) {
        scope.launch {
            settings.settings.map { it.weatherEnabled && it.weatherPlace != null }.distinctUntilChanged().collect { on ->
                val work = WorkManager.getInstance(context)
                if (on) {
                    work.enqueueUniquePeriodicWork(
                        PERIODIC,
                        ExistingPeriodicWorkPolicy.KEEP,
                        PeriodicWorkRequestBuilder<WeatherWorker>(3, TimeUnit.HOURS).setConstraints(network).build(),
                    )
                } else {
                    work.cancelUniqueWork(PERIODIC)
                }
            }
        }
    }

    /** 위치를 바꿨거나 켰을 때 바로 받는다. */
    fun requestNow() {
        WorkManager.getInstance(context).enqueueUniqueWork(
            ONCE,
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<WeatherWorker>().setConstraints(network).build(),
        )
    }

    /** 앱을 열 때: 3시간이 지났으면 받는다. */
    suspend fun requestIfStale() {
        if (weather.needsRefresh()) requestNow()
    }

    private companion object {
        const val PERIODIC = "weather-periodic"
        const val ONCE = "weather-now"
    }
}
