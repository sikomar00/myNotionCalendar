package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.PropValue
import com.ysdoing.notioncal.core.data.parseExtraValues
import com.ysdoing.notioncal.core.data.parsePropCatalog
import com.ysdoing.notioncal.core.data.SelectValue
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.notion.NotionError
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
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
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** PRD §12 쓰기 시나리오: 충돌 3종, 생성 중복, 오프라인 10건 순서, 400 거부, 읽기 동기화 보호. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class OutboxProcessorTest {
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
    private fun engine() = NotionSyncEngine(notion, db, clock, UnconfinedTestDispatcher(), WidgetRefresher.None)

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = EventWriter(db, clock, WidgetRefresher.None, OutboxRequester.None)
    }

    @After
    fun tearDown() = db.close()

    /** 노션에 일정 2개를 두고 읽기 동기화로 받아 온 상태(base 값이 실제 동기화에서 나온다). */
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
        engine().sync(SyncTrigger.APP_OPEN)
        tick()
    }

    private fun tick(seconds: Long = 120) {
        now = now.plusSeconds(seconds)
    }

    private suspend fun conflicts() = db.conflictDao().all()

    @Test
    fun extraPropertiesSyncAndSendOnlyChanged() = runTest {
        // EX-07: 동기화로 속성 목록·값을 받고, 편집한 속성만 보낸다.
        seeded()
        notion.editRemotely("p1") {
            pri = "pri-1"
            tags = listOf("t-home")
        }
        tick()
        engine().sync(SyncTrigger.PULL_TO_REFRESH)
        tick()
        val source = db.dataSourceDao().get("ds")!!
        assertEquals(listOf("Cost", "Ctgr", "Pri.", "Tags", "URL"), parsePropCatalog(source.propCatalog).map { it.name })
        assertEquals(PropValue(optionIds = listOf("pri-1")), parseExtraValues(db.eventDao().get("p1")!!.extraValues)["pRi"])

        writer.update("p1", EventChange(props = mapOf("tAg" to PropValue(optionIds = listOf("t-home", "t-work")), "cOs" to PropValue(number = 3.5))))
        tick()
        processor().process()

        val page = notion.pages.getValue("p1")
        assertEquals(listOf("t-home", "t-work"), page.tags)
        assertEquals(3.5, page.cost)
        assertEquals("pri-1", page.pri) // 안 바꾼 속성은 그대로
        assertEquals("UPDATE 영어 스터디 [Cost, Tags]", notion.writeLog.last())
        val local = parseExtraValues(db.eventDao().get("p1")!!.extraValues)
        assertEquals(PropValue(number = 3.5), local["cOs"])
        assertEquals(0, db.eventDao().get("p1")!!.pendingOps)
    }

    @Test
    fun extraPropertyConflictNotionWins() = runTest {
        // EX-07도 §8.5-6 규칙: 같은 속성을 양쪽에서 바꾸면 노션 값이 남고 기록된다. 다른 속성은 보낸다.
        seeded()
        writer.update("p1", EventChange(props = mapOf("uRl" to PropValue(text = "https://phone.example"), "pRi" to PropValue(optionIds = listOf("pri-1")))))
        tick()
        notion.editRemotely("p1") { link = "https://notion.example" }
        tick()

        processor().process()

        val page = notion.pages.getValue("p1")
        assertEquals("https://notion.example", page.link)
        assertEquals("pri-1", page.pri)
        val conflict = conflicts().single()
        assertEquals("PROP:URL", conflict.field)
        assertEquals("https://phone.example", conflict.localValue)
        assertEquals("https://notion.example", conflict.remoteValue)
        assertEquals(PropValue(text = "https://notion.example"), parseExtraValues(db.eventDao().get("p1")!!.extraValues)["uRl"])
    }

    @Test
    fun conflict1DifferentFieldsBothSurvive() = runTest {
        // §12 충돌 1: 폰에서 제목, 노션에서 날짜를 바꾸면 둘 다 반영된다.
        seeded()
        writer.update("p1", EventChange(title = "영어 회화"))
        tick()
        notion.editRemotely("p1") { start = "2026-10-15" }
        tick()

        val outcome = processor().process()

        assertNull(outcome.retryError)
        assertEquals("영어 회화", notion.pages.getValue("p1").title)
        assertEquals("2026-10-15", notion.pages.getValue("p1").start)
        val local = db.eventDao().get("p1")!!
        assertEquals("영어 회화", local.title)
        assertEquals(LocalDate.of(2026, 10, 15), local.startDate)
        assertEquals(0, local.pendingOps)
        assertTrue(conflicts().isEmpty())
    }

    @Test
    fun conflict2SameFieldNotionWinsAndIsRecorded() = runTest {
        // §12 충돌 2: 같은 제목을 양쪽에서 바꾸면 노션 값이 남고 충돌 기록 1건이 생긴다.
        seeded()
        writer.update("p1", EventChange(title = "폰에서 고친 제목", done = true))
        tick()
        notion.editRemotely("p1") { title = "노션에서 고친 제목" }
        tick()

        processor().process()

        assertEquals("노션에서 고친 제목", notion.pages.getValue("p1").title)
        assertTrue("충돌 없는 필드는 보낸다", notion.pages.getValue("p1").done)
        assertEquals("노션에서 고친 제목", db.eventDao().get("p1")!!.title)
        val conflict = conflicts().single()
        assertEquals("CONFLICT", conflict.kind)
        assertEquals("TITLE", conflict.field)
        assertEquals("폰에서 고친 제목", conflict.localValue)
        assertEquals("노션에서 고친 제목", conflict.remoteValue)
    }

    @Test
    fun conflictWithinSameMinuteIsStillDetected() {
        runTest {
            // 실기기: DB 자동화가 생성 4초 뒤 날짜를 바꿨지만 last_edited_time(분 단위)이 같아 놓쳤던 경우.
            seeded()
            writer.update("p1", EventChange(title = "폰 제목"))
            notion.editRemotely("p1", sameMinute = true) { title = "노션 제목" }

            processor().process()

            assertEquals("노션 제목", notion.pages.getValue("p1").title)
            assertEquals("CONFLICT", conflicts().single().kind)
        }
    }

    @Test
    fun conflict3RemoteDeletionWins() = runTest {
        // §12 충돌 3: 폰에서 고친 항목을 노션에서 지우면 로컬에서도 사라지고, 아웃박스 작업은 폐기되며 기록이 남는다.
        seeded()
        writer.update("p1", EventChange(title = "고쳤지만"))
        writer.setDone("p1", true)
        notion.editRemotely("p1") { inTrash = true }

        processor().process()

        assertNull(db.eventDao().get("p1"))
        assertTrue(db.outboxDao().all().isEmpty())
        assertEquals("REMOTE_DELETED", conflicts().single().kind)
        assertEquals("영어 스터디", notion.pages.getValue("p1").title) // 노션에는 아무것도 쓰지 않았다
    }

    @Test
    fun createTimeoutDoesNotDuplicate() = runTest {
        // §12 생성 중복: 생성 요청이 시간 초과로 끝나도 노션에 페이지가 1개만 생긴다.
        seeded()
        val tempId = writer.create("ds", "영어 스터디 복습", DateValue.allDay(LocalDate.of(2026, 10, 20)))
        notion.createTimesOutAfterCreating = true

        val first = processor().process()
        assertEquals(NotionError.Network, first.retryError)
        assertEquals(1, db.outboxDao().all().single().attempts)

        tick(30)
        val second = processor().process()

        assertNull(second.retryError)
        assertEquals(1, notion.pages.values.count { it.title == "영어 스터디 복습" })
        assertNull(db.eventDao().get(tempId))
        val created = notion.pages.values.single { it.title == "영어 스터디 복습" }
        assertEquals(created.id, db.eventDao().get(created.id)!!.pageId)
        assertTrue(db.outboxDao().all().isEmpty())
    }

    @Test
    fun offlineTenOperationsAreSentInOrderAfterReconnect() = runTest {
        // NS-04: 비행기 모드에서 10건 → 연결 후 10건이 순서대로 반영된다(임시 ID 교체 포함).
        seeded()
        notion.networkDown = true
        val a = writer.create("ds", "A", DateValue.allDay(LocalDate.of(2026, 10, 20))) // 1 생성
        writer.update(a, EventChange(title = "A2")) // 2 생성에 합쳐짐
        val b = writer.create("ds", "B", DateValue.allDay(LocalDate.of(2026, 10, 21))) // 3
        writer.setDone(b, true) // 4 생성에 합쳐짐
        writer.update("p1", EventChange(title = "영어 1")) // 5
        writer.setDone("p1", true) // 6
        writer.trash("p2") // 7
        val c = writer.create("ds", "C", DateValue.allDay(LocalDate.of(2026, 10, 22))) // 8
        writer.trash(c) // 9 아직 안 보낸 생성 → 둘 다 사라짐
        writer.update("p1", EventChange(date = DateValue.allDay(LocalDate.of(2026, 10, 16)))) // 10

        val offline = processor().process()
        assertEquals(NotionError.Network, offline.retryError)
        assertEquals(0, offline.sent)
        // 화면에는 이미 반영돼 있다.
        assertEquals("영어 1", db.eventDao().get("p1")!!.title)
        assertNull(db.eventDao().get("p2"))

        notion.networkDown = false
        val online = processor().process()

        assertNull(online.retryError)
        assertEquals(0, online.remaining)
        assertEquals(
            listOf(
                "CREATE A2",
                "CREATE B",
                "UPDATE 영어 1 [Name]",
                "UPDATE 영어 1 [완료]",
                "TRASH 운동",
                "UPDATE 영어 1 [Date]",
            ),
            notion.writeLog,
        )
        assertTrue(notion.pages.values.single { it.title == "B" }.done)
        assertEquals("2026-10-16", notion.pages.getValue("p1").start)
        assertTrue(notion.pages.getValue("p2").inTrash)
        assertFalse(notion.pages.values.any { it.title == "C" })
        // 임시 ID는 모두 실제 ID로 바뀌었다.
        assertTrue(db.eventDao().pendingPageIds().isEmpty())
        assertNull(db.eventDao().get(a))
        assertTrue(conflicts().isEmpty())
    }

    @Test
    fun validationErrorRevertsAndRecords() = runTest {
        seeded()
        writer.update("p1", EventChange(done = true, select = SelectValue("opt-habit")))
        assertTrue(db.eventDao().get("p1")!!.done)
        notion.rejectNext = true

        processor().process()

        val local = db.eventDao().get("p1")!!
        assertFalse("거부되면 되돌린다", local.done)
        assertEquals("opt-learn", local.colorOptionId)
        assertEquals(0, local.pendingOps)
        assertEquals("REJECTED", conflicts().single().kind)
        assertTrue(db.outboxDao().all().isEmpty())
    }

    @Test
    fun readSyncDoesNotOverwriteUnsentLocalChanges() = runTest {
        seeded()
        writer.update("p1", EventChange(title = "아직 안 보낸 제목"))
        writer.trash("p2")
        notion.editRemotely("p2") { title = "노션에서 바뀜" } // 증분 조회에 걸리게 한다
        tick()

        engine().sync(SyncTrigger.PULL_TO_REFRESH)

        assertEquals("아직 안 보낸 제목", db.eventDao().get("p1")!!.title)
        assertNull("휴지통 대기 중인 일정이 다시 나타나지 않는다", db.eventDao().get("p2"))
    }
}
