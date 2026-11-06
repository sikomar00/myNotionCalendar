package com.ysdoing.notioncal.core.data.view

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Phase 12e RC-03 위젯 칸에서 바로 하는 해빗 체크: 그날 행이 있으면 토글, 없으면 첫 체크로 그날 페이지를 만들고(제목 'M월 d일'),
 * 아직 안 보낸 그날 페이지가 있으면 거기에 합쳐 하루 페이지는 1개만 만든다. 4초 안에 되돌릴 수 있다. 오늘은 2026-11-06.
 */
@RunWith(RobolectricTestRunner::class)
class ViewWriterHabitTest {
    private lateinit var db: NotionCalDatabase
    private val clock = Clock.fixed(Instant.parse("2026-11-06T00:00:00Z"), ZoneOffset.UTC)
    private val sep = VIEW_CELL_SEPARATOR
    private val today = LocalDate.of(2026, 11, 6)
    private val columns = listOf(
        ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date"), ViewColumn("c1", "운동", "checkbox"), ViewColumn("c2", "독서", "checkbox"),
        ViewColumn("m", "메모", "rich_text"),
    )
    private val snapshot = ViewSnapshotEntity("v1", "ds", "습관", "table", columns = "이름${sep}날짜${sep}운동${sep}독서", syncedAt = 1, columnInfo = ViewJson.encodeColumns(columns))

    private fun dateValue(iso: String): JsonElement = JsonObject(mapOf("start" to JsonPrimitive(iso)))

    private suspend fun seed(vararg rows: Triple<String, String, Pair<Boolean, Boolean>>) {
        db.viewDao().upsert(snapshot)
        db.viewDao().replace(
            snapshot,
            rows.mapIndexed { i, (id, day, checks) ->
                val typed = mapOf("d" to dateValue(day), "c1" to JsonPrimitive(checks.first), "c2" to JsonPrimitive(checks.second))
                ViewRowEntity("v1", i, id, "u$i", listOf(id, "", "", "").joinToString(sep), ViewJson.encodeTyped(typed))
            },
        )
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val writer get() = ViewWriter(db.viewDao(), db.eventDao(), { }, { }, clock)

    private suspend fun creates() = db.viewDao().pendingCreates("v1").map { ViewJson.parseCreate(it.value)!! }

    private suspend fun checked(pageId: String, prop: String) = (ViewJson.typed(db.viewDao().row("v1", pageId)!!.typed)[prop] as JsonPrimitive).content.toBoolean()

    @Test
    fun aFirstCheckOnADayWithoutARowCreatesThatDaysPageWithTheHabitTicked() = runTest {
        seed(Triple("p1", "2026-11-05", false to false))
        assertTrue(writer.checkHabit("v1", "c1", today))
        val create = creates().single()
        assertEquals(listOf("11월 6일", "2026-11-06"), listOf(create.title, create.date))
        assertEquals(mapOf<String, JsonElement>("c1" to JsonPrimitive(true)), create.props)
        assertTrue("4초 지연으로 쌓는다", db.viewDao().firstWrite()!!.sendAfter > 0)
    }

    @Test
    fun aSecondHabitOnTheSameUnsentDayMergesIntoTheSamePageAndNeverCreatesTwo() = runTest {
        seed()
        writer.checkHabit("v1", "c1", today)
        writer.checkHabit("v1", "c2", today)
        assertEquals(setOf("c1", "c2"), creates().single().props.keys) // 하루 페이지 1개만
        // 같은 습관을 다시 누르면 체크가 풀리고, 모두 풀리면 자동 제목의 새 항목은 사라진다
        writer.checkHabit("v1", "c1", today)
        assertEquals(setOf("c2"), creates().single().props.keys)
        writer.checkHabit("v1", "c2", today)
        assertEquals(0, creates().size)
    }

    @Test
    fun aCustomTitledPendingPageIsKeptEvenWhenAllHabitsAreUnchecked() = runTest {
        seed()
        writer.create("v1", "내가 쓴 제목", today, mapOf("c1" to JsonPrimitive(true)))
        writer.checkHabit("v1", "c1", today)
        val create = creates().single()
        assertEquals("내가 쓴 제목", create.title)
        assertEquals(emptyMap<String, JsonElement>(), create.props)
    }

    @Test
    fun anExistingRowForThatDayIsToggledNotRecreated() = runTest {
        seed(Triple("today", "2026-11-06", false to false), Triple("yesterday", "2026-11-05", true to false))
        assertTrue(writer.checkHabit("v1", "c1", today))
        assertTrue(checked("today", "c1"))
        assertEquals(0, creates().size)
        assertTrue(writer.checkHabit("v1", "c1", today.minusDays(1))) // 지난날 칸도 같은 길(체크 해제)
        assertFalse(checked("yesterday", "c1"))
    }

    @Test
    fun theBoardShowsAQueuedPageAsCheckedAndTheWholeTapCanBeUndoneInsideTheWindow() = runTest {
        seed()
        writer.checkHabit("v1", "c1", today)
        val board = ViewRepository(db.viewDao()) { }.observe("v1").first()!!
        assertEquals(mapOf(today to setOf("c1")), board.pendingCreateChecks)
        assertEquals(1, writer.undoPending("v1")) // 4초 안이면 새 항목째 지운다
        assertEquals(0, creates().size)
    }

    @Test
    fun aViewWithoutADateColumnOrThatCheckboxCannotBeChecked() = runTest {
        seed()
        assertFalse(writer.checkHabit("v1", "m", today)) // 체크박스가 아니다
        assertFalse(writer.checkHabit("v1", "zzz", today))
        assertFalse(writer.checkHabit("gone", "c1", today)) // 보기가 없다
        db.viewDao().upsert(snapshot.copy(columnInfo = ViewJson.encodeColumns(columns.filter { it.type != "date" })))
        assertFalse(writer.checkHabit("v1", "c1", today))
        assertEquals(0, creates().size)
    }
}
