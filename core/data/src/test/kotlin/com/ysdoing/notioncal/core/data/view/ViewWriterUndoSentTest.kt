package com.ysdoing.notioncal.core.data.view

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.SentWriteEntity
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/**
 * Phase 12c IX-05 후반: 이미 보낸 변경 되돌리기. 가장 최근 묶음만, 창(2분) 안에서, 칸이 아직 보낸 값일 때만 반대 쓰기를 쌓고
 * 기준값은 보낸 값이다. 위젯 보드는 아직 보낸 값인 기록만 '되돌릴 수 있는 것'으로 센다.
 */
@RunWith(RobolectricTestRunner::class)
class ViewWriterUndoSentTest {
    private lateinit var db: NotionCalDatabase
    private val now = Instant.parse("2026-11-03T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private val sep = VIEW_CELL_SEPARATOR
    private val columns = listOf(
        ViewColumn("t", "Name", "title"),
        ViewColumn("dn", "Done", "checkbox"),
        ViewColumn("st", "Status", "status", listOf(ViewChoice("o1", "할 일", "To-do"), ViewChoice("o3", "완료", "Complete"))),
        ViewColumn("pg", "Pages", "number"),
    )
    private val snapshot = ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = "Name$sep" + "Done", syncedAt = 1, columnInfo = ViewJson.encodeColumns(columns))

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java).allowMainThreadQueries().build()
        db.viewDao().upsert(snapshot)
        // 보낸 뒤의 로컬 행: 완료 체크, 쪽수 40, 상태 완료(o3), 분류 없음
        val typed = ViewJson.encodeTyped(mapOf("dn" to JsonPrimitive(true), "pg" to JsonPrimitive(40.0), "st" to JsonPrimitive("o3")))
        db.viewDao().replace(snapshot, listOf(ViewRowEntity("v1", 0, "p1", "u1", "첫째${sep}true", typed)))
    }

    @After
    fun tearDown() = db.close()

    private val writer get() = ViewWriter(db.viewDao(), db.eventDao(), { }, { }, clock)

    private suspend fun sentLog(prop: String, type: String, prior: String?, sentValue: String?, ago: Long = 10_000, batch: Long = 0) =
        db.viewDao().insertSent(SentWriteEntity(viewId = "v1", pageId = "p1", propId = prop, propType = type, priorValue = prior, sentValue = sentValue, sentAt = now.toEpochMilli() - ago, batchId = batch))

    private fun queued(): List<List<Any?>> = db.query("SELECT propId, value, baseValue, sendAfter FROM view_writes ORDER BY id", null).use { c ->
        generateSequence { if (c.moveToNext()) listOf(c.getString(0), c.getString(1), c.getString(2), c.getLong(3)) else null }.toList()
    }

    @Test
    fun undoingWritesThePriorValueAgainstTheSentValueRightAwayAndDropsTheLog() = runTest {
        sentLog("pg", "number", prior = "12.0", sentValue = "40.0")
        assertEquals(1, writer.undoSent("v1"))
        assertEquals(listOf(listOf("pg", "12.0", "40.0", 0L)), queued()) // 지연 없이, 기준값 = 보낸 값
        assertEquals(emptyList<Any>(), db.viewDao().sentSince("v1", 0))
        assertEquals(12.0, (ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)["pg"] as JsonPrimitive).content.toDouble(), 0.0)
    }

    @Test
    fun onlyTheNewestBatchIsUndoneAtATime() = runTest {
        sentLog("pg", "number", "12.0", "40.0", ago = 60_000, batch = 0)
        sentLog("dn", "checkbox", "false", "true", ago = 5_000, batch = 7)
        sentLog("st", "status", "\"o1\"", "\"o3\"", ago = 5_000, batch = 7)
        assertEquals(2, writer.undoSent("v1"))
        assertEquals(setOf("dn", "st"), queued().map { it[0] }.toSet())
        assertEquals(1, db.viewDao().sentSince("v1", 0).size) // 이전 묶음은 남아 다음에 되돌릴 수 있다
        assertEquals("pg", db.viewDao().sentSince("v1", 0).single().propId)
    }

    @Test
    fun anOldLogOrACellChangedSinceIsLeftAlone() = runTest {
        sentLog("pg", "number", "12.0", "40.0", ago = ViewWriter.SENT_UNDO_WINDOW_MS + 1)
        assertEquals(0, writer.undoSent("v1")) // 창이 지났다
        sentLog("dn", "checkbox", "false", "false") // 로컬은 true: 그 뒤에 사용자가 또 바꿨다
        assertEquals(0, writer.undoSent("v1"))
        assertTrue(queued().isEmpty())
    }

    @Test
    fun aStatusWithoutAPriorValueIsSkippedButANumberThatWasEmptyIsCleared() = runTest {
        sentLog("st", "status", null, "\"o3\"", batch = 3)
        sentLog("pg", "number", null, "40.0", batch = 3) // 비어 있던 숫자는 비운다
        assertEquals(1, writer.undoSent("v1"))
        assertEquals(listOf("pg"), queued().map { it[0] })
        val pages = ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)["pg"]
        assertTrue(pages == null || pages is kotlinx.serialization.json.JsonNull)
    }

    @Test
    fun undoPrefersDelayedWritesAndFallsBackToTheSentOnes() = runTest {
        sentLog("pg", "number", "12.0", "40.0")
        writer.set("v1", "p1", "dn", JsonPrimitive(false), ViewWriter.UNDO_WINDOW_MS) // 지연 중인 쓰기
        assertEquals(1, writer.undo("v1")) // 지연 중인 것을 되돌린다
        assertEquals(1, db.viewDao().sentSince("v1", 0).size) // 보낸 기록은 그대로
        assertEquals(1, writer.undo("v1")) // 이제 지연 중인 것이 없으니 보낸 것을 되돌린다
        assertEquals(emptyList<Any>(), db.viewDao().sentSince("v1", 0))
    }

    @Test
    fun theBoardCountsOnlySentChangesWhoseCellStillHasTheSentValueInsideTheWindow() = runTest {
        sentLog("pg", "number", "12.0", "40.0") // 로컬 40.0: 되돌릴 수 있다
        sentLog("dn", "checkbox", "false", "false") // 로컬 true: 그 뒤에 또 바뀌었다
        val board = ViewRepository(db.viewDao()) { }.observe("v1").first()!!
        assertEquals(1, board.sentUndoableAt(now.toEpochMilli()))
        assertEquals(0, board.sentUndoableAt(now.toEpochMilli() + ViewWriter.SENT_UNDO_WINDOW_MS)) // 창이 지나면 줄을 거둔다
        assertEquals(1, board.sentUndoableAt(now.toEpochMilli() + ViewWriter.SENT_UNDO_WINDOW_MS - 10_000)) // 보낸 지 110초
    }
}
