package com.ysdoing.notioncal.core.data.weather

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.WeatherDayEntity
import com.ysdoing.notioncal.core.data.db.WeatherHourEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** WX-01·03·05 날씨: 하늘 8종 매핑, 표기·낭독, 켜기 전에는 비움, 12시간 넘으면 흐리게, 시간별 3시간 간격, 지역 목록. */
@RunWith(RobolectricTestRunner::class)
class WeatherTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-10-19T03:00:00Z") // 서울 12:00
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
    }
    private val today = LocalDate.of(2026, 10, 19)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(context, NotionCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.repository(): Pair<WeatherRepository, SettingsRepository> {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val settings = SettingsRepository(store, WidgetRefresher.None)
        return WeatherRepository(db, settings, clock, WidgetRefresher.None) to settings
    }

    @Test
    fun wmoCodesMapToEightSkies() {
        assertEquals(Sky.CLEAR, Sky.fromWmo(0))
        assertEquals(Sky.PARTLY_CLOUDY, Sky.fromWmo(2))
        assertEquals(Sky.CLOUDY, Sky.fromWmo(3))
        assertEquals(Sky.FOG, Sky.fromWmo(45))
        assertEquals(Sky.RAIN, Sky.fromWmo(53))
        assertEquals(Sky.RAIN, Sky.fromWmo(61))
        assertEquals(Sky.RAIN, Sky.fromWmo(67))
        assertEquals(Sky.SNOW, Sky.fromWmo(73))
        assertEquals(Sky.SHOWERS, Sky.fromWmo(81))
        assertEquals(Sky.SNOW, Sky.fromWmo(86))
        assertEquals(Sky.THUNDER, Sky.fromWmo(95))
        assertNull(Sky.fromWmo(null))
        assertNull(Sky.fromWmo(42))
        assertEquals(8, Sky.entries.size)
    }

    /** WX-03: '☀ 21°/12°'의 글자 부분과 낭독 '맑음, 최고 21도 최저 12도'. 반올림, 비면 빼고 읽는다. */
    @Test
    fun formatAndDescription() {
        val day = WeatherDay(today, Sky.CLEAR, 21, 12, 10)
        assertEquals("21°/12°", day.temps)
        assertEquals("맑음, 최고 21도 최저 12도", day.description)
        assertEquals("흐림, 최고 9도", WeatherDay(today, Sky.CLOUDY, 9, null, null).description)
        assertEquals(22, WeatherDay.from(WeatherDayEntity(today, 0, 21.5, -0.4, 10, 0)).tMax)
        assertEquals(0, WeatherDay.from(WeatherDayEntity(today, 0, 21.5, -0.4, 10, 0)).tMin)
    }

    @Test
    fun hiddenUntilEnabledThenStaleAfterTwelveHours() = runTest {
        val (repo, settings) = repository()
        repo.save(
            listOf(WeatherDayEntity(today, 0, 21.0, 12.0, 10, 0), WeatherDayEntity(today.plusDays(1), 61, 19.0, 11.0, 70, 0)),
            listOf(WeatherHourEntity(now.toEpochMilli(), 0, 20.0, 0)),
        )
        assertTrue(repo.observeDays(today, today.plusDays(9)).first().isEmpty()) // 날씨를 켜기 전에는 보이지 않는다

        settings.setWeatherEnabled(true)
        val days = repo.observeDays(today, today.plusDays(9)).first()
        assertEquals(Sky.RAIN, days.getValue(today.plusDays(1)).sky)
        assertFalse(repo.observeStatus().first().stale)

        now = now.plus(Duration.ofHours(13))
        assertTrue(repo.observeStatus().first().stale)
        assertEquals(now.minus(Duration.ofHours(13)), repo.observeStatus().first().fetchedAt)
        assertFalse(repo.needsRefresh()) // 위치를 고르기 전에는 받지 않는다
        settings.setWeatherPlace(WeatherPlace("서울 종로구", 37.594, 126.978))
        assertTrue(repo.needsRefresh())
        assertEquals(WeatherPlace("서울 종로구", 37.59, 126.98), settings.settings.first().weatherPlace) // 둘째 자리로 저장
    }

    /** 날짜 시트 위쪽 시간별 줄: 그날의 3시간 간격(0·3·6…시). */
    @Test
    fun threeHourlyForDay() = runTest {
        val (repo, settings) = repository()
        settings.setWeatherEnabled(true)
        val start = today.atStartOfDay(com.ysdoing.notioncal.core.data.APP_ZONE).toInstant()
        repo.save(
            listOf(WeatherDayEntity(today, 0, 21.0, 12.0, 10, 0)),
            (0 until 48).map { h -> WeatherHourEntity(start.plus(Duration.ofHours(h.toLong())).toEpochMilli(), 0, h.toDouble(), 0) },
        )
        val hours = repo.observeHoursOn(today).first()
        assertEquals(listOf(0, 3, 6, 9, 12, 15, 18, 21), hours.map { it.hour })
        assertEquals(15, hours[5].temp)
    }

    @Test
    fun regionCatalogHasDistrictsAndSearches() = runTest {
        val catalog = RegionCatalog(context)
        val all = catalog.all()
        assertEquals(250, all.size)
        assertTrue(all.all { it.lat in 33.0..39.0 && it.lon in 124.0..132.0 })
        assertEquals(listOf("서울 강남구"), catalog.search("강남").map { it.name })
        assertTrue(catalog.search("수원 장안").single().name == "경기 수원시 장안구")
        assertTrue(catalog.search("").isEmpty())
    }
}
