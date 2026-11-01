package com.ysdoing.notioncal.core.weather

import android.net.TrafficStats
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.util.Locale
import java.util.concurrent.TimeUnit
import javax.inject.Singleton

/** 일별 예보 한 줄. [date]는 'yyyy-MM-dd'(Asia/Seoul), [code]는 WMO 날씨 코드. 값이 비면 null. */
data class DailyForecast(val date: String, val code: Int?, val tMax: Double?, val tMin: Double?, val pop: Int?)

/** 시간별 예보 한 줄. [time]은 'yyyy-MM-ddTHH:mm'(Asia/Seoul). */
data class HourlyForecast(val time: String, val code: Int?, val temp: Double?, val pop: Int?)

data class Forecast(val days: List<DailyForecast>, val hours: List<HourlyForecast>)

/** WX-01 예보 받기. 네트워크는 동기화 모듈(WeatherWorker)에서만 부른다. 실패하면 [IOException]. */
fun interface WeatherClient {
    suspend fun fetch(lat: Double, lon: Double): Forecast
}

/**
 * Open-Meteo `/v1/forecast` 기본 모델(best_match). 기상청 모델은 2026-03 UM 중단 뒤 데이터 갱신이 멈춰 있어
 * 쓰지 않는다(2026-10-19 사용자 결정). 일별 10일·시간별 48시간, 시간대 Asia/Seoul. 키가 없다.
 * 좌표는 소수 둘째 자리로만 보낸다(WX-02).
 */
class OpenMeteoClient(
    private val baseUrl: HttpUrl = BASE_URL.toHttpUrl(),
    socketTag: Int? = WEATHER_SOCKET_TAG,
) : WeatherClient {
    private val http = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .fastFallback(false) // 별도 스레드 소켓은 태그가 안 붙는다(StrictMode PF-03)
        .apply { socketTag?.let { tag -> addInterceptor(SocketTag(tag)) } }
        .build()

    override suspend fun fetch(lat: Double, lon: Double): Forecast = withContext(Dispatchers.IO) {
        val url = baseUrl.newBuilder()
            .addPathSegments("v1/forecast")
            .addQueryParameter("latitude", "%.2f".format(Locale.ROOT, lat))
            .addQueryParameter("longitude", "%.2f".format(Locale.ROOT, lon))
            .addQueryParameter("daily", "weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max")
            .addQueryParameter("hourly", "weather_code,temperature_2m,precipitation_probability")
            .addQueryParameter("timezone", "Asia/Seoul")
            .addQueryParameter("forecast_days", FORECAST_DAYS.toString())
            .addQueryParameter("forecast_hours", FORECAST_HOURS.toString())
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("날씨 응답 ${response.code}")
            val body = response.body.string()
            val dto = runCatching { json.decodeFromString(ForecastDto.serializer(), body) }.getOrElse { throw IOException("날씨 응답을 읽지 못함", it) }
            dto.toForecast().also { if (it.days.none { d -> d.code != null || d.tMax != null }) throw IOException("날씨 값이 비어 있음") }
        }
    }

    private class SocketTag(private val tag: Int) : Interceptor {
        override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
            val previous = TrafficStats.getThreadStatsTag()
            TrafficStats.setThreadStatsTag(tag)
            return try {
                chain.proceed(chain.request())
            } finally {
                TrafficStats.setThreadStatsTag(previous)
            }
        }
    }

    companion object {
        const val BASE_URL = "https://api.open-meteo.com/"
        const val FORECAST_DAYS = 10
        const val FORECAST_HOURS = 48

        /** 데이터 사용량에서 날씨 트래픽을 구분하는 소켓 태그(노션은 0x4E01). */
        const val WEATHER_SOCKET_TAG = 0x5701

        private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    }
}

@Serializable
internal data class ForecastDto(
    val daily: DailyDto? = null,
    val hourly: HourlyDto? = null,
) {
    fun toForecast(): Forecast {
        val d = daily
        val days = d?.time.orEmpty().mapIndexed { i, date ->
            DailyForecast(date, d!!.code.getOrNull(i), d.tMax.getOrNull(i), d.tMin.getOrNull(i), d.pop.getOrNull(i))
        }
        val h = hourly
        val hours = h?.time.orEmpty().mapIndexed { i, time ->
            HourlyForecast(time, h!!.code.getOrNull(i), h.temp.getOrNull(i), h.pop.getOrNull(i))
        }
        return Forecast(days, hours)
    }
}

@Serializable
internal data class DailyDto(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val code: List<Int?> = emptyList(),
    @SerialName("temperature_2m_max") val tMax: List<Double?> = emptyList(),
    @SerialName("temperature_2m_min") val tMin: List<Double?> = emptyList(),
    @SerialName("precipitation_probability_max") val pop: List<Int?> = emptyList(),
)

@Serializable
internal data class HourlyDto(
    val time: List<String> = emptyList(),
    @SerialName("weather_code") val code: List<Int?> = emptyList(),
    @SerialName("temperature_2m") val temp: List<Double?> = emptyList(),
    @SerialName("precipitation_probability") val pop: List<Int?> = emptyList(),
)

@Module
@InstallIn(SingletonComponent::class)
object WeatherModule {
    @Provides
    @Singleton
    fun provideWeatherClient(): WeatherClient = OpenMeteoClient()
}
