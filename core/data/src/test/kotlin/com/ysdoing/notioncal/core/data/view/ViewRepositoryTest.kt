package com.ysdoing.notioncal.core.data.view

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.VIEW_ERROR_NOT_FOUND
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** VW-04·06 등록한 보기의 스냅샷을 위젯이 읽는 모양으로 돌려주고, 등록을 지우면 행도 함께 지운다. */
@RunWith(RobolectricTestRunner::class)
class ViewRepositoryTest {
    private lateinit var db: NotionCalDatabase
    private var widgetUpdates = 0

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries().build()
    }

    @After
    fun tearDown() = db.close()

    private fun repo() = ViewRepository(db.viewDao(), { widgetUpdates++ })

    /** 등록(upsert)한 뒤 스냅샷을 채운다. `replace`는 등록이 지워졌으면 되살리지 않기 때문이다. */
    private suspend fun seed(view: ViewSnapshotEntity, rows: List<ViewRowEntity>) {
        db.viewDao().upsert(view)
        db.viewDao().replace(view, rows)
    }

    @Test
    fun observeSplitsColumnsAndCellsInOrder() = runTest {
        val sep = VIEW_CELL_SEPARATOR
        seed(
            ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = "이름${sep}분류", syncedAt = 5, lastError = "Network"),
            listOf(ViewRowEntity("v1", 1, "p2", "u2", "둘째${sep}"), ViewRowEntity("v1", 0, "p1", "u1", "첫째${sep}집")),
        )
        val board = repo().observe("v1").first()!!
        assertEquals(listOf("이름", "분류"), board.columns)
        assertEquals(listOf("p1", "p2"), board.rows.map { it.pageId })
        assertEquals(listOf("첫째", "집"), board.rows[0].cells)
        assertEquals(listOf("둘째", ""), board.rows[1].cells) // 빈 값도 열 자리를 지킨다
        assertEquals("Network", board.lastError)
    }

    @Test
    fun takenDatesAreTheFirstDateColumnsRowDatesPlusNewItemsStillWaitingToBeSent() = runTest {
        // Phase 11h 하루 한 줄: 이미 받은 행의 날짜와, 노션에 아직 만들러 보내지 않은 새 항목의 날짜를 함께 안다.
        val sep = VIEW_CELL_SEPARATOR
        val columns = listOf(ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date"), ViewColumn("e", "마감", "date"))
        fun day(iso: String) = kotlinx.serialization.json.JsonObject(mapOf("start" to kotlinx.serialization.json.JsonPrimitive(iso)))
        seed(
            ViewSnapshotEntity("v1", "ds", "습관", "table", columns = "이름", syncedAt = 5, columnInfo = ViewJson.encodeColumns(columns)),
            listOf(
                ViewRowEntity("v1", 0, "p1", "u1", "a${sep}", ViewJson.encodeTyped(mapOf("d" to day("2026-11-04"), "e" to day("2026-12-25")))),
                ViewRowEntity("v1", 1, "p2", "u2", "b${sep}", ViewJson.encodeTyped(mapOf("d" to day("2026-11-03T09:00:00+09:00")))), // 시각이 든 날짜는 그날로
                ViewRowEntity("v1", 2, "p3", "u3", "c${sep}", ""), // 날짜가 없는 행
            ),
        )
        val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-05T00:00:00Z"), java.time.ZoneOffset.UTC)
        ViewWriter(db.viewDao(), db.eventDao(), { }, { }, clock).create("v1", "오늘", java.time.LocalDate.of(2026, 11, 5))
        val board = repo().observe("v1").first()!!
        // 첫 날짜 열(d)만 센다(마감 e는 새 항목의 날짜가 아니다)
        assertEquals(setOf("2026-11-04", "2026-11-03", "2026-11-05"), board.takenDates().map { it.toString() }.toSet())
        assertEquals(setOf(java.time.LocalDate.of(2026, 11, 5)), board.pendingCreateDates)
    }

    @Test
    fun aViewWithoutADateColumnHasNoTakenDays() = runTest {
        seed(ViewSnapshotEntity("v1", "ds", "메모", "table", columns = "이름", syncedAt = 5, columnInfo = ViewJson.encodeColumns(listOf(ViewColumn("t", "이름", "title")))), emptyList())
        assertEquals(emptySet<java.time.LocalDate>(), repo().observe("v1").first()!!.takenDates())
    }

    @Test
    fun theBoardKnowsWhichWritesAreStillInTheirUndoWindow() = runTest {
        // Phase 11c: 지연 중인 쓰기의 전송 시각을 위젯이 알아 '되돌리기' 줄을 그린다. 지난 시각은 세지 않는다.
        val sep = VIEW_CELL_SEPARATOR
        seed(ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = "이름${sep}완료", syncedAt = 5, columnInfo = ViewJson.encodeColumns(listOf(ViewColumn("t", "이름", "title"), ViewColumn("dn", "완료", "checkbox")))), listOf(ViewRowEntity("v1", 0, "p1", "u1", "첫째${sep}", ViewJson.encodeTyped(mapOf("dn" to kotlinx.serialization.json.JsonPrimitive(false))))))
        val clock = java.time.Clock.fixed(java.time.Instant.parse("2026-11-04T00:00:00Z"), java.time.ZoneOffset.UTC)
        ViewWriter(db.viewDao(), db.eventDao(), { }, { }, clock).toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        val board = repo().observe("v1").first()!!
        assertEquals(listOf(clock.millis() + 4_000), board.pendingSendAfter)
        assertEquals(setOf("p1"), board.pendingPageIds) // 바꾸고 아직 안 보낸 행(필터에서 빠지는 중인지 가린다)
        assertEquals(1, board.undoableAt(clock.millis() + 3_999))
        assertEquals(0, board.undoableAt(clock.millis() + 4_000)) // 시각이 되면 보낼 쓰기라 되돌릴 수 없다
    }

    @Test
    fun rowsExposeTheirTypedValuesAndTheViewItsConfigAndCompleteness() = runTest {
        // Phase 11a: 칩 색·보드 그룹은 글자가 아니라 원래 값(옵션 ID)과 보기 설정이 필요하다.
        val typed = ViewJson.encodeTyped(mapOf("st" to kotlinx.serialization.json.JsonPrimitive("o2"), "dn" to kotlinx.serialization.json.JsonPrimitive(true)))
        seed(
            ViewSnapshotEntity("v1", "ds", "보드", "board", columns = "이름", syncedAt = 5, viewConfig = """{"group_by":{"property_id":"st"}}""", rowsComplete = false),
            listOf(ViewRowEntity("v1", 0, "p1", "u1", "첫째", typed), ViewRowEntity("v1", 1, "p2", "u2", "둘째")),
        )
        val board = repo().observe("v1").first()!!
        assertEquals(mapOf("st" to kotlinx.serialization.json.JsonPrimitive("o2"), "dn" to kotlinx.serialization.json.JsonPrimitive(true)), board.rows[0].typed)
        assertEquals(emptyMap<String, kotlinx.serialization.json.JsonElement>(), board.rows[1].typed) // 값을 모르면 빈 맵
        assertEquals(listOf("""{"group_by":{"property_id":"st"}}""", false), listOf(board.viewConfig, board.rowsComplete))
        assertEquals("", board.filterJson)
    }

    @Test
    fun unknownViewIsNull() = runTest { assertNull(repo().observe("nope").first()) }

    @Test
    fun onlyNotFoundErrorMarksTheViewAsGone() = runTest {
        // VW-06: 노션이 404로 답한 보기만 '찾을 수 없음'이다. 네트워크 실패 같은 일시 오류는 아니다.
        db.viewDao().upsert(ViewSnapshotEntity("gone", "ds", "지워짐", "table", lastError = VIEW_ERROR_NOT_FOUND))
        db.viewDao().upsert(ViewSnapshotEntity("flaky", "ds", "불안정", "table", lastError = "Network"))
        db.viewDao().upsert(ViewSnapshotEntity("fine", "ds", "정상", "table"))
        assertTrue(repo().observe("gone").first()!!.notFound)
        assertFalse(repo().observe("flaky").first()!!.notFound)
        assertFalse(repo().observe("fine").first()!!.notFound)
    }

    @Test
    fun removeDropsRowsAndRequestsWidgetUpdate() = runTest {
        seed(ViewSnapshotEntity("v1", "ds", "할 일", "table"), listOf(ViewRowEntity("v1", 0, "p1", "u1", "a")))
        repo().remove("v1")
        assertEquals(emptyList<String>(), db.viewDao().all().map { it.viewId })
        assertEquals(0, db.viewDao().rows("v1").size)
        assertEquals(1, widgetUpdates)
    }

    @Test
    fun boardKnowsWhichColumnsCanBeTapped() = runTest {
        val sep = VIEW_CELL_SEPARATOR
        val info = ViewJson.encodeColumns(
            listOf(ViewColumn("t", "이름", "title"), ViewColumn("dn", "완료", "checkbox"), ViewColumn("pg", "쪽수", "number"), ViewColumn("st", "상태", "status", listOf(ViewChoice("a", "하나"), ViewChoice("b", "둘")))),
        )
        seed(
            ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = listOf("이름", "완료", "쪽수", "상태", "메모").joinToString(sep), columnInfo = info),
            listOf(ViewRowEntity("v1", 0, "p1", "u", "a${sep}${sep}1${sep}x${sep}m")),
        )
        val board = repo().observe("v1").first()!!
        assertEquals(listOf(null, "checkbox", null, "status", null), (0..4).map { board.tappableAt(it)?.type }) // 제목·숫자·메모는 눌러 바꾸지 않는다
        assertEquals("dn", board.tappableAt(1)!!.id)
    }

    @Test
    fun boardKnowsWhetherItCanCreateAndHasADateColumn() = runTest {
        val withTitle = ViewJson.encodeColumns(listOf(ViewColumn("t", "이름", "title"), ViewColumn("d", "날짜", "date")))
        seed(ViewSnapshotEntity("v1", "ds", "a", "table", columns = "이름", columnInfo = withTitle), emptyList())
        seed(ViewSnapshotEntity("v2", "ds", "b", "table", columns = "이름"), emptyList()) // 아직 쓰기 정보를 받지 못함
        seed(ViewSnapshotEntity("v3", "ds", "c", "board", columns = "이름", columnInfo = ViewJson.encodeColumns(listOf(ViewColumn("t", "이름", "title")))), emptyList())
        assertEquals(listOf(true, false, true), listOf("v1", "v2", "v3").map { repo().observe(it).first()!!.canCreate })
        assertEquals(listOf(true, false, false), listOf("v1", "v2", "v3").map { repo().observe(it).first()!!.hasDateColumn })
    }
}
