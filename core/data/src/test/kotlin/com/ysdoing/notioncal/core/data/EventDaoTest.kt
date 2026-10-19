package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class EventDaoTest {
    private lateinit var db: NotionCalDatabase
    private lateinit var repo: EventRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repo = EventRepository(db.eventDao())
    }

    @After
    fun tearDown() = db.close()

    private fun source(
        id: String,
        color: String = "#0B6E99",
        enabled: Boolean = true,
        position: Int = 0,
        kind: SourceKind = SourceKind.CALENDAR,
        link: String? = null,
    ) = DataSourceEntity(
        dataSourceId = id, databaseId = "db-$id", name = id, colorHex = color, titlePropId = "title",
        datePropId = if (kind == SourceKind.HABIT) null else "d",
        donePropId = null, doneType = null, doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null,
        enabled = enabled, position = position, kind = kind.name, linkDataSourceId = link,
    )

    private fun event(id: String, ds: String, start: String, end: String? = null, colorKey: String? = null, sortKey: Int? = null) =
        EventEntity(
            pageId = id, dataSourceId = ds, title = id, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
            startTime = null, endTime = null, isAllDay = true, done = false, colorKey = colorKey, sortKey = sortKey,
            url = "", lastEditedTime = 0,
        )

    private suspend fun ids(from: String, to: String) =
        repo.observeRange(LocalDate.parse(from), LocalDate.parse(to)).first().map { it.pageId }

    @Test
    fun multiDayEventAppearsInBothMonths() = runTest {
        // §12 필수 시나리오: 10/30~11/2 일정이 10월·11월 화면에 모두 보인다.
        db.dataSourceDao().upsert(source("ds"))
        db.eventDao().upsert(listOf(event("span", "ds", "2026-10-30", "2026-11-02"), event("sep", "ds", "2026-09-30")))

        assertEquals(listOf("span"), ids("2026-10-01", "2026-10-31"))
        assertEquals(listOf("span"), ids("2026-11-01", "2026-11-30"))
        assertEquals(listOf("sep"), ids("2026-09-01", "2026-09-30"))
    }

    @Test
    fun disabledSourceIsHidden() = runTest {
        db.dataSourceDao().upsert(source("on"))
        db.dataSourceDao().upsert(source("off", enabled = false))
        db.eventDao().upsert(listOf(event("a", "on", "2026-10-13"), event("b", "off", "2026-10-13")))
        assertEquals(listOf("a"), ids("2026-10-01", "2026-10-31"))
    }

    @Test
    fun colorFallsBackToSourceColorAndSortKeyOrders() = runTest {
        db.dataSourceDao().upsert(source("ds", color = "#123456"))
        db.eventDao().upsert(
            listOf(
                event("late", "ds", "2026-10-13", sortKey = 3),
                event("none", "ds", "2026-10-13"),
                event("early", "ds", "2026-10-13", colorKey = "yellow", sortKey = 0),
            ),
        )
        val events = repo.observeRange(LocalDate.parse("2026-10-13"), LocalDate.parse("2026-10-13")).first()
        assertEquals(listOf("early", "late", "none"), events.map { it.pageId })
        assertEquals("#DFAB01", events[0].colorHex)
        assertEquals("#123456", events[1].colorHex)
    }

    @Test
    fun pageIdsInRangeAndDeleteSourceCascade() = runTest {
        db.dataSourceDao().upsert(source("ds"))
        db.eventDao().upsert(listOf(event("a", "ds", "2026-10-01"), event("b", "ds", "2026-12-01")))
        assertEquals(
            listOf("a"),
            db.eventDao().pageIdsInRange("ds", LocalDate.parse("2026-10-01"), LocalDate.parse("2026-10-31")),
        )
        DataSourceRepository(db, db.dataSourceDao(), db.eventDao(), WidgetRefresher.None).delete("ds")
        assertEquals(emptyList<String>(), db.eventDao().pageIdsInRange("ds", LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)))
    }

    /** Phase 6: ✅ 습관 기록 행(events의 다른 종류)은 달력·검색·일정 위젯 조회에 섞이지 않는다. */
    @Test
    fun habitLogRowsStayOutOfCalendarAndSearch() = runTest {
        db.dataSourceDao().upsert(source("cal"))
        db.dataSourceDao().upsert(source("log", kind = SourceKind.HABIT_LOG, link = "habits"))
        db.eventDao().upsert(
            listOf(event("meeting", "cal", "2026-10-19"), event("명상 2026-10-19", "log", "2026-10-19").copy(linkPageId = "h1")),
        )
        assertEquals(listOf("meeting"), ids("2026-10-01", "2026-10-31"))
        assertEquals(emptyList<String>(), repo.search("명상", LocalDate.parse("2026-10-19")).map { it.pageId })
        assertEquals(listOf("meeting"), repo.search("meeting", LocalDate.parse("2026-10-19")).map { it.pageId })
    }

    /** 화면의 DB 목록(필터·퀵 추가 칩·위젯 설정)은 일정 DB만, 설정 화면은 전체. */
    @Test
    fun calendarSourcesExcludeHabitDatabases() = runTest {
        db.dataSourceDao().upsert(source("cal", position = 0))
        db.dataSourceDao().upsert(source("log", position = 1, kind = SourceKind.HABIT_LOG, link = "habits"))
        db.dataSourceDao().upsert(source("habits", position = 2, kind = SourceKind.HABIT))
        val repository = DataSourceRepository(db, db.dataSourceDao(), db.eventDao(), WidgetRefresher.None)
        assertEquals(listOf("cal"), repository.observeCalendarSources().first().map { it.dataSourceId })
        assertEquals(listOf("cal", "log", "habits"), repository.observeAll().first().map { it.dataSourceId })
    }

    /** HB-01: 습관 기록과 🌱 습관은 한 쌍이라 켜기·끄기와 삭제를 함께 한다. 삭제하면 습관 캐시와 기록 캐시도 지운다. */
    @Test
    fun habitPairIsToggledAndDeletedTogether() = runTest {
        db.dataSourceDao().upsert(source("cal"))
        db.dataSourceDao().upsert(source("log", kind = SourceKind.HABIT_LOG, link = "habits"))
        db.dataSourceDao().upsert(source("habits", kind = SourceKind.HABIT))
        db.habitDao().insert(listOf(com.ysdoing.notioncal.core.data.db.HabitEntity("h1", "habits", "명상", null, 7, true, null, null)))
        db.eventDao().upsert(listOf(event("r1", "log", "2026-10-19").copy(linkPageId = "h1"), event("c1", "cal", "2026-10-19")))
        val repository = DataSourceRepository(db, db.dataSourceDao(), db.eventDao(), WidgetRefresher.None)

        repository.setEnabled("habits", false)
        assertEquals(false, db.dataSourceDao().get("log")!!.enabled)
        repository.setEnabled("log", true)
        assertEquals(true, db.dataSourceDao().get("habits")!!.enabled)

        repository.delete("log")
        assertEquals(null, db.dataSourceDao().get("log"))
        assertEquals(null, db.dataSourceDao().get("habits"))
        assertEquals(emptyList<Any>(), db.habitDao().forSource("habits"))
        assertEquals(null, db.eventDao().get("r1"))
        assertEquals("c1", db.eventDao().get("c1")!!.pageId)
    }

    /** LD-01: 거래내역·예산·카테고리 마스터는 한 묶음으로 켜고 끄고 지운다(어느 쪽에서 해도). 예산·카테고리 캐시도 지운다. */
    @Test
    fun ledgerTrioIsToggledAndDeletedTogether() = runTest {
        db.dataSourceDao().upsert(source("ledger", kind = SourceKind.LEDGER))
        db.dataSourceDao().upsert(source("budget", kind = SourceKind.BUDGET, link = "ledger"))
        db.dataSourceDao().upsert(source("category", kind = SourceKind.CATEGORY, link = "ledger"))
        db.dataSourceDao().upsert(source("cal"))
        db.ledgerDao().insertBudgets(listOf(com.ysdoing.notioncal.core.data.db.BudgetEntity("b1", "budget", "월", "2026-10", "전체", 1)))
        val repository = DataSourceRepository(db, db.dataSourceDao(), db.eventDao(), WidgetRefresher.None)

        repository.setEnabled("budget", false)
        assertEquals(listOf(false, false, false, true), listOf("ledger", "budget", "category", "cal").map { db.dataSourceDao().get(it)!!.enabled })

        repository.delete("category")
        assertEquals(listOf(null, null, null), listOf("ledger", "budget", "category").map { db.dataSourceDao().get(it) })
        assertEquals(emptyList<Any>(), db.ledgerDao().observeBudgets().first())
        assertEquals("cal", db.dataSourceDao().get("cal")!!.dataSourceId)
    }
}
