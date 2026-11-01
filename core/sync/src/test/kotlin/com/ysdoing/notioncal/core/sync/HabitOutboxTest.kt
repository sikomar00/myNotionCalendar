package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.ysdoing.notioncal.core.data.DateValue
import com.ysdoing.notioncal.core.data.SettingsRepository
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.habit.HabitRepository
import com.ysdoing.notioncal.core.data.db.HabitEntity
import kotlinx.coroutines.test.TestScope
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import com.ysdoing.notioncal.core.data.EventWriter
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.SourceKind
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

/** HB-02 습관 기록 쓰기: relation을 담아 만들고, 보내는 도중 해제해도 체크가 되살아나지 않는다(검증 메모 4). */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class HabitOutboxTest {
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-10-19T03:00:00Z")
    private val clock = object : Clock() {
        override fun getZone(): ZoneId = ZoneId.of("UTC")
        override fun withZone(zone: ZoneId?): Clock = this
        override fun instant(): Instant = now
    }
    private val notion = FakeNotionStore { now }
    private lateinit var writer: EventWriter

    private fun processor() = OutboxProcessor(notion, notion, db, clock, WidgetRefresher.None, UnconfinedTestDispatcher())
    private fun engine() = NotionSyncEngine(notion, db, clock, UnconfinedTestDispatcher(), WidgetRefresher.None)

    @get:Rule
    val tmp = TemporaryFolder()

    private fun TestScope.habits(): HabitRepository {
        val store = PreferenceDataStoreFactory.create(scope = backgroundScope) { File(tmp.root, "settings.preferences_pb") }
        return HabitRepository(db, writer, SettingsRepository(store, WidgetRefresher.None), clock)
    }

    private suspend fun seedMeditation() {
        notion.seedHabit(FakeNotionStore.Habit("habit-meditate", "명상", start = "2026-10-01"))
        db.habitDao().insert(listOf(HabitEntity("habit-meditate", HABITS, "명상", null, 7, true, LocalDate.of(2026, 10, 1), null)))
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        writer = EventWriter(db, clock, WidgetRefresher.None, OutboxRequester.None)
        registerHabitPair(db)
    }

    @After
    fun tearDown() = db.close()

    private val day = DateValue.allDay(LocalDate.of(2026, 10, 19))

    @Test
    fun checkCreatesLogLinkedToHabit() = runTest {
        writer.create(LOG, "명상 2026-10-19", day, link = "habit-meditate")
        processor().process()

        assertEquals(listOf("habit-meditate" to "2026-10-19"), notion.liveLogs())
        val local = db.eventDao().pendingPageIds() // 보낸 뒤 대기 작업 없음
        assertTrue(local.isEmpty())
        val created = notion.pages.values.single { it.dataSourceId == FakeNotionStore.HABIT_LOG }
        assertEquals("habit-meditate", db.eventDao().get(created.id)!!.linkPageId)
        assertEquals("명상 2026-10-19", created.title)
    }

    @Test
    fun uncheckWhileCreateIsInFlightTrashesCreatedPage() = runTest {
        val tempId = writer.create(LOG, "명상 2026-10-19", day, link = "habit-meditate")
        // 노션이 만든 뒤 응답이 오기 전에 사용자가 해제한다(EventWriter는 처리기 잠금 밖에서 임시 행과 작업을 지운다).
        notion.afterCreate = { writer.trash(tempId) }
        processor().process()

        assertTrue("노션에 기록이 남지 않는다", notion.liveLogs().isEmpty())
        assertEquals(1, notion.pages.values.count { it.dataSourceId == FakeNotionStore.HABIT_LOG && it.inTrash })
        assertTrue(db.outboxDao().all().isEmpty())
        val created = notion.pages.values.single { it.dataSourceId == FakeNotionStore.HABIT_LOG }
        assertEquals(null, db.eventDao().get(created.id))
        assertEquals(null, db.eventDao().get(tempId))
    }

    /** HB-02 완료 기준: 비행기 모드에서 체크 → 해제 → 체크 → 연결 후 노션에 기록 1개. */
    @Test
    fun offlineCheckUncheckCheckSendsOneLog() = runTest {
        seedMeditation()
        val repo = habits()
        val today = LocalDate.of(2026, 10, 19)
        notion.networkDown = true
        repo.toggle("habit-meditate", today)
        repo.toggle("habit-meditate", today)
        repo.toggle("habit-meditate", today)
        processor().process() // 연결 없음: 그대로 남는다
        notion.networkDown = false
        processor().process()

        assertEquals(listOf("habit-meditate" to "2026-10-19"), notion.liveLogs())
        assertEquals(1, notion.pages.values.count { it.dataSourceId == FakeNotionStore.HABIT_LOG })
    }

    /** HB-02 완료 기준: 노션에서 생긴 중복 2개인 날을 해제하면 둘 다 휴지통으로 간다. */
    @Test
    fun uncheckingDuplicatedDayTrashesBothInNotion() = runTest {
        seedMeditation()
        notion.seed("r1", "명상 2026-10-18", "2026-10-18", dataSourceId = LOG, habit = "habit-meditate")
        notion.seed("r2", "명상 2026-10-18", "2026-10-18", dataSourceId = LOG, habit = "habit-meditate")
        engine().sync(SyncTrigger.APP_OPEN)

        habits().toggle("habit-meditate", LocalDate.of(2026, 10, 18))
        processor().process()
        assertTrue(notion.liveLogs().isEmpty())
        assertTrue(notion.pages.getValue("r1").inTrash && notion.pages.getValue("r2").inTrash)
    }

    companion object {
        const val LOG = FakeNotionStore.HABIT_LOG
        const val HABITS = FakeNotionStore.HABITS

        /** ✅ 습관 기록 + 🌱 습관 등록(FakeNotionStore의 속성 ID). */
        suspend fun registerHabitPair(db: NotionCalDatabase) {
            db.dataSourceDao().upsert(
                DataSourceEntity(
                    dataSourceId = LOG, databaseId = "db-log", name = "✅ 습관 기록", colorHex = "#0F7B6C", titlePropId = "title",
                    datePropId = "nAlJ", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                    colorPropId = null, sortPropId = null, kind = SourceKind.HABIT_LOG.name, linkPropId = "rEl", linkDataSourceId = HABITS,
                ),
            )
            db.dataSourceDao().upsert(
                DataSourceEntity(
                    dataSourceId = HABITS, databaseId = "db-habits", name = "🌱 습관", colorHex = "#0F7B6C", titlePropId = "title",
                    datePropId = null, donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                    colorPropId = null, sortPropId = null, kind = SourceKind.HABIT.name,
                    habitPropIds = """{"emoji":"eMoJ","weeklyTarget":"gOaL","active":"aCtV","startDate":"sTrT","sortOrder":"oRdR"}""",
                ),
            )
        }
    }
}
