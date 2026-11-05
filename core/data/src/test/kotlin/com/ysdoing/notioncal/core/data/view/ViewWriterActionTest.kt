package com.ysdoing.notioncal.core.data.view

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
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
import java.time.ZoneOffset

/**
 * Phase 12c IX-07 액션 버튼의 쓰기: 변경마다 현재 값에서 새 값을 만들고(날짜는 시각·시간대를 지킨다), 같은 묶음 번호로 4초 뒤 보내며,
 * 그 안에 되돌릴 수 있다. 오늘은 2026-11-03(서울 09:00).
 */
@RunWith(RobolectricTestRunner::class)
class ViewWriterActionTest {
    private lateinit var db: NotionCalDatabase
    private val clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
    private val sep = VIEW_CELL_SEPARATOR
    private val columns = listOf(
        ViewColumn("t", "Name", "title"),
        ViewColumn("wh", "When", "date"),
        ViewColumn("dn", "Done", "checkbox"),
        ViewColumn("st", "Status", "status", listOf(ViewChoice("o1", "할 일", "To-do"), ViewChoice("o3", "완료", "Complete"))),
        ViewColumn("pg", "Pages", "number"),
    )
    private val snapshot = ViewSnapshotEntity(
        "v1", "ds", "할 일", "table", columns = listOf("Name", "When", "Done", "Status", "Pages").joinToString(sep), syncedAt = 1,
        columnInfo = ViewJson.encodeColumns(columns),
    )

    private fun date(start: String, end: String? = null): JsonElement =
        JsonObject(buildMap { put("start", JsonPrimitive(start)); end?.let { put("end", JsonPrimitive(it)) } })

    private suspend fun seed(on: JsonElement?) {
        db.openHelper.writableDatabase.execSQL("DELETE FROM view_writes") // 쌓인 쓰기가 있으면 행을 다시 받아 덮지 않는다
        val typed = buildMap<String, JsonElement> {
            on?.let { put("wh", it) }
            put("dn", JsonPrimitive(false)); put("st", JsonPrimitive("o1")); put("pg", JsonPrimitive(12.0))
        }
        db.viewDao().upsert(snapshot)
        db.viewDao().replace(snapshot, listOf(ViewRowEntity("v1", 0, "p1", "u1", listOf("첫째", "", "", "할 일", "12").joinToString(sep), ViewJson.encodeTyped(typed))))
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java).allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private val writer get() = ViewWriter(db.viewDao(), db.eventDao(), { }, { }, clock)

    private fun action(vararg changes: ActionChange) = ViewAction("a1", "v1", "액션", changes.toList())

    /** 큐에 쌓인 쓰기를 (속성, 값, 묶음 번호, 보낼 시각)으로. */
    private fun queued(): List<List<Any?>> = db.query("SELECT propId, value, batchId, sendAfter FROM view_writes ORDER BY id", null).use { c ->
        generateSequence { if (c.moveToNext()) listOf(c.getString(0), c.getString(1), c.getLong(2), c.getLong(3)) else null }.toList()
    }

