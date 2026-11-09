package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.notion.NotionError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 종합 진단 1단계(2026-11-08): 일정 쓰기 큐가 한 작업 때문에 영원히 멈추지 않되, 사용자가 고친 내용은 버리지 않는다.
 * 공유가 끊긴 DB는 그 DB만 건너뛰고, 읽지 못하는 작업은 정리하고 다음을 보내며, 일시 오류(오프라인·노션 장애)는 몇 번이든 다시 보낸다.
 * 오류 이름은 릴리스(R8)에서도 같은 글자로 남는다.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutboxResilienceTest {
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-10-13T03:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val notion = FakeNotionStore { now }
    private lateinit var writer: EventWriter

    private fun processor() = OutboxProcessor(notion, notion, db, clock, WidgetRefresher.None, UnconfinedTestDispatcher())

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = EventWriter(db, clock, WidgetRefresher.None, OutboxRequester.None)
    }

    @After
    fun tearDown() = db.close()

    private fun source(id: String) = DataSourceEntity(
        dataSourceId = id, databaseId = "db-$id", name = id, colorHex = "#0B6E99", titlePropId = "title",
        datePropId = "dAtE", donePropId = "dOnE", doneType = "CHECKBOX", doneOption = null, undoneOption = null,
        colorPropId = "cTgR", sortPropId = null,
    )

    private suspend fun seeded() {
        db.dataSourceDao().upsert(source(FakeNotionStore.CALENDAR))
        db.dataSourceDao().upsert(source("ds2"))
        notion.seed("p1", "영어 스터디", "2026-10-13")
        NotionSyncEngine(notion, db, clock, UnconfinedTestDispatcher(), WidgetRefresher.None).sync(SyncTrigger.APP_OPEN)
        now = now.plusSeconds(120)
    }

    @Test
    fun hoursOfflineAndALongNotionOutageNeverDropAnEditADeletionOrANewEvent() = runTest {
        seeded()
        notion.seed("p2", "운동", "2026-10-14")
        NotionSyncEngine(notion, db, clock, UnconfinedTestDispatcher(), WidgetRefresher.None).sync(SyncTrigger.APP_OPEN)
        writer.update("p1", EventChange(title = "오프라인에서 고침"))
        writer.trash("p2")
        writer.create(FakeNotionStore.CALENDAR, "새 일정", DateValue.allDay(LocalDate.of(2026, 10, 20)))
        notion.networkDown = true
        repeat(30) { processor().process() }
        notion.networkDown = false
        notion.writeError = NotionError.Server(503) // 노션이 몇 시간 장애
        repeat(30) { processor().process() }

        assertEquals(setOf("UPDATE", "TRASH", "CREATE"), db.outboxDao().all().map { it.op }.toSet()) // 하나도 버리지 않았다
        assertEquals("오프라인에서 고침", db.eventDao().get("p1")!!.title) // 로컬도 되돌리지 않았다
        assertNull(db.eventDao().get("p2"))
        assertTrue(db.conflictDao().all().isEmpty())

        notion.writeError = null
        processor().process()
        assertTrue(db.outboxDao().all().isEmpty()) // 복구되면 모두 보낸다
        assertEquals("오프라인에서 고침", notion.pages.getValue("p1").title)
    }

    @Test
    fun aDatabaseThatIsNoLongerSharedOnlyHoldsBackItsOwnWrites() = runTest {
        seeded()
        writer.update("p1", EventChange(title = "공유 끊긴 DB의 바꿈"))
        writer.create("ds2", "다른 DB의 새 일정", DateValue.allDay(LocalDate.of(2026, 10, 21)))
        notion.sourceErrors = mapOf(FakeNotionStore.CALENDAR to NotionError.NotShared)

        val outcome = processor().process()

        assertEquals(1, notion.pages.values.count { it.title == "다른 DB의 새 일정" }) // 다른 DB는 계속 보낸다
        val held = db.outboxDao().all().single()
        assertEquals(FakeNotionStore.CALENDAR, held.dataSourceId) // 끊긴 DB의 작업은 지우지 않고 남긴다
        assertEquals("NotShared", held.lastError) // 화면이 비교하는 글자 그대로(릴리스에서도)
        assertNull(outcome.retryError) // 결과 오류로 올리지 않아 다른 DB의 보기 새로고침(SY-06)이 막히지 않는다
        assertTrue(outcome.createdInSources.contains("ds2"))
    }

    @Test
    fun aJobThatCannotBeReadIsClearedAndTheNextOneIsStillSent() = runTest {
        seeded()
        writer.update("p1", EventChange(title = "읽을 수 없게 될 바꿈"))
        writer.create("ds2", "뒤의 새 일정", DateValue.allDay(LocalDate.of(2026, 10, 22)))
        val broken = db.outboxDao().all().first()
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET changes = '{망가짐' WHERE id = ${broken.id}")

        processor().process()

        assertEquals(1, notion.pages.values.count { it.title == "뒤의 새 일정" })
        assertTrue(db.outboxDao().all().isEmpty())
        assertEquals(OutboxProcessor.KIND_REJECTED, db.conflictDao().all().single().kind) // 조용히 사라지지 않고 '노션 반영 실패'에 남는다
    }

    @Test
    fun aChangeWhoseSavedOriginalIsBrokenIsClearedWithoutBeingSentAndRecorded() = runTest {
        seeded()
        writer.update("p1", EventChange(title = "거부될 바꿈"))
        writer.create("ds2", "뒤의 새 일정", DateValue.allDay(LocalDate.of(2026, 10, 22)))
        val row = db.outboxDao().all().first()
        db.openHelper.writableDatabase.execSQL("UPDATE outbox SET baseValues = '{망가짐' WHERE id = ${row.id}") // 되돌릴 값이 망가짐

        processor().process()

        assertEquals("영어 스터디", notion.pages.getValue("p1").title) // 망가진 작업은 보내지 않는다
        assertEquals(1, notion.pages.values.count { it.title == "뒤의 새 일정" }) // 큐가 멈추지 않았다
        assertTrue(db.outboxDao().all().isEmpty())
        assertEquals(OutboxProcessor.KIND_REJECTED, db.conflictDao().all().single().kind)
    }

    @Test
    fun storedErrorNamesAreTheWordsTheBannersAndSettingsCompare() {
        // MonthBanners·SettingsCategories는 'Unauthorized'·'NotShared' 글자와 비교한다. 클래스 이름(R8이 바꿈)이 아니라 정한 글자여야 한다.
        assertEquals("Unauthorized", NotionError.Unauthorized.storedName())
        assertEquals("NotShared", NotionError.NotShared.storedName())
        assertEquals("Network", NotionError.Network.storedName())
        assertEquals("RateLimited", NotionError.RateLimited.storedName())
        assertEquals("Server", NotionError.Server(503).storedName())
        assertEquals("Validation", NotionError.Validation("x").storedName())
    }

    @Test
    fun aFailedWriteStepIsRecordedAndTheNextStepStillRunsButCancellationStops() = runTest {
        val result = continueOnFailure(db.syncLogDao(), clock) { error("예상 못 한 예외") }
        assertNull(result) // 다음 단계(읽기 동기화)는 이어서 돈다
        val logged = db.syncLogDao().recent(10).single()
        assertEquals(listOf("ERROR", LOCAL_WRITE_ERROR), listOf(logged.result, logged.error)) // 삼키지 않고 진단에 남긴다
        assertEquals(3, continueOnFailure(db.syncLogDao(), clock) { 3 })
        try {
            continueOnFailure<Unit>(db.syncLogDao(), clock) { throw CancellationException("중지") }
            fail("취소는 삼키지 않는다")
        } catch (_: CancellationException) {
        }
    }
}
