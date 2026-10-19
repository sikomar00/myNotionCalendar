package com.ysdoing.notioncal.core.data.weather

import android.content.Context
import com.ysdoing.notioncal.core.data.APP_ZONE
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.WeatherDayEntity
import com.ysdoing.notioncal.core.data.db.WeatherHourEntity
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.roundToInt

/** WX-05 하늘 상태 8종. 예보의 WMO 날씨 코드에서 정한다. [label]은 낭독·설명에 쓴다. */
enum class Sky(val label: String) {
    CLEAR("맑음"),
    PARTLY_CLOUDY("구름 조금"),
    CLOUDY("흐림"),
    RAIN("비"),
    SHOWERS("소나기"),
    SNOW("눈"),
    THUNDER("뇌우"),
    FOG("안개"),
    ;

    companion object {
        /** WMO 4677 코드 → 8종. 이슬비·어는 비는 비, 소낙눈은 눈. 모르는 코드는 null(아이콘 없이 기온만). */
        fun fromWmo(code: Int?): Sky? = when (code) {
            null -> null
            0 -> CLEAR
            1, 2 -> PARTLY_CLOUDY
            3 -> CLOUDY
            45, 48 -> FOG
            in 51..57, in 61..67 -> RAIN
            in 71..77, 85, 86 -> SNOW
            in 80..82 -> SHOWERS
            in 95..99 -> THUNDER
            else -> null
        }
    }
}

/** 하루 예보(앱·위젯 표시용). 기온은 반올림한 정수(°C). */
data class WeatherDay(val date: LocalDate, val sky: Sky?, val tMax: Int?, val tMin: Int?, val pop: Int?) {
    /** '21°/12°' */
    val temps: String get() = listOfNotNull(tMax?.let { "$it°" }, tMin?.let { "$it°" }).joinToString("/")

    /** 낭독: '맑음, 최고 21도 최저 12도'. */
    val description: String
        get() = listOfNotNull(
            sky?.label,
            listOfNotNull(tMax?.let { "최고 ${it}도" }, tMin?.let { "최저 ${it}도" }).joinToString(" ").takeIf { it.isNotEmpty() },
        ).joinToString(", ")

    companion object {
        fun from(e: WeatherDayEntity) = WeatherDay(e.date, Sky.fromWmo(e.code), e.tMax?.roundToInt(), e.tMin?.roundToInt(), e.pop)
    }
}

/** 시간별 예보 한 칸(날짜 시트의 3시간 간격 줄). */
data class WeatherHour(val time: Instant, val hour: Int, val sky: Sky?, val temp: Int?, val pop: Int?)

/** 받아온 시각과 오래됐는지(WX-01: 12시간 넘으면 흐리게). */
data class WeatherStatus(val fetchedAt: Instant?, val stale: Boolean, val placeName: String?)

