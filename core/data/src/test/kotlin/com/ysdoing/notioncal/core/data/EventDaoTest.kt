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

    private fun source(id: String, color: String = "#0B6E99", enabled: Boolean = true, position: Int = 0) = DataSourceEntity(
        dataSourceId = id, databaseId = "db-$id", name = id, colorHex = color, titlePropId = "title", datePropId = "d",
        donePropId = null, doneType = null, doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null,
        enabled = enabled, position = position,
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
}
