package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.weather.Sky
import com.ysdoing.notioncal.core.data.weather.WeatherPlace
import com.ysdoing.notioncal.core.data.weather.WeatherRepository
import com.ysdoing.notioncal.core.weather.DailyForecast
import com.ysdoing.notioncal.core.weather.Forecast
import com.ysdoing.notioncal.core.weather.HourlyForecast
import com.ysdoing.notioncal.core.weather.WeatherClient
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** WX-01 받기: 고른 위치로 받아 Room에 넣고, 실패하면 캐시를 남기며(비행기 모드), 꺼져 있거나 위치가 없으면 부르지 않는다. */
@RunWith(RobolectricTestRunner::class)
class WeatherSyncTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var db: NotionCalDatabase
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC)
    private val today = LocalDate.of(2026, 10, 19)
    private val calls = mutableListOf<Pair<Double, Double>>()
    private var offline = false

    private val client = WeatherClient { lat, lon ->
        calls += lat to lon
        if (offline) throw IOException("연결 없음")
        Forecast(
            listOf(DailyForecast("2026-10-19", 3, 17.3, 9.1, 4), DailyForecast("2026-10-20", 61, 19.7, 10.7, 20)),
            listOf(HourlyForecast("2026-10-19T12:00", 3, 16.0, 0), HourlyForecast("2026-10-19T15:00", 2, 17.0, 5)),
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun TestScope.setup(): Triple<WeatherUpdater, WeatherRepository, SettingsRepository> {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val settings = SettingsRepository(store, WidgetRefresher.None)
        val repo = WeatherRepository(db, settings, clock, WidgetRefresher.None)
        return Triple(WeatherUpdater(client, repo, settings), repo, settings)
    }

    @Test
    fun fetchesForChosenPlaceAndKeepsCacheWhenOffline() = runTest {
        val (updater, repo, settings) = setup()
        assertFalse(updater.refresh()) // 꺼져 있으면 부르지 않는다
        settings.setWeatherEnabled(true)
        assertFalse(updater.refresh()) // 위치가 없으면 부르지 않는다
        assertTrue(calls.isEmpty())

        settings.setWeatherPlace(WeatherPlace("서울 종로구", 37.594, 126.978))
        assertTrue(updater.refresh())
        assertEquals(listOf(37.59 to 126.98), calls)
        val days = repo.observeDays(today, today.plusDays(9)).first()
        assertEquals(Sky.RAIN, days.getValue(today.plusDays(1)).sky)
        assertEquals("17°/9°", days.getValue(today).temps)
        assertEquals(listOf(12, 15), repo.observeHoursOn(today).first().map { it.hour })

        offline = true
        assertFalse(updater.refresh())
        assertEquals(2, repo.observeDays(today, today.plusDays(9)).first().size) // 실패해도 캐시를 지우지 않는다
        assertEquals(clock.instant(), repo.observeStatus().first().fetchedAt)
    }
}