/**
 * WX-01·03 날씨 캐시. 화면·위젯은 이 저장소(Room)만 읽는다. 날씨를 끄면 아무 날도 내지 않는다.
 * 받아오기는 동기화 모듈(WeatherWorker)이 [save]로 넣는다. 실패하면 부르지 않아 캐시가 남는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class WeatherRepository @Inject constructor(
    private val db: NotionCalDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock,
    private val widgetRefresher: WidgetRefresher,
) {
    private val dao get() = db.weatherDao()
    private val enabled = settings.settings.map { it.weatherEnabled }.distinctUntilChanged()

    fun observeDays(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, WeatherDay>> = enabled.flatMapLatest { on ->
        if (!on) flowOf(emptyMap()) else dao.observeDays(from, to).map { rows -> rows.associate { it.date to WeatherDay.from(it) } }
    }

    /** 그날의 3시간 간격 시간별 예보(0·3·6…시). 받아 둔 48시간 안의 칸만. */
    fun observeHoursOn(date: LocalDate): Flow<List<WeatherHour>> = enabled.flatMapLatest { on ->
        if (!on) {
            flowOf(emptyList())
        } else {
            val start = date.atStartOfDay(APP_ZONE).toInstant()
            dao.observeHours(start.toEpochMilli(), HOURS_PER_DAY).map { rows ->
                rows.mapNotNull { row ->
                    val time = Instant.ofEpochMilli(row.time)
                    val local = time.atZone(APP_ZONE)
                    if (local.toLocalDate() != date || local.hour % STEP_HOURS != 0) null
                    else WeatherHour(time, local.hour, Sky.fromWmo(row.code), row.temp?.roundToInt(), row.pop)
                }
            }
        }
    }

    fun observeStatus(): Flow<WeatherStatus> = combine(dao.observeFetchedAt(), settings.settings) { at, s ->
        val fetched = at?.let(Instant::ofEpochMilli)
        WeatherStatus(fetched, fetched == null || Duration.between(fetched, clock.instant()) > STALE_AFTER, s.weatherPlace?.name)
    }

    /** 앱을 열 때 받을지: 켜져 있고 위치가 있고, 받은 적 없거나 3시간이 지났다(WX-01). */
    suspend fun needsRefresh(): Boolean {
        val s = settings.settings.first()
        if (!s.weatherEnabled || s.weatherPlace == null) return false
        val at = dao.observeFetchedAt().first() ?: return true
        return Duration.between(Instant.ofEpochMilli(at), clock.instant()) >= REFRESH_EVERY
    }

    /** 새 예보로 바꾼다. 어제까지의 날·지난 시간은 지운다. 받아온 시각은 지금으로 맞춘다. */
    suspend fun save(days: List<WeatherDayEntity>, hours: List<WeatherHourEntity>) {
        val now = clock.instant()
        val today = now.atZone(APP_ZONE).toLocalDate()
        val dayStart = today.atStartOfDay(APP_ZONE).toInstant().toEpochMilli()
        dao.replace(days.map { it.copy(fetchedAt = now.toEpochMilli()) }, hours, keepFrom = today, keepFromTime = dayStart)
        widgetRefresher.requestUpdate()
    }

    /** 위치를 바꾸면 이전 지역 예보를 지운다. */
    suspend fun clear() {
        dao.deleteAllDays()
        dao.deleteAllHours()
        widgetRefresher.requestUpdate()
    }

    companion object {
        val STALE_AFTER: Duration = Duration.ofHours(12)
        val REFRESH_EVERY: Duration = Duration.ofHours(3)
        private const val STEP_HOURS = 3
        private const val HOURS_PER_DAY = 24
    }
}

/** WX-02 지역 한 곳(시·군·구 대표 좌표). */
@Serializable
data class Region(
    @kotlinx.serialization.SerialName("n") val name: String,
    @kotlinx.serialization.SerialName("la") val lat: Double,
    @kotlinx.serialization.SerialName("lo") val lon: Double,
)

@Serializable
private data class RegionFile(val source: String = "", val regions: List<Region>)

/**
 * WX-02 지역 목록(시·군·구 250곳, assets/regions.json). 화면에서 지역 검색 API를 부르지 않도록 앱에 넣었다.
 * 출처: KOSTAT 2018 센서스용 행정구역경계(southkorea-maps 정리본, 자유 이용)에서 각 시·군·구의 대표점을 계산, 2026 이름 반영.
 */
@Singleton
class RegionCatalog @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private var cache: List<Region>? = null

    suspend fun all(): List<Region> = mutex.withLock {
        cache ?: withContext(Dispatchers.IO) {
            val text = context.assets.open(ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            Json { ignoreUnknownKeys = true }.decodeFromString(RegionFile.serializer(), text).regions
        }.also { cache = it }
    }

    /** 띄어쓰기를 무시하고 모든 낱말이 들어간 지역. 빈 검색어는 빈 결과. */
    suspend fun search(query: String): List<Region> {
        val words = query.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        return all().filter { region ->
            val name = region.name.replace(" ", "")
            words.all { name.contains(it) }
        }
    }

    private companion object {
        const val ASSET = "regions.json"
    }
}
