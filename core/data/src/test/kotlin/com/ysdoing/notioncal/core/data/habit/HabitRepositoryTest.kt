package com.ysdoing.notioncal.core.data.habit

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.EventChange
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.OutboxJson
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SourceKind
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.HabitEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
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

/** HB-02 체크·해제(아웃박스), HB-04 '진행 중' 숨김, 같은 날 기록 여러 개 = 하나. */
@RunWith(RobolectricTestRunner::class)
class HabitRepositoryTest {
    @get:Rule
    val tmp = TemporaryFolder()
    private lateinit var db: NotionCalDatabase
    private val today = LocalDate.of(2026, 10, 19)
    private val clock = Clock.fixed(Instant.parse("2026-10-19T03:00:00Z"), ZoneOffset.UTC)
    private var widgetRequests = 0

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        db.dataSourceDao().upsert(source("log", SourceKind.HABIT_LOG, link = "habits"))
        db.dataSourceDao().upsert(source("habits", SourceKind.HABIT))
        db.habitDao().insert(
            listOf(
                HabitEntity("h-med", "habits", "명상", "🧘", 7, true, LocalDate.of(2026, 10, 1), 1.0),
                HabitEntity("h-walk", "habits", "산책", "🚶", 3, true, null, 2.0),
                HabitEntity("h-off", "habits", "쉬는 습관", null, 7, false, null, 0.0),
            ),
        )
    }

    @After
    fun tearDown() = db.close()

    private fun source(id: String, kind: SourceKind, link: String? = null) = DataSourceEntity(
        dataSourceId = id, databaseId = "db-$id", name = id, colorHex = "#0F7B6C", titlePropId = "title",
        datePropId = if (kind == SourceKind.HABIT) null else "d", donePropId = null, doneType = null, doneOption = null,
        undoneOption = null, colorPropId = null, sortPropId = null, kind = kind.name,
        linkPropId = if (kind == SourceKind.HABIT_LOG) "rel" else null, linkDataSourceId = link,
    )

    private fun TestScope.repository(): HabitRepository {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        val writer = EventWriter(db, clock, { widgetRequests++ }, OutboxRequester.None)
        return HabitRepository(db, writer, SettingsRepository(store, WidgetRefresher {}), clock)
    }

    private fun log(id: String, habit: String, date: LocalDate) = EventEntity(
        pageId = id, dataSourceId = "log", title = "명상 $date", startDate = date, endDate = null, startTime = null, endTime = null,
        isAllDay = true, done = false, colorKey = null, sortKey = null, url = "", lastEditedTime = 0, linkPageId = habit,
    )

    @Test
    fun checkCreatesLogWithTitleDateAndLink() = runTest {
        val repo = repository()
        assertEquals(ToggleResult.CHECKED, repo.toggle("h-med", today))
        val row = db.outboxDao().all().single()
        assertEquals("CREATE", row.op)
        assertEquals("log", row.dataSourceId)
        val change = OutboxJson.decodeFromString(EventChange.serializer(), row.changes)
        assertEquals("명상 2026-10-19", change.title)
        assertEquals("2026-10-19", change.date!!.startDate)
        assertEquals("h-med", change.link)
        assertTrue(widgetRequests > 0) // WG-05 바로 갱신 요청
    }

    /** HB-02 완료 기준: 오프라인에서 체크 → 해제 → 체크하면 보낼 생성은 1건뿐이다. */
    @Test
    fun offlineCheckUncheckCheckLeavesOneCreate() = runTest {
        val repo = repository()
        repo.toggle("h-med", today)
        assertEquals(ToggleResult.UNCHECKED, repo.toggle("h-med", today))
        assertTrue(db.outboxDao().all().isEmpty()) // 아직 안 보낸 생성은 해제와 함께 사라진다
        repo.toggle("h-med", today)
        assertEquals(listOf("CREATE"), db.outboxDao().all().map { it.op })
        assertEquals(1, repo.observeBoard(today).first().habits.first { it.habit.pageId == "h-med" }.status.weekCount)
    }

    /** HB-02 완료 기준: 노션에서 생긴 중복 2개인 날을 해제하면 둘 다 휴지통으로 간다. 표시는 하나로 센다. */
    @Test
    fun duplicateLogsCountOnceAndUncheckTrashesAll() = runTest {
        db.eventDao().upsert(listOf(log("r1", "h-med", today), log("r2", "h-med", today), log("r3", "h-med", today.minusDays(1))))
        val repo = repository()
        val med = repo.observeBoard(today).first().habits.first { it.habit.pageId == "h-med" }
        assertEquals(2, med.status.monthCount)
        assertEquals(HabitMessage.Streak(2), med.status.message)

        assertEquals(ToggleResult.UNCHECKED, repo.toggle("h-med", today))
        assertEquals(setOf("r1", "r2"), db.outboxDao().all().filter { it.op == "TRASH" }.map { it.pageId }.toSet())
        assertEquals("r3", db.eventDao().get("r3")!!.pageId)
    }

    /** 검증 메모 7: 위젯을 빠르게 두 번 누르면 체크 → 해제로 끝난다(둘 다 '기록 없음'을 보고 두 번 만들지 않는다). */
    @Test
    fun concurrentTogglesEndUnchecked() = runTest {
        val repo = repository()
        withContext(Dispatchers.Default) {
            listOf(async { repo.toggle("h-med", today) }, async { repo.toggle("h-med", today) }).awaitAll()
        }
        assertTrue(db.outboxDao().all().isEmpty())
        assertTrue(db.eventDao().habitLogIds("h-med", today).isEmpty())
    }

    @Test
    fun onlyTodayAndLastSixDaysCanBeToggled() = runTest {
        val repo = repository()
        assertEquals(ToggleResult.CHECKED, repo.toggle("h-med", today.minusDays(6)))
        assertEquals(ToggleResult.OUT_OF_RANGE, repo.toggle("h-med", today.minusDays(7)))
        assertEquals(ToggleResult.OUT_OF_RANGE, repo.toggle("h-med", today.plusDays(1)))
        assertEquals(1, db.outboxDao().all().size)
    }

    /** 검증 메모 10: 기록 DB의 '습관' 속성이 relation이 아니게 되면 연결 없는 기록을 만들지 않는다. */
    @Test
    fun brokenLinkPropertyBlocksCheck() = runTest {
        db.dataSourceDao().setSchemaIssues("log", SchemaIssue.encode(listOf(SchemaIssue(SchemaIssue.LINK, SchemaIssue.TYPE_CHANGED))))
        assertEquals(ToggleResult.BLOCKED, repository().toggle("h-med", today))
        assertTrue(db.outboxDao().all().isEmpty())
    }

    /** HB-04: '진행 중'을 끈 습관은 숨기고, 꺼 둔 DB의 습관도 숨긴다. 순서대로. */
    @Test
    fun boardShowsActiveHabitsInOrder() = runTest {
        val repo = repository()
        val board = repo.observeBoard(today).first()
        assertEquals(listOf("h-med", "h-walk"), board.habits.map { it.habit.pageId })
        assertEquals(today, board.today)
        // 주 3회 습관은 주 단위 문구.
        assertEquals(HabitMessage.Weekly(0, 3, 0), board.habits[1].status.message)

        db.dataSourceDao().setEnabled("habits", false)
        assertTrue(repo.observeBoard(today).first().habits.isEmpty())
    }
}
