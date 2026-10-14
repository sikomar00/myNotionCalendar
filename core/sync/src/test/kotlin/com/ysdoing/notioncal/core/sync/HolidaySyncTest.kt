package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.HolidayRepository
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** EX-02: 캘린더 권한이 없으면 예외 없이 아무것도 하지 않는다(노션 일정과 무관). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HolidaySyncTest {
    @get:Rule
    val tmp = TemporaryFolder()

    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val db = Room.inMemoryDatabaseBuilder(context, NotionCalDatabase::class.java).allowMainThreadQueries().build()

    @After
    fun tearDown() = db.close()

    @Test
    fun withoutPermissionDoesNothing() = runTest {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "s.preferences_pb") }
        val settings = SettingsRepository(store, WidgetRefresher {})
        settings.setShowHolidays(true)
        val repo = HolidayRepository(db.holidayDao(), settings, WidgetRefresher {})
        val clock = Clock.fixed(Instant.parse("2026-10-14T03:00:00Z"), ZoneOffset.UTC)
        val sync = HolidaySync(context, repo, settings, clock, UnconfinedTestDispatcher(testScheduler))

        assertNull(sync.refresh())
        assertEquals(emptyMap<LocalDate, String>(), repo.observe(LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31)).first())
    }
}
