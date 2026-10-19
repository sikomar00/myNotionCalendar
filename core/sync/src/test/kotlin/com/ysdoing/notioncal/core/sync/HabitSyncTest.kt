package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.sync.FakeNotionStore.Habit
import com.ysdoing.notioncal.core.sync.HabitOutboxTest.Companion.HABITS
import com.ysdoing.notioncal.core.sync.HabitOutboxTest.Companion.LOG
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** HB-01 🌱 습관 동기화(날짜 없는 전체 조회)와 ✅ 습관 기록 조회 범위. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HabitSyncTest {
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-10-19T03:00:00Z") // 서울 12:00
    private val notion = FakeNotionStore { now }
    private val today = LocalDate.of(2026, 10, 19)

    private fun engine() = NotionSyncEngine(notion, db, Clock.fixed(now, ZoneOffset.UTC), UnconfinedTestDispatcher(), WidgetRefresher.None)

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        HabitOutboxTest.registerHabitPair(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun habits() = db.habitDao().forSource(HABITS).associateBy { it.pageId }

    /** 노션에서 습관 추가·이름 변경·'진행 중' 해제·삭제가 다음 동기화에 반영된다(HB-01 완료 기준). */
    @Test
    fun habitsAreFullyReplacedEachSync() = runTest {
        notion.seedHabit(Habit("h-med", "명상", emoji = "🧘", target = 7.0, start = "2026-10-19", order = 2.0))
        notion.seedHabit(Habit("h-walk", "산책", target = 3.0, order = 1.0))
        engine().sync(SyncTrigger.APP_OPEN)
        assertEquals(setOf("h-med", "h-walk"), habits().keys)
        assertEquals("🧘", habits().getValue("h-med").emoji)
        assertEquals(LocalDate.of(2026, 10, 19), habits().getValue("h-med").startDate)
        assertEquals(listOf("h-walk", "h-med"), db.habitDao().observeActive().first().map { it.pageId }) // 순서대로

        notion.habits.getValue("h-med").name = "아침 명상"
        notion.habits.getValue("h-walk").active = false
        notion.seedHabit(Habit("h-plan", "내일계획", emoji = "🗓️"))
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals("아침 명상", habits().getValue("h-med").name)
        assertEquals(false, habits().getValue("h-walk").active) // 기록 보존을 위해 행은 남고
        assertEquals(listOf("h-med", "h-plan"), db.habitDao().observeActive().first().map { it.pageId }) // 화면에서는 숨김
        assertEquals(7, habits().getValue("h-plan").weeklyTarget)

        notion.habits.getValue("h-plan").inTrash = true
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals(setOf("h-med", "h-walk"), habits().keys)
        // 🌱 습관은 필터 없이 조회한다(FakeNotionStore가 필터를 거부).
        assertTrue(notion.queries.filter { it.first == HABITS }.all { it.second == null })
    }

    /** ✅ 습관 기록은 relation을 읽어 둔다. 달력 조회에는 섞이지 않는다. */
    @Test
    fun habitLogsKeepRelationAndStayOffCalendar() = runTest {
        notion.seedHabit(Habit("h-med", "명상", start = "2026-10-01"))
        notion.seed("r1", "명상 2026-10-18", "2026-10-18", dataSourceId = LOG, habit = "h-med")
        engine().sync(SyncTrigger.APP_OPEN)
        assertEquals("h-med", db.eventDao().get("r1")!!.linkPageId)
        assertTrue(db.eventDao().observeRange(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31)).first().isEmpty())
    }

    /**
     * 검증 메모 6: 기록 조회 범위 = max(가장 이른 시작일, 오늘-400일) ~ 오늘. 한 달 범위로는 긴 연속·연속 달성 주가 잘린다.
     * 범위 조회는 시작을 31일 앞당겨 보낸다(§8.5-1).
     */
    @Test
    fun habitLogRangeStartsAtEarliestHabitStart() = runTest {
        notion.seedHabit(Habit("h-med", "명상", start = "2026-09-01"))
        notion.seedHabit(Habit("h-walk", "산책", start = "2026-10-10"))
        engine().sync(SyncTrigger.APP_OPEN)
        assertEquals("2026-08-01" to "2026-10-19", lastLogRange())

        // 시작일 없는 습관이 있으면 400일 전까지.
        notion.seedHabit(Habit("h-any", "아무 때나"))
        db.dataSourceDao().setLastFullSyncAt(LOG, 0) // 다음 동기화를 전체 대조로
        now = now.plusSeconds(60)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals(today.minusDays(400 + 31).toString() to "2026-10-19", lastLogRange())
    }

    private fun lastLogRange(): Pair<String, String> {
        val filter = notion.queries.last { it.first == LOG && it.second?.containsKey("and") == true }.second!!
        val (from, to) = filter["and"]!!.jsonArray.map { it.jsonObject["date"]!!.jsonObject.values.single().jsonPrimitive.content.take(10) }
        return from to to
    }

    /** NS-09: 🌱 습관은 제목만 필수. 이모지 속성이 사라져도 동기화하고 알림을 남기며, 제목이 없으면 캐시를 그대로 둔다. */
    @Test
    fun habitSchemaChangesAreReportedNotFatal() = runTest {
        notion.seedHabit(Habit("h-med", "명상", emoji = "🧘"))
        engine().sync(SyncTrigger.APP_OPEN)

        notion.habitSchema = schemaOf(
            """"이름":{"id":"title","name":"이름","type":"title"},"주 목표":{"id":"gOaL","name":"주 목표","type":"number"}""",
        )
        notion.habits.getValue("h-med").name = "아침 명상"
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals("아침 명상", habits().getValue("h-med").name)
        assertNull(habits().getValue("h-med").emoji)
        val issues = SchemaIssue.parse(db.dataSourceDao().get(HABITS)!!.schemaIssues)
        assertTrue(issues.toString(), issues.any { it.role == SchemaIssue.EMOJI && it.kind == SchemaIssue.MISSING })

        notion.habitSchema = schemaOf(""""주 목표":{"id":"gOaL","name":"주 목표","type":"number"}""")
        notion.habits.getValue("h-med").name = "바뀐 이름"
        now = now.plusSeconds(900)
        engine().sync(SyncTrigger.PERIODIC)
        assertEquals("아침 명상", habits().getValue("h-med").name) // 제목이 없으면 건너뛰고 캐시 유지
    }

    /** 검증 메모 10: 기록 DB의 '습관' 속성이 relation이 아니면 알림을 남긴다(체크는 HabitRepository가 막는다). */
    @Test
    fun habitLogLinkTypeChangeIsReported() = runTest {
        notion.logSchema = schemaOf(
            """"이름":{"id":"title","name":"이름","type":"title"},"날짜":{"id":"nAlJ","name":"날짜","type":"date"},
               "습관":{"id":"rEl","name":"습관","type":"rich_text"}""",
        )
        engine().sync(SyncTrigger.APP_OPEN)
        val issues = SchemaIssue.parse(db.dataSourceDao().get(LOG)!!.schemaIssues)
        assertTrue(issues.toString(), issues.any { it.role == SchemaIssue.LINK && it.kind == SchemaIssue.TYPE_CHANGED })
    }

    /** '노션과 비교'는 날짜 없는 🌱 습관을 건너뛰고 기록 DB는 그대로 대조한다. */
    @Test
    fun comparerSkipsHabitDatabase() = runTest {
        notion.seedHabit(Habit("h-med", "명상"))
        notion.seed("r1", "명상 2026-10-18", "2026-10-18", dataSourceId = LOG, habit = "h-med")
        engine().sync(SyncTrigger.APP_OPEN)
        val report = NotionComparer(notion, db, Clock.fixed(now, ZoneOffset.UTC), UnconfinedTestDispatcher()).compare()
        assertTrue(report.toString(), report.matches)
        assertEquals(1, report.remoteCount)
    }

    private fun schemaOf(props: String) = NotionJson.decodeFromString(DataSourceDto.serializer(), """{"id":"x","properties":{$props}}""")
}
