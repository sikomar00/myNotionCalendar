package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.data.db.ViewRowEntity
import com.ysdoing.notioncal.core.data.db.ViewSnapshotEntity
import com.ysdoing.notioncal.core.data.view.CoverStore
import com.ysdoing.notioncal.core.data.view.ViewColumn
import com.ysdoing.notioncal.core.data.view.ViewJson
import com.ysdoing.notioncal.core.data.view.ViewChoice
import com.ysdoing.notioncal.core.data.view.ViewWriter
import java.time.LocalDate
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
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
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

/** M3 위젯에서 바꾼 보기 행을 노션에 보내는 순서·재시도·거부 규칙. 일정 아웃박스와 따로 돈다. */
@RunWith(RobolectricTestRunner::class)
class ViewWriteProcessorTest {
    private lateinit var db: NotionCalDatabase
    private val clock = Clock.fixed(Instant.parse("2026-11-03T00:00:00Z"), ZoneOffset.UTC)
    private val sent = mutableListOf<Pair<String, JsonObject>>()
    private val created = mutableListOf<Pair<String, JsonObject>>()
    private val failures = ArrayDeque<NotionError?>()
    private val viewRequests = mutableListOf<String>()
    private var widgetUpdates = 0

    private var schema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "Done":{"id":"dn","name":"Done","type":"checkbox"},
            "When":{"id":"wh","name":"When","type":"date"},
            "Status":{"id":"st","name":"Status","type":"status"}}}""",
    )

    private val notionReader = object : NotionReader {
        override suspend fun dataSource(dataSourceId: String) = schema
        override suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult = error("쓰기 처리는 조회하지 않는다")
        override suspend fun pageOrNull(pageId: String): PageDto? = error("쓰기 처리는 단건 조회하지 않는다")
    }

    private val notionWriter = object : NotionWriter {
        override suspend fun createPage(dataSourceId: String, properties: JsonObject): PageDto {
            failures.removeFirstOrNull()?.let { throw NotionException(it) }
            created += dataSourceId to properties
            return PageDto(id = "new-page", lastEditedTime = "2026-11-03T00:00:00.000Z")
        }
        override suspend fun updatePage(pageId: String, properties: JsonObject): PageDto {
            failures.removeFirstOrNull()?.let { throw NotionException(it) }
            sent += pageId to properties
            return PageDto(id = pageId, lastEditedTime = "2026-11-03T00:00:00.000Z")
        }
        override suspend fun trashPage(pageId: String): PageDto? = error("보기 쓰기는 지우지 않는다")
    }

    private val viewReader = object : ViewReader {
        override suspend fun viewIds(dataSourceId: String) = emptyList<String>()
        override suspend fun view(viewId: String): ViewDto {
            viewRequests += viewId
            return ViewDto(id = viewId, name = "할 일", type = "table")
        }
        override suspend fun rows(dataSourceId: String, view: ViewDto, limit: Int) = emptyList<PageDto>()
    }

    @Before
    fun setUp() = runTest {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries().build()
        val columns = listOf(
            ViewColumn("title", "Name", "title"),
            ViewColumn("wh", "When", "date"),
            ViewColumn("dn", "Done", "checkbox"),
            ViewColumn("st", "Status", "status", listOf(ViewChoice("o1", "할 일"), ViewChoice("o2", "진행 중"))),
        )
        val snapshot = ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = "Name\u001FDone\u001FStatus", columnInfo = ViewJson.encodeColumns(columns))
        db.viewDao().upsert(snapshot)
        db.viewDao().replace(
            snapshot,
            listOf(
                ViewRowEntity("v1", 0, "p1", "u1", "첫째\u001F\u001F할 일", ViewJson.encodeTyped(mapOf("dn" to JsonPrimitive(false), "st" to JsonPrimitive("o1")))),
            ),
        )
    }

    private val coverDir = java.nio.file.Files.createTempDirectory("covers").toFile()

    @After
    fun tearDown() {
        db.close()
        coverDir.deleteRecursively()
    }

    private fun processor(): ViewWriteProcessor {
        val views = ViewSync(viewReader, db.viewDao(), { widgetUpdates++ }, clock, CoverSync(CoverStore(coverDir), CoverDownload { null }))
        return ViewWriteProcessor(notionReader, notionWriter, db, views, { widgetUpdates++ }, clock, Dispatchers.Unconfined)
    }

    private fun writer() = ViewWriter(db.viewDao(), { }, { }, clock)

    @Test
    fun sendsInOrderUsingPropertyNamesFromTheCurrentSchema() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().cycle("v1", "p1", "st")
        schema = NotionJson.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{"Done":{"id":"dn","name":"완료 여부","type":"checkbox"},"Status":{"id":"st","name":"Status","type":"status"}}}""",
        )
        val outcome = processor().process()
        assertEquals(2, outcome.sent)
        assertEquals(0, outcome.remaining)
        assertNull(outcome.retryError)
        // 속성 이름을 노션에서 바꿔도 보내는 순간의 이름으로 쓴다(ID를 키로 보내면 노션이 무시한다).
        assertEquals(listOf("p1" to """{"완료 여부":{"checkbox":true}}""", "p1" to """{"Status":{"status":{"id":"o2"}}}"""), sent.map { it.first to it.second.toString() })
        assertEquals(0, db.viewDao().pendingWriteCount())
        assertEquals(0, viewRequests.size) // 보낸 값은 이미 화면에 있으니 다시 받지 않는다
    }

    @Test
    fun aTransientErrorStopsTheQueueAndKeepsTheWrites() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().cycle("v1", "p1", "st")
        failures += NotionError.Network
        val outcome = processor().process()
        assertEquals(0, outcome.sent)
        assertEquals(2, outcome.remaining) // 순서를 지키려고 첫 실패에서 멈춘다
        assertEquals(NotionError.Network, outcome.retryError)
        val first = db.viewDao().firstWrite()!!
        assertEquals(1, first.attempts)
        assertEquals("Network", first.lastError)
        // 다음 실행에서 이어 보낸다.
        assertEquals(2, processor().process().sent)
    }

    @Test
    fun aRejectedWriteIsDroppedAndTheViewIsReadAgain() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().cycle("v1", "p1", "st")
        failures += NotionError.Validation("validation_error")
        val outcome = processor().process()
        assertEquals(1, outcome.sent) // 거부된 바꿈 뒤의 다음 바꿈은 계속 보낸다
        assertEquals(1, outcome.dropped)
        assertEquals(0, outcome.remaining)
        assertEquals(listOf("v1"), viewRequests) // 위젯을 노션 값으로 되돌리려고 그 보기를 다시 받는다
    }

    @Test
    fun aMissingPageDoesNotBlockTheRestOfTheQueue() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().cycle("v1", "p1", "st")
        failures += NotionError.NotShared
        val outcome = processor().process()
        assertEquals(1, outcome.dropped)
        assertEquals(1, outcome.sent)
        assertEquals(0, db.viewDao().pendingWriteCount())
    }

    @Test
    fun anEmptyQueueDoesNothing() = runTest {
        val outcome = processor().process()
        assertEquals(listOf(0, 0, 0), listOf(outcome.sent, outcome.dropped, outcome.remaining))
        assertEquals(0, widgetUpdates)
    }

    @Test
    fun aWriteThatKeepsFailingIsEventuallyDropped() = runTest {
        writer().toggle("v1", "p1", "dn")
        val id = db.viewDao().firstWrite()!!.id
        repeat(10) { db.viewDao().markWriteFailed(id, "Server") }
        val outcome = processor().process()
        assertEquals(1, outcome.dropped)
        assertEquals(0, sent.size) // 보내지 않고 버린다
        assertEquals(listOf("v1"), viewRequests) // 위젯을 노션 값으로 되돌린다
    }

    @Test
    fun aCorruptValueIsDroppedInsteadOfClearingTheNotionValue() = runTest {
        val row = db.viewDao().row("v1", "p1")!!
        db.viewDao().enqueue(row, com.ysdoing.notioncal.core.data.db.ViewWriteEntity(viewId = "v1", pageId = "p1", dataSourceId = "ds", propId = "st", propType = "status", value = "{oops", createdAt = 1))
        val outcome = processor().process()
        assertEquals(listOf(0, 1), listOf(outcome.sent, outcome.dropped))
        assertEquals(0, sent.size) // 깨진 값을 '비우기'로 읽어 노션 값을 지우지 않는다
    }

    @Test
    fun aDeletedPropertyIsDroppedBecauseNotionWouldIgnoreAnIdKey() = runTest {
        writer().toggle("v1", "p1", "dn")
        schema = NotionJson.decodeFromString(DataSourceDto.serializer(), """{"id":"ds","properties":{"Name":{"id":"title","name":"Name","type":"title"}}}""")
        val outcome = processor().process()
        assertEquals(listOf(0, 1), listOf(outcome.sent, outcome.dropped))
        assertEquals(0, sent.size)
    }

    @Test
    fun aTransientErrorAfterARejectionDoesNotTryToReadTheViewNow() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().cycle("v1", "p1", "st")
        failures += NotionError.Validation("validation_error")
        failures += NotionError.Network
        val outcome = processor().process()
        assertEquals(1, outcome.dropped)
        assertEquals(NotionError.Network, outcome.retryError)
        assertEquals(1, outcome.remaining)
        assertEquals(0, viewRequests.size) // 연결이 안 되는 지금 보기를 다시 받지 않는다(다음 동기화가 한다)
    }

    // --- 새 항목 만들기

    @Test
    fun createsTheItemUsingCurrentNamesAndThenReadsTheViewToShowIt() = runTest {
        writer().create("v1", "장보기", LocalDate.of(2026, 11, 4))
        val outcome = processor().process()
        assertEquals(listOf(1, 0, 0), listOf(outcome.sent, outcome.dropped, outcome.remaining))
        assertEquals(
            listOf("ds" to """{"Name":{"title":[{"text":{"content":"장보기"}}]},"When":{"date":{"start":"2026-11-04"}}}"""),
            created.map { it.first to it.second.toString() },
        )
        assertEquals(listOf("v1"), viewRequests) // 새 행이 위젯에 나타나게 보기를 바로 다시 받는다
        assertEquals(0, sent.size) // 속성 바꿈(PATCH)이 아니다
    }

    @Test
    fun theItemIsStillCreatedWhenTheDatePropertyWasDeleted() = runTest {
        writer().create("v1", "장보기", LocalDate.of(2026, 11, 4))
        schema = NotionJson.decodeFromString(DataSourceDto.serializer(), """{"id":"ds","properties":{"Name":{"id":"title","name":"Name","type":"title"}}}""")
        processor().process()
        assertEquals("""{"Name":{"title":[{"text":{"content":"장보기"}}]}}""", created.single().second.toString()) // 제목이 더 중요하다
    }

    @Test
    fun aDeletedTitlePropertyDropsTheCreateInsteadOfSendingAnIdKey() = runTest {
        writer().create("v1", "장보기", null)
        schema = NotionJson.decodeFromString(DataSourceDto.serializer(), """{"id":"ds","properties":{}}""")
        val outcome = processor().process()
        assertEquals(1, outcome.dropped)
        assertEquals(0, created.size)
    }

    @Test
    fun aFailedCreateIsRetriedAndKeptInOrder() = runTest {
        writer().create("v1", "하나", null)
        failures += NotionError.Server(503)
        val outcome = processor().process()
        assertEquals(NotionError.Server(503), outcome.retryError)
        assertEquals(1, outcome.remaining)
        assertEquals(0, created.size)
        assertEquals(1, processor().process().sent) // 다음 실행에서 만든다
        assertEquals(1, created.size)
    }

    @Test
    fun aDroppedCreateIsLeftAsAWriteFailureNotLostSilently() = runTest {
        writer().create("v1", "장보기", null)
        failures += NotionError.Validation("validation_error")
        processor().process()
        val issue = db.conflictDao().all().single()
        assertEquals(listOf("장보기", "PAGE", "CREATE", OutboxProcessor.KIND_REJECTED), listOf(issue.title, issue.field, issue.localValue, issue.kind))
    }

    @Test
    fun aDroppedChangeIsRecordedWithTheRowTitle() = runTest {
        writer().toggle("v1", "p1", "dn")
        failures += NotionError.Validation("validation_error")
        processor().process()
        val issue = db.conflictDao().all().single()
        assertEquals(listOf("첫째", "UPDATE", OutboxProcessor.KIND_REJECTED), listOf(issue.title, issue.localValue, issue.kind))
    }

    @Test
    fun aCreateIsNeverDroppedForTooManyRetries() = runTest {
        writer().create("v1", "장보기", null)
        val id = db.viewDao().firstWrite()!!.id
        repeat(20) { db.viewDao().markWriteFailed(id, "Network") }
        val outcome = processor().process()
        assertEquals(listOf(1, 0), listOf(outcome.sent, outcome.dropped)) // 사용자가 쓴 글이라 보낼 때까지 다시 시도한다
        assertEquals(1, created.size)
        assertEquals(0, db.conflictDao().all().size)
    }
}
