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
    private val trashed = mutableListOf<String>()
    private var trashGone = false // 노션에 이미 없는 페이지(휴지통·삭제)
    private val failures = ArrayDeque<NotionError?>()
    private val viewRequests = mutableListOf<String>()
    private var widgetUpdates = 0

    private var schema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "Done":{"id":"dn","name":"Done","type":"checkbox"},
            "When":{"id":"wh","name":"When","type":"date"},
            "Status":{"id":"st","name":"Status","type":"status","status":{"options":[{"id":"o1","name":"할 일"},{"id":"o2","name":"진행 중"},{"id":"o3","name":"완료"}],"groups":[]}},
            "Pages":{"id":"pg","name":"Pages","type":"number"}}}""",
    )

    /** 노션에 있는 p1의 현재 속성(SY-05 전송 전 충돌 확인이 읽는다). 기본은 행을 받아 둘 때와 같은 값이다. */
    private var remote: PageDto? = remotePage(done = false, status = "o1", pages = 12.0)
    private var pageLookups = 0

    /** 노션 값을 읽는 도중에 실행한다(보내는 중 상태 확인용). */
    private var duringLookup: (suspend () -> Unit)? = null

    private fun remotePage(done: Boolean, status: String, pages: Double?) = PageDto(
        id = "p1", lastEditedTime = "2026-11-03T00:00:00.000Z",
        properties = kotlinx.serialization.json.Json.parseToJsonElement(
            """{"Name":{"type":"title","title":[{"plain_text":"첫째"}]},
               "Done":{"type":"checkbox","checkbox":$done},
               "Status":{"type":"status","status":{"id":"$status","name":"x"}},
               "Pages":{"type":"number","number":${pages ?: "null"}}}""",
        ).let { it as kotlinx.serialization.json.JsonObject },
    )

    private val notionReader = object : NotionReader {
        override suspend fun dataSource(dataSourceId: String) = schema
        override suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult = error("쓰기 처리는 조회하지 않는다")
        override suspend fun pageOrNull(pageId: String): PageDto? {
            pageLookups++
            duringLookup?.invoke()
            return remote
        }
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
        override suspend fun trashPage(pageId: String): PageDto? {
            failures.removeFirstOrNull()?.let { throw NotionException(it) }
            if (trashGone) return null
            trashed += pageId
            return PageDto(id = pageId, lastEditedTime = "2026-11-03T00:00:00.000Z", inTrash = true)
        }
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
            ViewColumn("pg", "Pages", "number"),
        )
        val snapshot = ViewSnapshotEntity("v1", "ds", "할 일", "table", columns = "Name\u001FDone\u001FStatus", columnInfo = ViewJson.encodeColumns(columns))
        db.viewDao().upsert(snapshot)
        db.viewDao().replace(
            snapshot,
            listOf(
                ViewRowEntity("v1", 0, "p1", "u1", "첫째\u001F\u001F할 일", ViewJson.encodeTyped(mapOf("dn" to JsonPrimitive(false), "st" to JsonPrimitive("o1"), "pg" to JsonPrimitive(12.0)))),
            ),
        )
    }

    private val coverDir = java.nio.file.Files.createTempDirectory("covers").toFile()

    @After
    fun tearDown() {
        db.close()
        coverDir.deleteRecursively()
    }

    private fun processor(at: Clock = clock): ViewWriteProcessor {
        val views = ViewSync(viewReader, db.viewDao(), { widgetUpdates++ }, at, CoverSync(CoverStore(coverDir), CoverDownload { null }))
        return ViewWriteProcessor(notionReader, notionWriter, db, views, { widgetUpdates++ }, at, Dispatchers.Unconfined)
    }

    private fun writer() = ViewWriter(db.viewDao(), db.eventDao(), { }, { }, clock)

    @Test
    fun sendsInOrderUsingPropertyNamesFromTheCurrentSchema() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().cycle("v1", "p1", "st")
        schema = NotionJson.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{"Done":{"id":"dn","name":"완료 여부","type":"checkbox"},"Status":{"id":"st","name":"Status","type":"status"}}}""",
        )
        // 노션에서 속성 이름을 바꾸면 페이지 JSON의 키도 새 이름이다.
        remote = remote!!.copy(properties = JsonObject(remote!!.properties.mapKeys { (name, _) -> if (name == "Done") "완료 여부" else name }))
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

    // --- SY-05 전송 전 충돌 확인: 노션 값이 기준값과 다르면 노션 값 우선

    @Test
    fun aNotionEditSinceTheBaseWinsAndIsRecordedAsAConflictWithReadableValues() = runTest {
        writer().cycle("v1", "p1", "st") // 기준값은 할 일(o1), 내 값은 진행 중(o2)
        remote = remotePage(done = false, status = "o3", pages = 12.0) // 그 사이 노션에서 완료(o3)로 바꿈
        val outcome = processor().process()
        assertEquals(listOf(0, 1, 0), listOf(outcome.sent, outcome.dropped, outcome.remaining))
        assertEquals(0, sent.size) // 노션에 쓰지 않았다
        val conflict = db.conflictDao().all().single()
        // 배너에 옵션 ID·JSON이 아니라 이름으로 보인다.
        assertEquals(listOf("PROP:Status", OutboxProcessor.KIND_CONFLICT, "진행 중", "완료"), listOf(conflict.field, conflict.kind, conflict.localValue, conflict.remoteValue))
        assertEquals(listOf("v1"), viewRequests) // 보기를 다시 받아 위젯을 노션 값으로 되돌린다
    }

    @Test
    fun aWriteNotionAlreadyAppliedIsNotAConflictAndIsNotSentAgain() = runTest {
        // 보낸 요청은 노션에 닿았는데 응답이 끊겨 다시 시도하는 경우: 노션 값이 이미 내 값이면 충돌이 아니고 다시 쓰지도 않는다.
        writer().toggle("v1", "p1", "dn") // 해제(false) → 체크(true)
        remote = remotePage(done = true, status = "o1", pages = 12.0)
        val outcome = processor().process()
        assertEquals(listOf(1, 0), listOf(outcome.sent, outcome.dropped))
        assertEquals(0, sent.size)
        assertEquals(0, db.conflictDao().all().size)
    }

    @Test
    fun aWriteIsMarkedAsBeingSentWhileNotionIsRead() = runTest {
        writer().toggle("v1", "p1", "dn")
        var during: String? = null
        duringLookup = { during = db.viewDao().firstWrite()?.lastError }
        processor().process()
        assertEquals("Sending", during) // 이때 같은 속성을 또 바꾸면 기준값을 이어받지 않는다(ViewDao.enqueue)
    }

    @Test
    fun aStaleReadingPageCountDoesNotOverwriteWhatNotionHas() = runTest {
        // M5b 한계 해소: 위젯이 보여 준 12쪽에 10을 더해 22를 쓰려 했는데 그 사이 노션이 100이 됐다면 100을 지키고 충돌로 남긴다.
        writer().add("v1", "p1", "pg", 10.0)
        remote = remotePage(done = false, status = "o1", pages = 100.0)
        val outcome = processor().process()
        assertEquals(1, outcome.dropped)
        assertEquals(0, sent.size)
        val conflict = db.conflictDao().all().single()
        assertEquals(listOf("PROP:Pages", "22", "100"), listOf(conflict.field, conflict.localValue, conflict.remoteValue))
    }

    @Test
    fun aWriteWhoseBaseStillMatchesIsSentAfterOneLookup() = runTest {
        writer().toggle("v1", "p1", "dn")
        val outcome = processor().process()
        assertEquals(listOf(1, 0), listOf(outcome.sent, outcome.dropped))
        assertEquals(1, pageLookups)
        assertEquals(0, db.conflictDao().all().size)
    }

    @Test
    fun aWriteWithoutABaseIsSentWithoutLookingNotionUp() = runTest {
        // 기준값이 없는 쓰기(이전 버전에서 쌓인 것)는 확인하지 않고 보낸다.
        val row = db.viewDao().row("v1", "p1")!!
        db.viewDao().enqueue(row, com.ysdoing.notioncal.core.data.db.ViewWriteEntity(viewId = "v1", pageId = "p1", dataSourceId = "ds", propId = "dn", propType = "checkbox", value = "true", createdAt = 1))
        remote = remotePage(done = true, status = "o2", pages = 1.0) // 달라도 상관없다
        val outcome = processor().process()
        assertEquals(1, outcome.sent)
        assertEquals(0, pageLookups)
    }

    @Test
    fun aWriteForAPageGoneFromNotionIsRejectedNotSent() = runTest {
        writer().toggle("v1", "p1", "dn")
        remote = null
        val outcome = processor().process()
        assertEquals(listOf(0, 1), listOf(outcome.sent, outcome.dropped))
        assertEquals(OutboxProcessor.KIND_REJECTED, db.conflictDao().all().single().kind)
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
    fun aCreateIsMarkedSendingWhileItGoesOutAndEditableAgainWhenTheSendFails() = runTest {
        writer().create("v1", "하나", null)
        failures += NotionError.Network
        processor().process()
        // 연결이 끊겨 못 보냈으면 '보내는 중'이 아니다: 해빗 체크가 그 새 항목의 체크를 다시 고칠 수 있다.
        assertEquals(NotionError.Network.storedName(), db.viewDao().pendingCreates("v1").single().lastError)
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

    // --- Phase 11b ED-05 휴지통 쓰기(`in_trash`). 스키마·충돌 확인이 필요 없고, 앞서 쌓은 같은 페이지 바꿈이 먼저 나간다.

    @Test
    fun aTrashIsSentWithoutLookingTheSchemaOrThePageUp() = runTest {
        writer().trash("v1", "p1")
        val outcome = processor().process()
        assertEquals(listOf(1, 0, 0), listOf(outcome.sent, outcome.dropped, outcome.remaining))
        assertEquals(listOf("p1"), trashed)
        assertEquals(0, pageLookups) // 충돌을 가를 기준값이 없다: 마지막 쓰기가 이긴다
        assertEquals(0, viewRequests.size)
    }

    @Test
    fun aPageAlreadyGoneInNotionCountsAsTrashed() = runTest {
        trashGone = true
        writer().trash("v1", "p1")
        val outcome = processor().process()
        assertEquals(listOf(1, 0), listOf(outcome.sent, outcome.dropped))
        assertEquals(0, db.conflictDao().all().size)
    }

    @Test
    fun aTrashReplacesTheEarlierEditsOfThePage() = runTest {
        writer().toggle("v1", "p1", "dn")
        writer().trash("v1", "p1")
        val outcome = processor().process()
        assertEquals(1, outcome.sent)
        assertEquals(emptyList<Pair<String, String>>(), sent.map { it.first to it.second.toString() }) // 곧 휴지통으로 갈 페이지에 속성은 보내지 않는다
        assertEquals(listOf("p1"), trashed)
    }

    @Test
    fun aRejectedTrashRefreshesEveryViewOfTheSource() = runTest {
        // 휴지통이 거부되면 행이 돌아와야 한다. 같은 소스의 다른 보기에서도 이미 지웠으므로 모두 다시 받는다.
        val other = ViewSnapshotEntity("v2", "ds", "다른 보기", "table", columns = "Name", columnInfo = ViewJson.encodeColumns(listOf(ViewColumn("title", "Name", "title"))))
        db.viewDao().upsert(other)
        writer().trash("v1", "p1")
        failures += NotionError.Validation("validation_error")
        processor().process()
        assertEquals(setOf("v1", "v2"), viewRequests.toSet())
    }

    @Test
    fun aTransientErrorKeepsTheTrashAndTheNextRunSendsIt() = runTest {
        writer().trash("v1", "p1")
        failures += NotionError.Network
        val outcome = processor().process()
        assertEquals(listOf(0, 1), listOf(outcome.sent, outcome.remaining))
        assertEquals(NotionError.Network, outcome.retryError)
        assertEquals(1, processor().process().sent)
        assertEquals(listOf("p1"), trashed)
    }

    @Test
    fun aRejectedTrashIsRecordedWithTheRowTitleAndTheViewIsReadAgain() = runTest {
        writer().trash("v1", "p1")
        failures += NotionError.Validation("validation_error")
        val outcome = processor().process()
        assertEquals(1, outcome.dropped)
        val issue = db.conflictDao().all().single()
        // 지운 행은 로컬에 없으니 제목은 큐에 남긴 글자로 적는다. 보기를 다시 받으면 행이 돌아온다(노션에 그대로 있으므로).
        assertEquals(listOf("첫째", "PAGE", "TRASH", OutboxProcessor.KIND_REJECTED), listOf(issue.title, issue.field, issue.localValue, issue.kind))
        assertEquals(listOf("v1"), viewRequests)
    }

    @Test
    fun aTrashThatKeepsFailingIsEventuallyDropped() = runTest {
        writer().trash("v1", "p1")
        val id = db.viewDao().firstWrite()!!.id
        repeat(10) { db.viewDao().markWriteFailed(id, "Server") }
        val outcome = processor().process()
        assertEquals(1, outcome.dropped)
        assertEquals(0, trashed.size)
    }

    // --- Phase 11c 전송 지연(sendAfter): 지연 중인 쓰기는 안 나가고, 시각이 되면 나간다. 안 나가는 쓰기가 나가는 쓰기를 막지 않는다.

    @Test
    fun aWriteStillInItsDelayIsNotSentAndStaysQueued() = runTest {
        writer().toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        val outcome = processor().process()
        assertEquals(listOf(0, 0, 1), listOf(outcome.sent, outcome.dropped, outcome.remaining))
        assertEquals(0, sent.size)
        assertEquals(0, pageLookups) // 노션 값을 읽지도 않는다
    }

    @Test
    fun theSameWriteIsSentOnceItsTimeHasCome() = runTest {
        writer().toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        val later = Clock.offset(clock, java.time.Duration.ofMillis(ViewWriter.UNDO_WINDOW_MS))
        assertEquals(1, processor(later).process().sent)
        assertEquals(listOf("p1" to """{"Done":{"checkbox":true}}"""), sent.map { it.first to it.second.toString() })
    }

    @Test
    fun aDueWriteIsNotBlockedByAnOlderWriteThatIsStillDelayed() = runTest {
        writer().toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS) // 먼저 쌓았지만 아직 지연 중
        writer().cycle("v1", "p1", "st") // 나중에 쌓았지만 바로 보낼 수 있다
        val outcome = processor().process()
        assertEquals(listOf(1, 1), listOf(outcome.sent, outcome.remaining))
        assertEquals(listOf("p1" to """{"Status":{"status":{"id":"o2"}}}"""), sent.map { it.first to it.second.toString() })
    }

    // --- Phase 11c IX-06: 보낸 바꿈 때문에 행이 보기 필터에서 빠지거나(또는 폰에서 판정할 수 없으면) 전송 뒤 그 보기를 다시 받아 행이 사라지게 한다.

    private suspend fun filterThe(view: String) {
        db.viewDao().upsert(db.viewDao().snapshot("v1")!!.copy(filterJson = view))
    }

    @Test
    fun aSentChangeThatMakesTheRowLeaveTheFilterRefreshesTheView() = runTest {
        filterThe("""{"property":"dn","checkbox":{"equals":false}}""")
        writer().toggle("v1", "p1", "dn") // 완료로 바꾸면 '완료 안 함' 필터에서 빠진다
        processor().process()
        assertEquals(listOf("v1"), viewRequests)
    }

    @Test
    fun aSentChangeThatStaysInsideTheFilterDoesNotRefresh() = runTest {
        filterThe("""{"property":"dn","checkbox":{"equals":true}}""")
        writer().toggle("v1", "p1", "dn")
        processor().process()
        assertEquals(emptyList<String>(), viewRequests) // 요청 수를 늘리지 않는다
    }

    @Test
    fun aFilterItCannotJudgeRefreshesAfterTheSend() = runTest {
        filterThe("""{"property":"zz","formula":{"string":{"equals":"x"}}}""")
        writer().toggle("v1", "p1", "dn")
        processor().process()
        assertEquals(listOf("v1"), viewRequests) // 판정 불가('반영 대기') → 전송 뒤 보기를 다시 받는다
    }

    // --- Phase 11c 새 항목에 값 묶음(AD-01·05): 보내는 순간의 속성 이름으로 합치고, 그 사이 지워진 속성은 건너뛴다.

    @Test
    fun createSendsTheExtraValuesUnderTheCurrentPropertyNames() = runTest {
        writer().create("v1", "점심", null, mapOf("st" to JsonPrimitive("o2"), "dn" to JsonPrimitive(true)))
        processor().process()
        assertEquals(
            listOf("ds" to """{"Name":{"title":[{"text":{"content":"점심"}}]},"Status":{"status":{"id":"o2"}},"Done":{"checkbox":true}}"""),
            created.map { it.first to it.second.toString() },
        )
    }

    @Test
    fun aValueForAPropertyDeletedMeanwhileIsLeftOutButTheItemIsStillCreated() = runTest {
        writer().create("v1", "점심", null, mapOf("st" to JsonPrimitive("o2"), "dn" to JsonPrimitive(true)))
        schema = NotionJson.decodeFromString(
            DataSourceDto.serializer(),
            """{"id":"ds","properties":{"Name":{"id":"title","name":"Name","type":"title"},"Done":{"id":"dn","name":"Done","type":"checkbox"}}}""",
        )
        processor().process()
        assertEquals(listOf("""{"Name":{"title":[{"text":{"content":"점심"}}]},"Done":{"checkbox":true}}"""), created.map { it.second.toString() })
    }

    @Test
    fun aViewSkippedWhileWritesWerePendingIsReadAfterTheQueueDrains() = runTest {
        writer().toggle("v1", "p1", "dn", sendDelayMs = ViewWriter.UNDO_WINDOW_MS)
        val views = ViewSync(viewReader, db.viewDao(), { widgetUpdates++ }, clock, CoverSync(CoverStore(coverDir), CoverDownload { null }))
        views.syncAll() // 지연 중인 쓰기가 있어 건너뛴다(새로고침이 4초 안에 눌렸다)
        assertEquals(0, viewRequests.size)
        val later = Clock.offset(clock, java.time.Duration.ofMillis(ViewWriter.UNDO_WINDOW_MS))
        ViewWriteProcessor(notionReader, notionWriter, db, views, { widgetUpdates++ }, later, Dispatchers.Unconfined).process()
        assertEquals(listOf("v1"), viewRequests) // 쓰기가 나간 뒤 건너뛴 보기를 받는다
    }
}
