package com.ysdoing.notioncal.core.data.view

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
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

    private fun writer() = ViewWriter(db.viewDao(), { widgetUpdates++ }, { requests++ }, clock)

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
}