    private suspend fun typedNow() = ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)

    @Test
    fun shiftingAnAllDayDateMovesItAndQueuesItDelayedUnderABatch() = runTest {
        seed(date("2026-11-04"))
        assertTrue(writer.applyAction("v1", "p1", action(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1))))
        assertEquals("2026-11-05", TypedDate.parse(typedNow()["wh"])!!.start)
        val now = clock.millis()
        assertEquals(listOf(listOf("wh", """{"start":"2026-11-05"}""", now, now + ViewWriter.UNDO_WINDOW_MS)), queued())
    }

    @Test
    fun shiftingATimedDateKeepsItsTimeAndAnEmptyDateStartsFromToday() = runTest {
        seed(date("2026-11-04T09:00:00.000+09:00", "2026-11-04T10:00:00.000+09:00"))
        writer.applyAction("v1", "p1", action(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1)))
        assertEquals(TypedDate("2026-11-05T09:00:00.000+09:00", "2026-11-05T10:00:00.000+09:00"), TypedDate.parse(typedNow()["wh"]))
        seed(null)
        writer.applyAction("v1", "p1", action(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1)))
        assertEquals("2026-11-04", TypedDate.parse(typedNow()["wh"])!!.start) // 오늘(11-03) + 1
    }

    @Test
    fun todayMovesTheStartToTodayAndKeepsTheTime() = runTest {
        seed(date("2026-12-24T18:30:00.000+09:00"))
        writer.applyAction("v1", "p1", action(ActionChange("wh", ActionOp.TODAY)))
        assertEquals("2026-11-03T18:30:00.000+09:00", TypedDate.parse(typedNow()["wh"])!!.start)
        seed(null)
        writer.applyAction("v1", "p1", action(ActionChange("wh", ActionOp.TODAY)))
        assertEquals("2026-11-03", TypedDate.parse(typedNow()["wh"])!!.start)
    }

    @Test
    fun anActionWithSeveralChangesWritesThemAllUnderOneBatch() = runTest {
        seed(date("2026-11-04"))
        val done = action(ActionChange("st", ActionOp.SET, "o3"), ActionChange("dn", ActionOp.SET, "true"), ActionChange("pg", ActionOp.SET, "40"))
        assertTrue(writer.applyAction("v1", "p1", done))
        assertEquals(listOf("o3", true, 40.0), typedNow().let { listOf((it["st"] as JsonPrimitive).content, (it["dn"] as JsonPrimitive).content.toBoolean(), (it["pg"] as JsonPrimitive).content.toDouble()) })
        assertEquals(setOf(clock.millis()), queued().map { it[2] }.toSet())
        assertEquals(3, queued().size)
    }

    @Test
    fun aChangeThatDoesNotFitTheColumnFailsTheActionButOthersStillApply() = runTest {
        seed(date("2026-11-04"))
        val partial = action(ActionChange("st", ActionOp.SET, "gone"), ActionChange("dn", ActionOp.SET, "true"))
        assertFalse(writer.applyAction("v1", "p1", partial)) // 없는 옵션
        assertEquals(listOf("dn"), queued().map { it[0] })
        assertFalse(writer.applyAction("v1", "p1", action(ActionChange("zzz", ActionOp.SET, "x")))) // 없는 속성
        assertFalse(writer.applyAction("v1", "p1", action(ActionChange("dn", ActionOp.SHIFT_DAYS, days = 1)))) // 날짜가 아닌 속성
        assertFalse(writer.applyAction("v1", "gone", action(ActionChange("dn", ActionOp.SET, "true")))) // 없는 행
    }

    @Test
    fun theWholeActionCanBeUndoneInsideTheWindow() = runTest {
        seed(date("2026-11-04"))
        writer.applyAction("v1", "p1", action(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 3), ActionChange("dn", ActionOp.SET, "true")))
        assertEquals(2, writer.undoPending("v1"))
        val typed = typedNow()
        assertEquals("2026-11-04", TypedDate.parse(typed["wh"])!!.start)
        assertEquals(false, (typed["dn"] as JsonPrimitive).content.toBoolean())
        assertTrue(queued().isEmpty())
    }

    @Test
    fun repeatingARelativeActionBuildsOnTheCurrentValueNotTheOriginal() = runTest {
        seed(date("2026-11-04"))
        val tomorrow = action(ActionChange("wh", ActionOp.SHIFT_DAYS, days = 1))
        writer.applyAction("v1", "p1", tomorrow)
        writer.applyAction("v1", "p1", tomorrow)
        assertEquals("2026-11-06", TypedDate.parse(typedNow()["wh"])!!.start)
        assertEquals(1, queued().size) // 같은 속성은 마지막 값 하나만 남는다
    }
}
