package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** MV-10 제목 검색: 부분 일치·대소문자·와일드카드 이스케이프·정렬·꺼 둔 DB 제외, 1,000건 100ms. */
@RunWith(RobolectricTestRunner::class)
class EventSearchTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "search-test.db"
    private val today = LocalDate.of(2026, 10, 14)
    private val db: NotionCalDatabase = run {
        context.deleteDatabase(name)
        Room.databaseBuilder(context, NotionCalDatabase::class.java, name).allowMainThreadQueries().build()
    }
    private val repo = EventRepository(db.eventDao())

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    private suspend fun source(id: String, enabled: Boolean = true) = db.dataSourceDao().upsert(
        DataSourceEntity(
            dataSourceId = id, databaseId = "db-$id", name = id, colorHex = "#0B6E99", titlePropId = "title",
            datePropId = "d", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
            colorPropId = null, sortPropId = null, enabled = enabled,
        ),
    )

    private fun event(id: String, title: String, start: String, end: String? = null, ds: String = "cal") = EventEntity(
        pageId = id, dataSourceId = ds, title = title, startDate = LocalDate.parse(start), endDate = end?.let(LocalDate::parse),
        startTime = null, endTime = null, isAllDay = true, done = false, colorKey = null, sortKey = null, url = "", lastEditedTime = 0,
    )

    private suspend fun search(q: String) = repo.search(q, today).map { it.pageId }

    @Test
    fun matchesAndOrders() = runTest {
        source("cal")
        source("off", enabled = false)
        db.eventDao().upsert(
            listOf(
                event("past-old", "Team meeting", "2026-09-01"),
                event("past-new", "team Meeting 회고", "2026-10-10"),
                event("ongoing", "팀 MEETING 워크숍", "2026-10-12", "2026-10-15"), // 오늘 걸쳐 있음 → 다가오는 쪽
                event("future-far", "meeting 준비", "2026-12-01"),
                event("future-near", "Meeting", "2026-10-20"),
                event("other", "점심", "2026-10-14"),
                event("hidden", "meeting(꺼 둔 DB)", "2026-10-16", ds = "off"),
            ),
        )
        assertEquals(listOf("ongoing", "future-near", "future-far", "past-new", "past-old"), search("meeting"))
        assertEquals(listOf("ongoing"), search("  워크숍 "))
        assertEquals(emptyList<String>(), search("   "))
        assertEquals(emptyList<String>(), search("없는말"))
    }

    @Test
    fun wildcardsAreLiteral() = runTest {
        source("cal")
        db.eventDao().upsert(
            listOf(
                event("pct", "달성률 100%", "2026-10-20"),
                event("num", "1000원", "2026-10-21"),
                event("under", "file_name", "2026-10-22"),
                event("plain", "filename", "2026-10-23"),
                event("slash", "a\\b", "2026-10-24"),
            ),
        )
        assertEquals(listOf("pct"), search("0%"))
        assertEquals(listOf("under"), search("_"))
        assertEquals(listOf("under"), search("e_n"))
        assertEquals(listOf("slash"), search("\\"))
    }

    @Test
    fun limitAndSpeedWithThousandRows() = runTest {
        source("cal")
        db.eventDao().upsert(
            (0 until 1_000).map { n -> event("e$n", if (n % 2 == 0) "운동 $n" else "회의 $n", today.plusDays(n - 500L).toString()) },
        )
        search("운동") // DB 열기·준비문 비용은 뺀다
        val timings = listOf("운동", "회의", "9", "없음").map { q ->
            val started = System.nanoTime()
            repo.search(q, today)
            (System.nanoTime() - started) / 1_000_000
        }
        assertEquals(EventRepository.SEARCH_LIMIT, search("운동").size)
        assertTrue("검색이 100ms를 넘었다: $timings", timings.all { it < 100 })
    }
}
