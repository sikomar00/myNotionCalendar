package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.ConflictEntity
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.OutboxEntity
import com.ysdoing.notioncal.core.data.db.SyncLogEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** S25-04 진단 집계, PF-10 캐시 초기화 범위, SEC-02 내보내기 마스킹. */
@RunWith(RobolectricTestRunner::class)
class DiagnosticsRepositoryTest {
    private lateinit var db: NotionCalDatabase
    private lateinit var repo: DiagnosticsRepository
    private val requested = mutableListOf<SyncTrigger>()
    private var widgetRequests = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        val requester = object : SyncRequester {
            override fun request(trigger: SyncTrigger, dataSourceId: String?) {
                requested += trigger
            }
            override val isSyncing = MutableStateFlow(false)
        }
        val schedule = object : SyncScheduleInfo {
            override val nextPeriodicAt = flowOf<Long?>(5_000L)
        }
        repo = DiagnosticsRepository(db, schedule, requester, { widgetRequests++ }, Dispatchers.Unconfined)
    }

    @After
    fun tearDown() = db.close()

    private fun event(id: String, title: String, pending: Int = 0) = EventEntity(
        pageId = id, dataSourceId = "ds", title = title, startDate = LocalDate.of(2026, 10, 13), endDate = null,
        startTime = null, endTime = null, isAllDay = true, done = false, colorKey = null, sortKey = null,
        url = "https://www.notion.so/$id", lastEditedTime = 1000, pendingOps = pending,
    )

    private suspend fun seed() {
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "d", donePropId = "done", doneType = "CHECKBOX", doneOption = null, undoneOption = null,
                colorPropId = null, sortPropId = null, lastSyncAt = 100, lastFullSyncAt = 100,
            ),
        )
        db.eventDao().upsert(listOf(event("p1", "병원 예약"), event("local-1", "치과 검진", pending = 1)))
        db.outboxDao().insert(
            OutboxEntity(pageId = "local-1", dataSourceId = "ds", op = "CREATE", changes = "{}", baseValues = null,
                baseLastEdited = null, createdAt = 2_000, attempts = 2, lastError = "Network"),
        )
        db.syncLogDao().insert(SyncLogEntity(at = 1_000, trigger = "PERIODIC", result = "OK", durationMs = 800, pulled = 3, pushed = 0, error = null))
        db.syncLogDao().insert(SyncLogEntity(at = 3_000, trigger = "APP_OPEN", result = "ERROR", durationMs = 50, pulled = 0, pushed = 0, error = "Unauthorized"))
        db.conflictDao().insert(
            ConflictEntity(pageId = "p1", title = "병원 예약", field = "TITLE", localValue = "병원 예약 변경", remoteValue = "병원 예약",
                kind = "CONFLICT", at = 2_500),
        )
    }

    @Test
    fun aggregatesLastSuccessFailureAndCounts() = runTest {
        seed()
        val d = repo.sync.first()
        assertEquals(1_000L, d.lastSuccess!!.at)
        assertEquals("Unauthorized", d.lastFailure!!.error)
        assertEquals(5_000L, d.nextPeriodicAt)
        assertEquals(1, d.pendingWrites)
        assertEquals(1, d.unseenConflicts)
    }

    @Test
    fun clearCacheKeepsPendingWritesAndRegistrations() = runTest {
        seed()
        repo.clearCache()

        assertNull(db.eventDao().get("p1"))
        assertEquals("치과 검진", db.eventDao().get("local-1")!!.title) // 아직 보내지 않은 일정은 남는다
        assertEquals(1, db.outboxDao().all().size)
        val source = db.dataSourceDao().get("ds")!!
        assertNull(source.lastSyncAt)
        assertNull(source.lastFullSyncAt)
        assertNull(repo.sync.first().lastSuccess)
        assertEquals(0, repo.sync.first().unseenConflicts)
        assertEquals(listOf(SyncTrigger.CACHE_RESET), requested)
        assertEquals(1, widgetRequests)
    }

    @Test
    fun reportMasksTitlesAndTokens() = runTest {
        seed()
        val fakeToken = "ntn_" + "Q1w2E3r4T5y6U7i8"
        val text = repo.report(
            header = listOf("버전 0.1.0"),
            sections = listOf(ReportSection("절전", listOf("상태 OK $fakeToken"))),
        )
        assertTrue(text.contains("PERIODIC OK"))
        assertTrue(text.contains("오류 Unauthorized"))
        assertTrue(text.contains("CREATE 1건"))
        assertTrue(text.contains(Redactor.maskTitle("병원 예약")))
        assertFalse(text.contains("병원 예약"))
        assertFalse(text.contains("치과 검진"))
        assertFalse(text.contains(fakeToken))
    }
}
