package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** PRD §12 대조: '노션과 비교'가 동기화 직후 불일치 0을 보이고, 어긋난 경우를 종류별로 찾는다. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotionComparerTest {
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-10-13T03:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val notion = FakeNotionStore { now }

    private fun engine() = NotionSyncEngine(notion, db, clock, UnconfinedTestDispatcher(), WidgetRefresher.None)
    private fun comparer() = NotionComparer(notion, db, clock, UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun seeded() {
        db.dataSourceDao().upsert(
            DataSourceEntity(
                dataSourceId = "ds", databaseId = "db", name = "Calendar", colorHex = "#0B6E99", titlePropId = "title",
                datePropId = "dAtE", donePropId = "dOnE", doneType = "CHECKBOX", doneOption = null, undoneOption = null,
                colorPropId = "cTgR", sortPropId = null,
            ),
        )
        notion.seed("p1", "영어 스터디", "2026-10-13", optionId = "opt-learn")
        notion.seed("p2", "운동", "2026-10-14")
        notion.seed("p3", "출장", "2026-11-20")
        notion.seed("far", "범위 밖", "2027-06-01") // 이번 달 ±2개월 밖
        engine().sync(SyncTrigger.APP_OPEN)
        now = now.plusSeconds(120)
    }

    @Test
    fun matchesRightAfterSync() = runTest {
        seeded()
        val report = comparer().compare()
        assertTrue(report.mismatches.toString(), report.matches)
        assertEquals(3, report.remoteCount)
        assertEquals(3, report.localCount)
        assertEquals(LocalDate.of(2026, 8, 1), report.from)
        assertEquals(LocalDate.of(2026, 12, 31), report.to)
    }

    @Test
    fun findsEachKindOfMismatch() = runTest {
        seeded()
        notion.editRemotely("p1") { title = "영어 회화" } // 아직 동기화 전: 내용 다름
        notion.pages.getValue("p2").inTrash = true // 노션에서 지움: 앱에만
        notion.seed("p4", "새 일정", "2026-10-20", editedAt = now) // 노션에서 만듦: 노션에만
        val report = comparer().compare()
        assertEquals(listOf("title"), report.mismatches.single { it.kind == Mismatch.Kind.DIFFERENT }.fields)
        assertEquals("운동", report.mismatches.single { it.kind == Mismatch.Kind.LOCAL_ONLY }.title)
        assertEquals("새 일정", report.mismatches.single { it.kind == Mismatch.Kind.REMOTE_ONLY }.title)
        assertEquals(3, report.mismatches.size)

        // 다시 동기화하면 맞춰진다(지운 항목은 앱 열기 때 이번 달 대조로 사라진다).
        engine().sync(SyncTrigger.APP_OPEN)
        assertTrue(comparer().compare().matches)
    }

    @Test
    fun ignoresPagesWaitingInOutbox() = runTest {
        seeded()
        EventWriter(db, clock, WidgetRefresher.None, OutboxRequester.None).update("p1", EventChange(title = "폰에서 고침"))
        val report = comparer().compare()
        assertTrue(report.mismatches.toString(), report.matches)
        assertEquals(2, report.remoteCount)
    }
}
