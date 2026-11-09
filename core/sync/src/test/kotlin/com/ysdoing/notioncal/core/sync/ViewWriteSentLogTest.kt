package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.OutboxRequester
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.ActionChange
import com.ysdoing.notioncal.core.data.view.ActionOp
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.ViewAction
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewWriter
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.NotionWriter
import com.ysdoing.notioncal.core.notion.QueryResult
import com.ysdoing.notioncal.core.notion.ViewReader
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import com.ysdoing.notioncal.core.notion.dto.ViewDto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
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
 * Phase 12c IX-05 후반(4초 뒤 되돌리기): 처리기는 속성 바꿈을 보낼 때 이전 값과 보낸 값을 기록하고(새 항목·휴지통·충돌·거부는 기록하지 않는다),
 * 되돌릴 수 있는 시간(2분)이 지나면 지운다. 되돌리기는 반대 쓰기를 쌓고, 처리기가 노션 값과 견준 뒤 보낸다.
 */
@RunWith(RobolectricTestRunner::class)
class ViewWriteSentLogTest {
    private lateinit var db: NotionCalDatabase
    private var now = Instant.parse("2026-11-03T00:00:00Z")
    private val clock = object : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant() = now
    }
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private var widgetUpdates = 0

    private val schema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{"Name":{"id":"title","name":"Name","type":"title"},"Done":{"id":"dn","name":"Done","type":"checkbox"},"Pages":{"id":"pg","name":"Pages","type":"number"}}}""",
    )

    /** 노션에 있는 p1의 지금 값(전송 전 충돌 확인이 읽는다). */
    private var remoteDone = false
    private var remotePages = 12.0

    private fun remotePage() = PageDto(
        id = "p1", lastEditedTime = "2026-11-03T00:00:00.000Z",
        properties = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"Name":{"type":"title","title":[{"plain_text":"첫째"}]},"Done":{"type":"checkbox","checkbox":$remoteDone},"Pages":{"type":"number","number":$remotePages}}""",
        ) as JsonObject,
    )

    private val notionReader = object : NotionReader {
        override suspend fun dataSource(dataSourceId: String) = schema
        override suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult = error("쓰기 처리는 조회하지 않는다")
        override suspend fun pageOrNull(pageId: String): PageDto? = remotePage()
    }
    private val notionWriter = object : NotionWriter {
        override suspend fun createPage(dataSourceId: String, properties: JsonObject) = PageDto(id = "new", lastEditedTime = "2026-11-03T00:00:00.000Z")
        override suspend fun updatePage(pageId: String, properties: JsonObject): PageDto {
            sent += pageId to properties
            return PageDto(id = pageId, lastEditedTime = "2026-11-03T00:00:00.000Z")
        }
        override suspend fun trashPage(pageId: String): PageDto? = PageDto(id = pageId, lastEditedTime = "2026-11-03T00:00:00.000Z", inTrash = true)
    }
    private val viewReader = object : ViewReader {
        override suspend fun viewIds(dataSourceId: String) = emptyList<String>()
        override suspend fun view(viewId: String) = ViewDto(id = viewId, name = "할 일", type = "table")
        override suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int) = emptyList<PageDto>()
    }
    private val requester = OutboxRequester.None
    private val coverDir = java.nio.file.Files.createTempDirectory("covers").toFile()

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java).allowMainThreadQueries().build()
        val columns = listOf(ViewColumn("title", "Name", "title"), ViewColumn("dn", "Done", "checkbox"), ViewColumn("pg", "Pages", "number"))
        val snapshot = ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = "Name\u001FDone\u001FPages", columnInfo = ViewJson.encodeColumns(columns))
        db.viewDao().upsert(snapshot)
        db.viewDao().replace(snapshot, listOf(ViewRowEntity("v1", 0, "p1", "u1", "첫째\u001F\u001F12", ViewJson.encodeTyped(mapOf("dn" to JsonPrimitive(false), "pg" to JsonPrimitive(12.0))))))
    }

    @After
    fun tearDown() {
        db.close()
        coverDir.deleteRecursively()
    }

    private val writer get() = ViewWriter(db.viewDao(), db.eventDao(), { widgetUpdates++ }, requester, clock)

    private fun processor(): ViewWriteProcessor {
        val views = ViewSync(viewReader, db.viewDao(), { widgetUpdates++ }, clock, CoverSync(CoverStore(coverDir), CoverDownload { null }))
        return ViewWriteProcessor(notionReader, notionWriter, db, views, { widgetUpdates++ }, clock, Dispatchers.Unconfined)
    }

    private suspend fun logs() = db.viewDao().sentSince("v1", 0)

    @Test
    fun aSentPropertyChangeIsLoggedWithItsPriorValue() = runTest {
        writer.set("v1", "p1", "pg", JsonPrimitive(40.0))
        processor().process()
        val log = logs().single()
        assertEquals(listOf("pg", "12.0", "40.0", now.toEpochMilli()), listOf(log.propId, log.priorValue, log.sentValue, log.sentAt))
    }

    @Test
    fun tapsMergedIntoOneWriteLogTheFirstPriorAndTheLastValue() = runTest {
        writer.set("v1", "p1", "pg", JsonPrimitive(20.0))
        writer.set("v1", "p1", "pg", JsonPrimitive(30.0))
        processor().process()
        val log = logs().single()
        assertEquals(listOf("12.0", "30.0"), listOf(log.priorValue, log.sentValue))
    }

    @Test
    fun anActionsWritesShareOneBatchInTheLog() = runTest {
        val action = ViewAction("a1", "v1", "끝", listOf(ActionChange("dn", ActionOp.SET, "true"), ActionChange("pg", ActionOp.SET, "40")))
        writer.applyAction("v1", "p1", action)
        now = now.plusSeconds(5) // 4초 지연이 지났다
        processor().process()
        val logs = logs()
        assertEquals(2, logs.size)
        assertEquals(setOf(Instant.parse("2026-11-03T00:00:00Z").toEpochMilli()), logs.map { it.batchId }.toSet())
    }

    @Test
    fun aConflictIsNotLoggedBecauseNotionsValueWon() = runTest {
        remotePages = 99.0 // 노션에서 그 사이 바뀌었다
        writer.set("v1", "p1", "pg", JsonPrimitive(40.0))
        processor().process()
        assertEquals(emptyList<Any>(), logs())
    }

    @Test
    fun aCreateOrATrashIsNotLogged() = runTest {
        writer.create("v1", "새 항목", null)
        writer.trash("v1", "p1")
        processor().process()
        assertEquals(emptyList<Any>(), logs())
    }

    @Test
    fun logsOlderThanTheWindowAreDroppedAndTheWidgetRedrawn() = runTest {
        writer.set("v1", "p1", "pg", JsonPrimitive(40.0))
        processor().process()
        val before = widgetUpdates
        now = now.plusMillis(ViewWriter.SENT_UNDO_WINDOW_MS + 1)
        processor().process()
        assertEquals(emptyList<Any>(), logs())
        assertTrue("줄이 사라지게 다시 그려야 한다", widgetUpdates > before)
    }

    @Test
    fun undoingASentChangeQueuesTheOppositeWriteAgainstTheSentValueAndSendsItBack() = runTest {
        writer.set("v1", "p1", "pg", JsonPrimitive(40.0))
        processor().process()
        remotePages = 40.0 // 노션에 반영됐다
        assertEquals(1, writer.undoSent("v1"))
        assertEquals(emptyList<Any>(), logs()) // 되돌린 기록은 지운다
        processor().process()
        val last = sent.last().second
        assertEquals(JsonPrimitive(12.0), (last["Pages"] as JsonObject)["number"]) // 이전 값으로 돌아갔다
    }

    @Test
    fun theReverseWritesOfAnUndoAreNotLoggedSoNoRedoLineAppears() = runTest {
        val action = ViewAction("a1", "v1", "끝", listOf(ActionChange("dn", ActionOp.SET, "true"), ActionChange("pg", ActionOp.SET, "40")))
        writer.applyAction("v1", "p1", action)
        now = now.plusSeconds(5)
        processor().process()
        val before = sent.size
        remoteDone = true
        remotePages = 40.0
        assertEquals(2, writer.undoSent("v1")) // 한 묶음의 두 칸을 함께
        processor().process()
        assertEquals(before + 2, sent.size) // 되돌림 쓰기 둘이 나갔다
        assertEquals(emptyList<Any>(), logs()) // 되돌림은 기록하지 않는다
    }

    @Test
    fun undoingAfterNotionChangedTheValueKeepsNotionsValueAndRecordsAConflict() = runTest {
        writer.set("v1", "p1", "pg", JsonPrimitive(40.0))
        processor().process()
        sent.clear()
        remotePages = 55.0 // 보낸 뒤 노션에서 또 바뀌었다
        writer.undoSent("v1")
        processor().process()
        assertEquals(emptyList<Any>(), sent) // 보내지 않았다
        assertEquals(1, db.conflictDao().all().size)
    }
}
