package com.ysdoing.notioncal.core.data.dday

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** DD-01·02 디데이 저장소: 고정·해제·세는 방식, 가까운 순, 지난 디데이 숨김, 동기화 전 고정, 풀린 디데이 안내. */
@RunWith(RobolectricTestRunner::class)
class DdayRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private lateinit var db: NotionCalDatabase
    private val today = LocalDate.of(2026, 10, 19)
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC)
    private var refreshed = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(context, NotionCalDatabase::class.java).allowMainThreadQueries().build()
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "cal", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "d", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                colorPropId = null, sortPropId = null,
            ),
        )
        db.eventDao().upsert(
            listOf(
                event("exam", "기말고사", today.plusDays(12)),
                event("trip", "제주 여행", today.minusDays(1), today.plusDays(1)),
                event("old", "지난 행사", today.minusDays(10)),
                event("soon", "생일", today.plusDays(3)),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun event(id: String, title: String, start: LocalDate, end: LocalDate? = null) = EventEntity(
        pageId = id, dataSourceId = "cal", title = title, startDate = start, endDate = end, startTime = null, endTime = null,
        isAllDay = true, done = false, colorKey = null, sortKey = null, url = "", lastEditedTime = 0,
    )

    private fun TestScope.repository(): Pair<DdayRepository, SettingsRepository> {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val settings = SettingsRepository(store, WidgetRefresher.None)
        return DdayRepository(db, settings, clock, WidgetRefresher { refreshed++ }) to settings
    }

    @Test
    fun pinnedListIsNearestFirstAndHidesOldOnes() = runTest {
        val (repo, _) = repository()
        listOf("exam", "trip", "old", "soon").forEach { repo.pin(it) }
        assertEquals(4, refreshed) // 고정할 때마다 위젯 갱신 요청

        val all = repo.observe(today).first()
        assertEquals(listOf("trip", "soon", "exam", "old"), all.map { it.pageId })
        assertEquals(listOf("D-Day · 2/3일째", "D-3", "D-12", "D+10"), all.map { it.label!!.text })
        assertTrue(all.last().label!!.hidden)

        val visible = repo.observeVisible(today).first()
        assertEquals(listOf("trip", "soon", "exam"), visible.map { it.pageId })
        assertEquals(setOf("exam", "trip", "old", "soon"), repo.observePinnedIds().first())
    }

    @Test
    fun modeAndUnpin() = runTest {
        val (repo, _) = repository()
        repo.pin("old")
        repo.setMode("old", DdayMode.DAYS)
        val old = repo.observe(today).first().single()
        assertEquals("11일째", old.label!!.text)
        assertEquals(false, old.label!!.hidden)

        repo.unpin("old")
        assertTrue(repo.observe(today).first().isEmpty())
        // 두 번 고정해도 한 줄.
        repo.pin("exam")
        repo.pin("exam")
        assertEquals(1, repo.observe(today).first().size)
    }

    /** 동기화 전(일정 행이 아직 없음)이면 제목·표기 없이 남는다(DB 복구 뒤 다시 채워질 때). */
    @Test
    fun pinWithoutEventWaitsForSync() = runTest {
        val (repo, _) = repository()
        repo.pin("missing")
        val row = repo.observe(today).first().single()
        assertNull(row.label)
        assertTrue(repo.observeVisible(today).first().isEmpty())
    }

    @Test
    fun removedNoticeCanBeDismissed() = runTest {
        val (repo, settings) = repository()
        settings.addDdayRemovedNotice(listOf("기말고사"))
        assertEquals(setOf("기말고사"), repo.removedNotice.first())
        repo.dismissRemovedNotice()
        assertTrue(repo.removedNotice.first().isEmpty())
    }
}
