package com.ysdoing.notioncal.core.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.calendar.GRID_DAYS
import com.ysdoing.notioncal.core.data.calendar.gridStart
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * PF-08: DB 2개 × 2,000건(약 2년치)에서도 월 전환 조회(6주 그리드 범위)가 100ms 안에 끝난다.
 * 실제 파일 DB에 인덱스(startDate, dataSourceId+startDate)를 그대로 쓴다. PC 기준 회귀 감시용이며 실기기 수치는 metrics.md에 둔다.
 */
@RunWith(RobolectricTestRunner::class)
class LargeDataQueryTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()
    private val name = "large-data-test.db"
    private lateinit var db: NotionCalDatabase

    @Before
    fun setUp() {
        context.deleteDatabase(name)
        db = Room.databaseBuilder(context, NotionCalDatabase::class.java, name).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() {
        db.close()
        context.deleteDatabase(name)
    }

    @Test
    fun monthSwitchQueryStaysUnder100ms() = runTest {
        val first = LocalDate.of(2025, 11, 1)
        listOf("ds-a", "ds-b").forEachIndexed { i, id ->
            db.dataSourceDao().upsert(
                DataSourceEntity(
                    dataSourceId = id, databaseId = "db-$id", name = id, colorHex = "#0B6E99", titlePropId = "title",
                    datePropId = "d", donePropId = null, doneType = null, doneOption = null, undoneOption = null,
                    colorPropId = null, sortPropId = null, position = i,
                ),
            )
            db.eventDao().upsert(
                (0 until PER_SOURCE).map { n ->
                    val start = first.plusDays((n * 730L) / PER_SOURCE)
                    EventEntity(
                        pageId = "$id-$n", dataSourceId = id, title = "일정 $n", startDate = start,
                        endDate = if (n % 10 == 0) start.plusDays(2) else null, startTime = null, endTime = null,
                        isAllDay = true, done = n % 3 == 0, colorKey = null, sortKey = null, url = "", lastEditedTime = 0,
                    )
                },
            )
        }

        val repo = EventRepository(db.eventDao())
        val months = (0 until 12).map { YearMonth.of(2026, 1).plusMonths(it.toLong()) }
        suspend fun load(month: YearMonth): Int {
            val from = gridStart(month, DayOfWeek.SUNDAY)
            return repo.observeRange(from, from.plusDays(GRID_DAYS - 1)).first().size
        }
        load(months.first()) // 첫 쿼리는 DB 열기·준비문 비용이 섞이므로 뺀다

        val timings = months.map { month ->
            val started = System.nanoTime()
            val count = load(month)
            assertTrue("$month 일정이 비어 있으면 측정이 무의미하다", count > 0)
            (System.nanoTime() - started) / 1_000_000.0
        }.sorted()
        val median = timings[timings.size / 2]
        println("PF-08 월 전환 조회(4,000건): 중앙값 ${"%.1f".format(median)}ms, 최대 ${"%.1f".format(timings.last())}ms")
        assertTrue("월 전환 조회 중앙값 ${median}ms", median < 100.0)
        assertTrue(File(context.getDatabasePath(name).path).exists())
    }

    private companion object {
        const val PER_SOURCE = 2_000
    }
}
