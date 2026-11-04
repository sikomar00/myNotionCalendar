package com.ysdoing.notioncal.core.data.view

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.db.EventEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.VIEW_CELL_SEPARATOR
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** M3 보기 행 쓰기: 위젯에 바로 반영하고(낙관), 보내지 않은 바꿈 큐에 같은 속성은 마지막 값 하나만 남긴다. */
@RunWith(RobolectricTestRunner::class)
class ViewWriterTest {
    private lateinit var db: NotionCalDatabase
    private var widgetUpdates = 0
    private var requests = 0
    private val clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
    private val sep = VIEW_CELL_SEPARATOR

    private val columns = listOf(
        ViewColumn("t", "Name", "title"),
        ViewColumn("wh", "When", "date"),
        ViewColumn("dn", "Done", "checkbox"),
        ViewColumn(
            "st", "Status", "status",
            listOf(ViewChoice("o1", "할 일", "To-do"), ViewChoice("o2", "진행 중", "In progress"), ViewChoice("o3", "완료", "Complete")),
        ),
        ViewColumn("pg", "Pages", "number"),
    )
    private val snapshot = ViewSnapshotEntity(
        "v1", "ds", "할 일", "table", columns = listOf("Name", "Done", "Status", "Pages").joinToString(sep), syncedAt = 1,
        columnInfo = ViewJson.encodeColumns(columns),
    )
    private val row = ViewRowEntity(
        "v1", 0, "p1", "u1", listOf("첫째", "", "할 일", "12").joinToString(sep),
        ViewJson.encodeTyped(mapOf("dn" to JsonPrimitive(false), "st" to JsonPrimitive("o1"), "pg" to JsonPrimitive(12.0))),
    )

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries().build()
        db.viewDao().upsert(snapshot)
        db.viewDao().replace(snapshot, listOf(row))
    }

    @After
    fun tearDown() = db.close()

    private fun writer() = ViewWriter(db.viewDao(), db.eventDao(), { widgetUpdates++ }, { requests++ }, clock)

    /** 지연 요청을 따로 기록하는 요청기(request()는 즉시, requestAfter()는 지연). */
    private val delays = mutableListOf<Long>()
    private fun delayedWriter(at: Clock = clock) = ViewWriter(
        db.viewDao(), db.eventDao(), { widgetUpdates++ },
        object : OutboxRequester {
            override fun request() {
                requests++
            }

            override fun requestAfter(delayMs: Long) {
                delays += delayMs
            }
        },
        at,
    )

    private suspend fun cells() = db.viewDao().row("v1", "p1")!!.cells.split(sep)

    @Test
    fun toggleChangesTheRowAtOnceAndQueuesOneWrite() = runTest {
        assertTrue(writer().toggle("v1", "p1", "dn"))
        assertEquals(listOf("첫째", "✓", "할 일", "12"), cells())
        assertEquals(JsonPrimitive(true), ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)["dn"])
        val write = db.viewDao().firstWrite()!!
        assertEquals(listOf("dn", "checkbox", "true", "ds", "p1"), listOf(write.propId, write.propType, write.value, write.dataSourceId, write.pageId))
        assertEquals(clock.millis(), write.createdAt)
        assertEquals(1, widgetUpdates) // 위젯이 바로 다시 그린다
        assertEquals(1, requests) // 보내 달라는 요청
    }

    @Test
    fun togglingTwiceKeepsOnlyTheLastValue() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().toggle("v1", "p1", "dn")
        assertEquals("", cells()[1]) // 다시 해제
        assertEquals(1, db.viewDao().pendingWriteCount()) // 같은 속성은 마지막 값 하나만
        assertEquals("false", db.viewDao().firstWrite()!!.value)
    }

    @Test
    fun cycleWalksThroughStatusOptionsAndWraps() = runTest {
        val seen = (1..3).map {
            writer().cycle("v1", "p1", "st")
            cells()[2]
        }
        assertEquals(listOf("진행 중", "완료", "할 일"), seen)
        assertEquals("\"o1\"", db.viewDao().firstWrite()!!.value) // 선택지 ID를 JSON 글자로
    }

    @Test
    fun addChangesANumberAndShowsItLikeTheReadPath() = runTest {
        writer().add("v1", "p1", "pg", 1.0)
        assertEquals("13", cells()[3])
        writer().add("v1", "p1", "pg", -21.5)
        assertEquals("-8.5", cells()[3])
        assertEquals(1, db.viewDao().pendingWriteCount())
    }

    @Test
    fun refusesWhatTheColumnCannotDo() = runTest {
        assertFalse(writer().toggle("v1", "p1", "st")) // 체크박스가 아니다
        assertFalse(writer().cycle("v1", "p1", "dn")) // 선택지가 없다
        assertFalse(writer().toggle("v1", "p1", "missing")) // 보기에 없는 속성
        assertFalse(writer().toggle("v1", "no-row", "dn")) // 없는 행
        assertFalse(writer().toggle("no-view", "p1", "dn")) // 없는 보기
        assertNull(db.viewDao().firstWrite())
        assertEquals(0, widgetUpdates + requests)
    }

    @Test
    fun aPendingChangeIsNotOverwrittenByTheNextRead() = runTest {
        writer().toggle("v1", "p1", "dn")
        // 노션의 이전 값(체크 해제)을 받아도 아직 보내지 않은 체크를 되돌리지 않는다.
        db.viewDao().replace(snapshot.copy(name = "바뀜"), listOf(row))
        assertEquals("할 일", db.viewDao().snapshot("v1")!!.name)
        assertEquals("✓", cells()[1])
    }

    @Test
    fun removingTheViewDropsItsPendingWrites() = runTest {
        writer().toggle("v1", "p1", "dn")
        db.viewDao().remove("v1")
        assertEquals(0, db.viewDao().pendingWriteCount())
    }

    @Test
    fun manyQuickTapsAreNotLost() = runTest {
        // 읽고-계산하고-쓰기가 한 트랜잭션이라, 거의 동시에 누른 20번이 모두 세어져 짝수번이면 처음 값으로 돌아온다.
        (1..20).map { async { writer().toggle("v1", "p1", "dn") } }.awaitAll()
        assertEquals("", cells()[1])
        assertEquals("false", db.viewDao().firstWrite()!!.value)
        assertEquals(1, db.viewDao().pendingWriteCount())
    }

    @Test
    fun addingTenthsDoesNotDrift() = runTest {
        repeat(3) { writer().add("v1", "p1", "pg", 0.1) }
        assertEquals("12.3", cells()[3]) // 12 + 0.1 + 0.1 + 0.1을 부동소수점으로 더하면 12.299999999999999
        assertEquals("12.3", db.viewDao().firstWrite()!!.value)
    }

    @Test
    fun aChangeRecordsWhenTheWidgetWasLastTouched() = runTest {
        writer().toggle("v1", "p1", "dn")
        assertEquals(clock.millis(), db.viewDao().snapshot("v1")!!.lastWriteAt)
    }

    @Test
    fun aReadStartedBeforeTheChangeDoesNotOverwriteIt() = runTest {
        val readStartedAt = clock.millis() - 1
        writer().toggle("v1", "p1", "dn")
        db.viewDao().deleteWriteById(db.viewDao().firstWrite()!!.id) // 이미 보냈다
        // 바꾸기 전에 시작한 읽기(노션의 이전 값)가 끝나도 위젯의 체크를 덮지 않는다.
        db.viewDao().replace(snapshot.copy(name = "바뀜"), listOf(row), fetchedAt = readStartedAt)
        assertEquals("할 일", db.viewDao().snapshot("v1")!!.name)
        assertEquals("✓", cells()[1])
        // 바꾼 뒤에 시작한 읽기는 받아들인다.
        db.viewDao().replace(snapshot.copy(name = "바뀜"), listOf(row), fetchedAt = clock.millis() + 1)
        assertEquals("바뀜", db.viewDao().snapshot("v1")!!.name)
        assertEquals(clock.millis(), db.viewDao().snapshot("v1")!!.lastWriteAt) // 읽기가 바꾼 시각을 지우지 않는다
    }

    // --- 새 항목 만들기

    @Test
    fun createQueuesANewItemWithTheTitleAndTheDateColumn() = runTest {
        assertTrue(writer().create("v1", "  장보기  ", LocalDate.of(2026, 11, 4)))
        val write = db.viewDao().firstWrite()!!
        assertEquals(listOf("create", "t", "ds"), listOf(write.propType, write.propId, write.dataSourceId))
        assertTrue(write.pageId.startsWith("new-"))
        assertEquals(ViewCreate("장보기", "2026-11-04", "wh"), ViewJson.parseCreate(write.value)) // 제목은 앞뒤 공백을 뺀다
        assertEquals(1, requests)
        assertEquals(row, db.viewDao().row("v1", "p1")) // 로컬 행은 건드리지 않는다(노션이 만든 뒤 보기를 다시 받아 채운다)
    }

    @Test
    fun createWithoutADateLeavesTheDateOut() = runTest {
        writer().create("v1", "메모", null)
        assertEquals(ViewCreate("메모", null, null), ViewJson.parseCreate(db.viewDao().firstWrite()!!.value))
    }

    @Test
    fun createRefusesABlankTitleOrAViewWithoutTitleInfo() = runTest {
        assertFalse(writer().create("v1", "   ", null))
        db.viewDao().upsert(ViewSnapshotEntity("v2", "ds", "정보 없음", "table"))
        assertFalse(writer().create("v2", "제목", null)) // 아직 쓰기 정보를 받지 못한 보기
        assertFalse(writer().create("none", "제목", null))
        assertNull(db.viewDao().firstWrite())
        assertEquals(0, requests)
    }

    @Test
    fun twoCreatesAreBothKept() = runTest {
        writer().create("v1", "하나", null)
        writer().create("v1", "둘", null)
        assertEquals(2, db.viewDao().pendingWriteCount()) // 같은 속성 바꿈과 달리 합치지 않는다
        assertEquals(clock.millis(), db.viewDao().snapshot("v1")!!.lastWriteAt)
    }

    @Test
    fun createDoesNotQueueForAViewThatWasJustRemoved() = runTest {
        db.viewDao().remove("v1")
        assertFalse(writer().create("v1", "장보기", null))
        assertEquals(0, db.viewDao().pendingWriteCount()) // 등록을 지운 보기의 바꿈은 노션에 가지 않는다
        assertEquals(0, requests)
    }

    // --- Phase 11c 전송 지연(IX-03·05): 즉시 동작은 sendAfter를 뒤로 두고 쌓는다. 연타는 같은 쓰기를 덮으며 시각을 뒤로 민다.

    @Test
    fun anInstantActionIsQueuedWithASendAfterAndAWakeUpAtThatTime() = runTest {
        assertTrue(delayedWriter().toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS))
        assertEquals(clock.millis() + 4_000, db.viewDao().firstWrite()!!.sendAfter)
        assertEquals(listOf(4_000L), delays) // 그 시각에 처리기를 깨워 달라는 요청
        assertEquals(0, requests) // 즉시 보내 달라는 요청은 하지 않는다
        assertEquals(1, widgetUpdates) // 위젯은 바로 다시 그린다(행은 이미 바뀌었다)
    }

    @Test
    fun aWriteWithoutADelayKeepsSendAfterZeroAndRequestsAtOnce() = runTest {
        delayedWriter().toggle("v1", "p1", "dn")
        assertEquals(0L, db.viewDao().firstWrite()!!.sendAfter)
        assertEquals(1, requests)
        assertEquals(emptyList<Long>(), delays)
    }

    @Test
    fun tenQuickTapsAreOneWriteWhoseSendAfterMovesBackEachTime() = runTest {
        repeat(10) { i ->
            delayedWriter(Clock.offset(clock, java.time.Duration.ofMillis(i * 100L))).toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        }
        assertEquals(1, db.viewDao().pendingWriteCount()) // 10연타 → 쓰기 1건(요청 1회)
        assertEquals(clock.millis() + 900 + 4_000, db.viewDao().firstWrite()!!.sendAfter)
        // 10번 뒤집으면 처음 값(false)으로 돌아오고, 기준값은 처음 노션 값(false) 그대로다.
        assertEquals(listOf("false", "false"), db.viewDao().firstWrite()!!.let { listOf(it.value, it.baseValue) })
    }

    @Test
    fun theEarliestFutureSendAfterIsKnownSoTheQueueCanWakeAgain() = runTest {
        delayedWriter().toggle("v1", "p1", "dn", sendDelayMs = 4_000)
        delayedWriter().cycle("v1", "p1", "st", sendDelayMs = 9_000)
        assertEquals(clock.millis() + 4_000, db.viewDao().earliestSendAfter(clock.millis()))
        assertNull(db.viewDao().earliestSendAfter(clock.millis() + 10_000)) // 모두 지났으면 없다
    }

    // --- Phase 11c 새 항목에 값 묶음(AD-01·05): 유효한 값만 싣는다.

    @Test
    fun createKeepsOnlyValidExtraValuesAndNeverTheTitleColumn() = runTest {
        val props = mapOf<String, kotlinx.serialization.json.JsonElement>(
            "st" to JsonPrimitive("o2"), // 맞다
            "dn" to JsonPrimitive("예"), // 체크박스에 글자: 거른다
            "zz" to JsonPrimitive(1), // 없는 속성: 거른다
            "t" to JsonPrimitive("다른 제목"), // 제목은 title 인자가 정한다
            "pg" to JsonPrimitive(3.0),
        )
        assertTrue(writer().create("v1", "점심", null, props))
        val spec = ViewJson.parseCreate(db.viewDao().firstWrite()!!.value)!!
        assertEquals(mapOf<String, kotlinx.serialization.json.JsonElement>("st" to JsonPrimitive("o2"), "pg" to JsonPrimitive(3.0)), spec.props)
    }

    @Test
    fun theDateArgumentWinsOverADateValueInTheProps() = runTest {
        val props = mapOf<String, kotlinx.serialization.json.JsonElement>("wh" to kotlinx.serialization.json.JsonObject(mapOf("start" to JsonPrimitive("2026-01-01"))))
        writer().create("v1", "점심", LocalDate.of(2026, 11, 4), props)
        val spec = ViewJson.parseCreate(db.viewDao().firstWrite()!!.value)!!
        assertEquals(listOf("2026-11-04", emptyMap<String, Any>()), listOf(spec.date, spec.props)) // 날짜는 한 군데로만 간다
    }

    // --- Phase 11c 되돌리기(IX-05): 지연 중인 쓰기를 지우고 행을 처음 값으로 복원한다. 이미 보낼 때가 된 쓰기는 건드리지 않는다.

    @Test
    fun undoRestoresTheRowsAndDropsTheDelayedWrites() = runTest {
        val w = delayedWriter()
        w.toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        w.cycle("v1", "p1", "st", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        widgetUpdates = 0
        assertEquals(2, w.undoPending("v1"))
        assertEquals(listOf("첫째", "", "할 일", "12"), cells()) // 완료 칸과 상태 칸이 처음으로
        val typed = ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)
        assertEquals(listOf(JsonPrimitive(false), JsonPrimitive("o1")), listOf(typed["dn"], typed["st"]))
        assertEquals(0, db.viewDao().pendingWriteCount()) // 노션에는 아무것도 가지 않는다
        assertEquals(1, widgetUpdates)
    }

    @Test
    fun undoGoesBackToTheValueBeforeTheFirstOfSeveralTaps() = runTest {
        repeat(3) { delayedWriter().toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS) } // 켬, 끔, 켬
        assertEquals(JsonPrimitive(true), ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)["dn"])
        delayedWriter().undoPending("v1")
        assertEquals(JsonPrimitive(false), ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)["dn"]) // 처음(노션에 있던) 값
        assertEquals(listOf("첫째", "", "할 일", "12"), cells())
    }

    @Test
    fun undoLeavesAWriteThatIsAlreadyDueAlone() = runTest {
        val w = delayedWriter()
        w.toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        w.cycle("v1", "p1", "st") // 지연 없이 바로 보낼 쓰기(시트 저장 같은)
        assertEquals(1, w.undoPending("v1"))
        assertEquals(listOf("st"), buildList { db.viewDao().firstWrite()?.let { add(it.propId) } }) // 남은 쓰기는 상태 하나
        assertEquals(JsonPrimitive("o2"), ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)["st"])
    }

    @Test
    fun undoOfAPendingCreateJustRemovesItsWrite() = runTest {
        val w = delayedWriter()
        assertTrue(w.create("v1", "장보기", null, sendDelayMs = ViewWriter.UNDO_WINDOW_MS))
        assertEquals(1, w.undoPending("v1"))
        assertEquals(0, db.viewDao().pendingWriteCount())
    }

    @Test
    fun undoAfterTheWindowDoesNothing() = runTest {
        delayedWriter().toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        val later = Clock.offset(clock, java.time.Duration.ofMillis(ViewWriter.UNDO_WINDOW_MS))
        assertEquals(0, delayedWriter(later).undoPending("v1")) // 4초가 지났다: 이제 보낼 쓰기다
        assertEquals(1, db.viewDao().pendingWriteCount())
        assertEquals(JsonPrimitive(true), ViewJson.typed(db.viewDao().row("v1", "p1")!!.typed)["dn"])
    }

    @Test
    fun undoOnlyTouchesTheGivenView() = runTest {
        val other = snapshot.copy(viewId = "v2")
        db.viewDao().upsert(other)
        db.viewDao().replace(other, listOf(row.copy(viewId = "v2")))
        delayedWriter().toggle("v2", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        assertEquals(0, delayedWriter().undoPending("v1"))
        assertEquals(1, db.viewDao().pendingWriteCount())
    }

    // --- Phase 11b ED-05 휴지통: 모든 보기에서 행을 바로 지우고 큐에 한 건을 쌓는다(영구 삭제가 아니라 `in_trash`).

    @Test
    fun trashRemovesTheRowFromEveryViewAtOnceAndQueuesOneWrite() = runTest {
        val other = snapshot.copy(viewId = "v2", name = "다른 보기")
        db.viewDao().upsert(other)
        db.viewDao().replace(other, listOf(row.copy(viewId = "v2")))
        assertTrue(writer().trash("v1", "p1"))
        assertNull(db.viewDao().row("v1", "p1"))
        assertNull(db.viewDao().row("v2", "p1")) // 같은 페이지는 다른 보기에서도 사라진다
        val write = db.viewDao().firstWrite()!!
        assertEquals(listOf("trash", "p1", "ds", "첫째", null), listOf(write.propType, write.pageId, write.dataSourceId, write.value, write.baseValue))
        assertEquals(1, db.viewDao().pendingWriteCount())
        assertEquals(clock.millis(), db.viewDao().snapshot("v1")!!.lastWriteAt)
        assertEquals(1, widgetUpdates)
        assertEquals(1, requests)
        assertFalse(writer().trash("v1", "p1")) // 이미 지운 행은 다시 쌓지 않는다
        assertEquals(1, db.viewDao().pendingWriteCount())
    }

    @Test
    fun trashDropsEarlierEditsOfThePage() = runTest {
        // 곧 휴지통으로 갈 페이지의 앞선 속성 바꿈은 노션에 보낼 이유가 없다(보내면 요청만 늘고, 거부돼도 제목이 비어 기록된다).
        writer().toggle("v1", "p1", "dn")
        writer().trash("v1", "p1")
        assertEquals(1, db.viewDao().pendingWriteCount())
        assertEquals("trash", db.viewDao().firstWrite()!!.propType)
    }

    @Test
    fun trashAlsoRemovesTheCalendarEventOfTheSamePage() = runTest {
        // 일정 DB를 보기로도 등록했으면 월 화면의 일정 행(events)도 바로 사라져야 한다(일정 시트의 삭제와 같다).
        db.eventDao().upsert(
            listOf(
                EventEntity(
                    pageId = "p1", dataSourceId = "ds", title = "첫째", startDate = LocalDate.of(2026, 11, 4), endDate = null, startTime = null, endTime = null,
                    isAllDay = true, done = false, colorKey = null, sortKey = null, url = "", lastEditedTime = 1,
                ),
            ),
        )
        writer().trash("v1", "p1")
        assertNull(db.eventDao().get("p1"))
    }

    @Test
    fun aPendingTrashKeepsThePageOutOfAnyViewThatIsRefreshedMeanwhile() = runTest {
        // 같은 페이지를 가진 다른 보기가 휴지통 전송 전에 새로 받아져도(노션에는 아직 있다) 그 행을 되살리지 않는다.
        val other = snapshot.copy(viewId = "v2", name = "다른 보기")
        db.viewDao().upsert(other)
        db.viewDao().replace(other, listOf(row.copy(viewId = "v2")))
        writer().trash("v1", "p1")
        db.viewDao().replace(other, listOf(row.copy(viewId = "v2"), row.copy(viewId = "v2", position = 1, pageId = "p2")))
        assertEquals(listOf("p2"), db.viewDao().rows("v2").map { it.pageId })
    }

    @Test
    fun theTrashRecordsTheTitleFromTheTitleColumnEvenWhenItIsNotFirst() = runTest {
        val reordered = snapshot.copy(columns = listOf("Done", "Name").joinToString(sep))
        db.viewDao().upsert(reordered)
        db.viewDao().replace(reordered, listOf(row.copy(cells = listOf("", "둘째").joinToString(sep))))
        writer().trash("v1", "p1")
        assertEquals("둘째", db.viewDao().firstWrite()!!.value)
    }

    @Test
    fun trashRefusesAViewOrARowThatIsGone() = runTest {
        assertFalse(writer().trash("nope", "p1"))
        assertFalse(writer().trash("v1", "zz"))
        assertEquals(0, db.viewDao().pendingWriteCount())
        assertEquals(0, requests)
    }

    // --- M5b 책 위젯 ±쪽: 읽은 쪽 속성은 보기에서 숨겨져 있어도 된다(cells에는 없고 typed에만 있다).

    private suspend fun seedBook(read: Double?, total: Double?, progress: Float? = 0.1f, roles: Boolean = true) {
        val info = listOf(
            ViewColumn("t", "Name", "title"),
            ViewColumn("ing", "ing", "number", role = if (roles) ViewColumn.ROLE_PAGES_READ else ""),
            ViewColumn("pg", "page", "number", role = if (roles) ViewColumn.ROLE_PAGES_TOTAL else ""),
        )
        val book = ViewSnapshotEntity("bk", "ds", "읽는 중", "gallery", columns = "Name", syncedAt = 1, columnInfo = ViewJson.encodeColumns(info))
        val typed = mapOf("ing" to (read?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull), "pg" to (total?.let(::JsonPrimitive) ?: kotlinx.serialization.json.JsonNull))
        db.viewDao().upsert(book)
        db.viewDao().replace(book, listOf(ViewRowEntity("bk", 0, "b1", "u", "책", ViewJson.encodeTyped(typed), progress = progress)))
    }

    private suspend fun bookRow() = db.viewDao().row("bk", "b1")!!

    @Test
    fun addPagesMovesTheHiddenReadPagesAndTheBarAtOnce() = runTest {
        seedBook(30.0, 300.0)
        assertTrue(writer().addPages("bk", "b1", 10))
        val row = bookRow()
        assertEquals(JsonPrimitive(40.0), ViewJson.typed(row.typed)["ing"])
        assertEquals(40f / 300f, row.progress!!, 0.0001f)
        assertEquals("책", row.cells) // 보이는 열(제목)은 그대로
        val write = db.viewDao().firstWrite()!!
        assertEquals(listOf("ing", "number", "40.0", "ds", "b1"), listOf(write.propId, write.propType, write.value, write.dataSourceId, write.pageId))
        assertEquals(1, widgetUpdates)
        assertEquals(1, requests)
    }

    @Test
    fun addPagesStopsAtTheTotalAndAtZero() = runTest {
        seedBook(295.0, 300.0)
        assertTrue(writer().addPages("bk", "b1", 10))
        assertEquals(JsonPrimitive(300.0), ViewJson.typed(bookRow().typed)["ing"]) // 전체 쪽을 넘지 않는다
        assertEquals(1f, bookRow().progress!!, 0f)
        assertFalse(writer().addPages("bk", "b1", 10)) // 이미 끝: 바뀐 것이 없으니 큐에 더 쌓지 않는다
        assertEquals(1, requests)
        assertTrue(writer().addPages("bk", "b1", -500))
        assertEquals(JsonPrimitive(0.0), ViewJson.typed(bookRow().typed)["ing"]) // 0 아래로 가지 않는다
        assertEquals(0f, bookRow().progress!!, 0f)
        assertFalse(writer().addPages("bk", "b1", -10))
    }

    @Test
    fun plusNeverLowersAValueAlreadyAboveTheTotal() = runTest {
        seedBook(350.0, 300.0) // 전체 쪽을 잘못 적은 경우
        assertFalse(writer().addPages("bk", "b1", 10)) // +가 350을 300으로 깎지 않는다
        assertEquals(JsonPrimitive(350.0), ViewJson.typed(bookRow().typed)["ing"])
        assertTrue(writer().addPages("bk", "b1", -10))
        assertEquals(JsonPrimitive(340.0), ViewJson.typed(bookRow().typed)["ing"]) // −는 10쪽만 줄인다
    }

    @Test
    fun addPagesStartsFromZeroWhenNothingWasRead() = runTest {
        seedBook(null, 200.0, progress = null)
        assertTrue(writer().addPages("bk", "b1", 10))
        assertEquals(0.05f, bookRow().progress!!, 0.0001f)
    }

    @Test
    fun withoutAKnownTotalTheBarIsLeftForTheNextSyncAndThereIsNoUpperLimit() = runTest {
        seedBook(30.0, null, progress = 0.4f)
        assertTrue(writer().addPages("bk", "b1", 10))
        assertEquals(JsonPrimitive(40.0), ViewJson.typed(bookRow().typed)["ing"])
        assertEquals(0.4f, bookRow().progress!!, 0f)
    }

    @Test
    fun addPagesRefusesWhenThereIsNoReadPagesColumnOrRow() = runTest {
        seedBook(30.0, 300.0, roles = false)
        assertFalse(writer().addPages("bk", "b1", 10))
        assertFalse(writer().addPages("v1", "p1", 10)) // 쪽수 속성이 없는 보기
        assertFalse(writer().addPages("none", "b1", 10))
        seedBook(30.0, 300.0)
        assertFalse(writer().addPages("bk", "no-row", 10))
        assertNull(db.viewDao().firstWrite())
        assertEquals(0, widgetUpdates + requests)
    }

    @Test
    fun quickTapsOnPlusAreAllCounted() = runTest {
        seedBook(0.0, 1000.0)
        (1..20).map { async { writer().addPages("bk", "b1", 10) } }.awaitAll()
        assertEquals(JsonPrimitive(200.0), ViewJson.typed(bookRow().typed)["ing"])
        assertEquals(1, db.viewDao().pendingWriteCount()) // 같은 속성은 마지막 값 하나만
    }

    // --- Phase 10-5: 일반 값 쓰기(제목·텍스트·다중 선택·날짜·URL·이메일·전화)

    private suspend fun seedMixed() {
        val info = listOf(
            ViewColumn("t", "Name", "title"),
            ViewColumn("tg", "Tags", "multi_select", listOf(ViewChoice("a", "집"), ViewChoice("b", "회사"))),
            ViewColumn("wh", "When", "date"),
            ViewColumn("ur", "Link", "url"),
        )
        val mixed = ViewSnapshotEntity("mx", "ds", "혼합", "table", columns = listOf("Name", "Tags", "When", "Link").joinToString(sep), syncedAt = 1, columnInfo = ViewJson.encodeColumns(info))
        db.viewDao().upsert(mixed)
        db.viewDao().replace(mixed, listOf(ViewRowEntity("mx", 0, "m1", "u", listOf("첫째", "집", "", "").joinToString(sep), ViewJson.encodeTyped(mapOf("t" to JsonPrimitive("첫째"), "tg" to kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"))), "wh" to kotlinx.serialization.json.JsonNull, "ur" to kotlinx.serialization.json.JsonNull)))))
    }

    private suspend fun mixedCells() = db.viewDao().row("mx", "m1")!!.cells.split(sep)

    @Test
    fun setWritesTextAndShowsItLikeTheReadPath() = runTest {
        seedMixed()
        assertTrue(writer().set("mx", "m1", "t", JsonPrimitive("새 제목")))
        assertEquals("새 제목", mixedCells()[0])
        val write = db.viewDao().firstWrite()!!
        assertEquals(listOf("t", "title", "\"새 제목\""), listOf(write.propId, write.propType, write.value))
        assertEquals("\"첫째\"", write.baseValue) // 바꾸기 전 값이 기준값(충돌 확인용)
    }

    @Test
    fun setWritesMultiSelectDateAndUrl() = runTest {
        seedMixed()
        val both = kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("a"), JsonPrimitive("b")))
        assertTrue(writer().set("mx", "m1", "tg", both))
        val range = kotlinx.serialization.json.buildJsonObject {
            put("start", JsonPrimitive("2026-11-04"))
            put("end", JsonPrimitive("2026-11-05"))
        }
        assertTrue(writer().set("mx", "m1", "wh", range))
        assertTrue(writer().set("mx", "m1", "ur", JsonPrimitive("https://a.b")))
        assertEquals(listOf("첫째", "집, 회사", "2026-11-04~2026-11-05", "https://a.b"), mixedCells())
        assertEquals(3, db.viewDao().pendingWriteCount())
        assertTrue(writer().set("mx", "m1", "ur", kotlinx.serialization.json.JsonNull)) // 비우기
        assertEquals("", mixedCells()[3])
    }

    @Test
    fun setRefusesAValueOfTheWrongShape() = runTest {
        seedMixed()
        assertFalse(writer().set("mx", "m1", "tg", JsonPrimitive(3))) // 다중 선택은 목록
        assertFalse(writer().set("mx", "m1", "wh", JsonPrimitive("2026-11-04"))) // 날짜는 객체
        assertFalse(writer().set("mx", "m1", "t", kotlinx.serialization.json.JsonNull)) // 제목은 비울 수 없다
        assertFalse(writer().set("mx", "m1", "nope", JsonPrimitive("x")))
        assertNull(db.viewDao().firstWrite())
    }

    @Test
    fun aWriteThatIsBeingSentDoesNotLendItsBaseToTheNextOne() = runTest {
        // 보내는 중인 쓰기는 이미 노션에 반영되는 중이다. 다음 쓰기의 기준을 처음 값으로 이어받으면 보낸 값을 노션의 '다른 편집'으로 본다.
        seedMixed()
        writer().set("mx", "m1", "t", JsonPrimitive("둘째"))
        db.viewDao().markWriteSending(db.viewDao().firstWrite()!!.id)
        writer().set("mx", "m1", "t", JsonPrimitive("셋째"))
        val write = db.viewDao().firstWrite()!!
        assertEquals(listOf("\"셋째\"", "\"둘째\""), listOf(write.value, write.baseValue)) // 기준은 직전에 보낸 값
    }

    @Test
    fun blankStringsClearUrlLikeValuesAndUnknownOptionsAreRefused() = runTest {
        seedMixed()
        assertTrue(writer().set("mx", "m1", "ur", JsonPrimitive("  ")))
        assertNull(db.viewDao().firstWrite()!!.value) // 공백은 비우기(노션이 빈 글자를 거부한다)
        assertFalse(writer().set("mx", "m1", "tg", kotlinx.serialization.json.JsonArray(listOf(JsonPrimitive("zzz"))))) // 없는 옵션 ID
        assertTrue(writer().set("mx", "m1", "tg", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.buildJsonObject { put("name", JsonPrimitive("새 태그")) })))) // 새 옵션은 이름으로
    }

    @Test
    fun coalescedWritesKeepTheOriginalBaseValue() = runTest {
        // 같은 속성을 보내기 전에 두 번 바꿔도 기준값은 처음(노션에 있던) 값이다. 두 번째 쓰기의 기준을 로컬에서 바꾼 값으로 잡으면
        // 전송 전 충돌 확인이 항상 '노션이 바뀜'으로 보인다.
        seedMixed()
        writer().set("mx", "m1", "t", JsonPrimitive("둘째"))
        writer().set("mx", "m1", "t", JsonPrimitive("셋째"))
        val write = db.viewDao().firstWrite()!!
        assertEquals(listOf("\"셋째\"", "\"첫째\""), listOf(write.value, write.baseValue))
        assertEquals(1, db.viewDao().pendingWriteCount())
    }
}
