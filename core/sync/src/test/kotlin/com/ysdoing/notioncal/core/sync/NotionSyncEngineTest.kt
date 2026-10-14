package com.ysdoing.notioncal.core.sync

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.ysdoing.notioncal.core.data.SchemaIssue
import com.ysdoing.notioncal.core.data.SyncTrigger
import com.ysdoing.notioncal.core.data.WidgetRefresher
import com.ysdoing.notioncal.core.data.db.DataSourceEntity
import com.ysdoing.notioncal.core.data.db.NotionCalDatabase
import com.ysdoing.notioncal.core.notion.NotionError
import com.ysdoing.notioncal.core.notion.NotionException
import com.ysdoing.notioncal.core.notion.NotionJson
import com.ysdoing.notioncal.core.notion.NotionReader
import com.ysdoing.notioncal.core.notion.QueryResult
import com.ysdoing.notioncal.core.notion.dto.DataSourceDto
import com.ysdoing.notioncal.core.notion.dto.PageDto
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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

/** NS-03 읽기 동기화: 범위·증분·삭제 감지와 오류 시 캐시 유지. */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
class NotionSyncEngineTest {
    private lateinit var db: NotionCalDatabase
    private val remote = FakeNotion()
    private var now = Instant.parse("2026-10-13T03:00:00Z") // 서울 12:00

    private var widgetRefreshes = 0

    private fun engine() = NotionSyncEngine(
        remote, db, Clock.fixed(now, ZoneOffset.UTC), UnconfinedTestDispatcher(), WidgetRefresher { widgetRefreshes++ },
    )

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext<Context>(), NotionCalDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    private suspend fun register() = db.dataSourceDao().upsert(
        DataSourceEntity(
            dataSourceId = DS, databaseId = "db", name = "Calendar", colorHex = "#0B6E99",
            titlePropId = "title", datePropId = "dAtE", donePropId = "dOnE", doneType = "CHECKBOX",
            doneOption = null, undoneOption = null, colorPropId = null, sortPropId = null,
        ),
    )

    private suspend fun local(): Map<String, String> =
        db.eventDao().observeRange(LocalDate.of(1900, 1, 1), LocalDate.of(2999, 12, 31)).first().associate { it.event.pageId to it.event.title }

    private fun schemaOf(props: String) = NotionJson.decodeFromString(DataSourceDto.serializer(), """{"id":"ds","properties":{$props}}""")

    /** NS-09: 날짜 속성이 사라져도 앱은 죽지 않고 캐시를 그대로 두며, 알림(사라짐·이름 바뀜)을 남긴다. */
    @Test
    fun missingDatePropertyKeepsCacheAndRecordsIssues() = runTest {
        register()
        remote.put("a", "영어 스터디", "2026-10-13")
        engine().sync(SyncTrigger.APP_OPEN)
        val firstSyncAt = db.dataSourceDao().get(DS)!!.lastSyncAt

        remote.schema = schemaOf(
            """"제목":{"id":"title","name":"제목","type":"title"},"완료":{"id":"dOnE","name":"완료","type":"checkbox"}""",
        )
        now = now.plusSeconds(600)
        val outcome = engine().sync(SyncTrigger.APP_OPEN)

        assertNull(outcome.error)
        assertEquals(mapOf("a" to "영어 스터디"), local())
        val source = db.dataSourceDao().get(DS)!!
        assertEquals("동기화를 건너뛰어 기준 시각이 그대로다", firstSyncAt, source.lastSyncAt)
        val issues = SchemaIssue.parse(source.schemaIssues)
        assertTrue(issues.contains(SchemaIssue(SchemaIssue.DATE, SchemaIssue.MISSING, oldName = "Date")))
        assertTrue(issues.contains(SchemaIssue(SchemaIssue.TITLE, SchemaIssue.RENAMED, "Name", "제목")))
        assertTrue(issues.any { it.blocksSync })
    }

    /** NS-09: 완료 속성만 사라지면 그 값 없이 나머지는 동기화한다. 같은 알림은 한 번 확인하면 다시 띄우지 않는다. */
    @Test
    fun missingOptionalPropertyStillSyncs() = runTest {
        register()
        remote.schema = schemaOf(
            """"Name":{"id":"title","name":"Name","type":"title"},"Date":{"id":"dAtE","name":"Date","type":"date"}""",
        )
        remote.put("a", "영어 스터디", "2026-10-13")
        engine().sync(SyncTrigger.APP_OPEN)

        assertEquals(mapOf("a" to "영어 스터디"), local())
        val issues = SchemaIssue.parse(db.dataSourceDao().get(DS)!!.schemaIssues)
        assertEquals(listOf(SchemaIssue(SchemaIssue.DONE, SchemaIssue.MISSING)), issues)

        db.dataSourceDao().setSchemaIssues(DS, SchemaIssue.encode(SchemaIssue.acknowledge(issues)))
        now = now.plusSeconds(600)
        engine().sync(SyncTrigger.APP_OPEN)
        assertTrue(SchemaIssue.parse(db.dataSourceDao().get(DS)!!.schemaIssues).all { it.acknowledged })
    }

    @Test
    fun firstSyncPullsRangeAndSkipsPagesWithoutDate() = runTest {
        register()
        remote.put("a", "영어 스터디", "2026-10-13")
        remote.put("b", "기간 일정", "2026-10-30", end = "2026-11-02")
        remote.put("nodate", "날짜 없음", null)
        remote.put("far", "범위 밖", "2027-06-01")

        val outcome = engine().sync(SyncTrigger.APP_OPEN)

        assertNull(outcome.error)
        assertEquals(mapOf("a" to "영어 스터디", "b" to "기간 일정"), local())
        val source = db.dataSourceDao().get(DS)!!
        assertEquals(now.toEpochMilli(), source.lastSyncAt)
        assertEquals(now.toEpochMilli(), source.lastFullSyncAt)
        // 범위 조회는 8/1에서 31일 앞당긴 날짜부터, +09:00 오프셋으로 요청한다.
        assertEquals("2026-07-01T00:00:00+09:00", remote.lastRangeFrom)
        assertEquals("OK", db.syncLogDao().observeLatest().first()!!.result)
        // WG-05: 받은 내용이 있으면 위젯 갱신을 요청한다.
        assertTrue(widgetRefreshes > 0)
    }

    @Test
    fun incrementalUpsertsEditsAndRemovesClearedDates() = runTest {
        register()
        remote.put("a", "원래 제목", "2026-10-13")
        remote.put("b", "날짜 지울 항목", "2026-10-14")
        engine().sync(SyncTrigger.APP_OPEN)

        now = now.plusSeconds(600)
        remote.put("a", "바뀐 제목", "2026-10-15", editedAt = now.minusSeconds(60))
        remote.put("b", "날짜 지울 항목", null, editedAt = now.minusSeconds(60))
        remote.put("c", "새 항목", "2026-10-20", editedAt = now.minusSeconds(60))
        engine().sync(SyncTrigger.PERIODIC)

        assertEquals(mapOf("a" to "바뀐 제목", "c" to "새 항목"), local())
        // 증분 기준 = 마지막 동기화 − 2분, 서울 오프셋.
        assertEquals("2026-10-13T11:58:00+09:00", remote.lastEditedSince)
    }

    @Test
    fun appOpenReconcileDeletesTrashedPageButKeepsMovedOne() = runTest {
        register()
        remote.put("keep", "유지", "2026-10-10")
        remote.put("trashed", "지울 항목", "2026-10-11")
        remote.put("moved", "옮긴 항목", "2026-10-12")
        engine().sync(SyncTrigger.APP_OPEN)

        now = now.plusSeconds(600)
        remote.trash("trashed")
        // 범위 쿼리에는 안 나오지만(필터 동작이 문서와 다를 때를 흉내) 단건 조회로는 살아 있는 페이지.
        remote.hideFromQueries("moved")
        engine().sync(SyncTrigger.APP_OPEN)

        assertEquals(setOf("keep", "moved"), local().keys)
        assertTrue("moved" in remote.singleFetches)
    }

    @Test
    fun unauthorizedKeepsCacheAndLogsError() = runTest {
        register()
        remote.put("a", "캐시", "2026-10-13")
        engine().sync(SyncTrigger.APP_OPEN)

        remote.failWith = NotionError.Unauthorized
        widgetRefreshes = 0
        val outcome = engine().sync(SyncTrigger.PULL_TO_REFRESH)
        assertEquals("일정은 그대로지만 위젯 오류 점(WG-07)을 위해 갱신을 한 번 요청한다", 1, widgetRefreshes)

        assertEquals(NotionError.Unauthorized, outcome.error)
        assertEquals(mapOf("a" to "캐시"), local())
        val log = db.syncLogDao().observeLatest().first()
        assertNotNull(log)
        assertEquals("ERROR", log!!.result)
        assertEquals("Unauthorized", log.error)
        assertEquals("PULL_TO_REFRESH", log.trigger)
    }

    @Test
    fun incompleteQueryDoesNotDeleteAnything() = runTest {
        register()
        remote.put("a", "남아야 함", "2026-10-13")
        engine().sync(SyncTrigger.APP_OPEN)

        remote.hideFromQueries("a")
        remote.trash("a")
        remote.incomplete = true
        now = now.plusSeconds(600)
        engine().sync(SyncTrigger.APP_OPEN)

        assertEquals(setOf("a"), local().keys)
    }

    @Test
    fun noRegisteredSourcesWritesNoLog() = runTest {
        engine().sync(SyncTrigger.APP_OPEN)
        assertNull(db.syncLogDao().observeLatest().first())
    }

    private companion object {
        const val DS = "ds-calendar"
    }
}

/** 메모리 노션: 필터 종류(증분·날짜 범위)만 해석한다. */
private class FakeNotion : NotionReader {
    private val pages = linkedMapOf<String, PageDto>()
    private val hidden = mutableSetOf<String>()
    val singleFetches = mutableListOf<String>()
    var failWith: NotionError? = null
    var incomplete = false
    var lastRangeFrom: String? = null
    var lastEditedSince: String? = null

    /** NS-09 테스트가 속성을 지우거나 이름을 바꿀 수 있게 바꿀 수 있다. */
    var schema: DataSourceDto = NotionJson.decodeFromString(
        DataSourceDto.serializer(),
        """{"id":"ds","properties":{
            "Name":{"id":"title","name":"Name","type":"title"},
            "Date":{"id":"dAtE","name":"Date","type":"date"},
            "완료":{"id":"dOnE","name":"완료","type":"checkbox"}}}""",
    )

    fun put(id: String, title: String, start: String?, end: String? = null, editedAt: Instant = Instant.parse("2026-10-01T00:00:00Z")) {
        val date = start?.let { """{"start":"$it","end":${end?.let { e -> "\"$e\"" } ?: "null"}}""" } ?: "null"
        pages[id] = NotionJson.decodeFromString(
            PageDto.serializer(),
            """{"id":"$id","url":"https://www.notion.so/$id","last_edited_time":"$editedAt","in_trash":false,"properties":{
                "Name":{"id":"title","type":"title","title":[{"plain_text":"$title"}]},
                "Date":{"id":"dAtE","type":"date","date":$date},
                "완료":{"id":"dOnE","type":"checkbox","checkbox":false}}}""",
        )
    }

    fun trash(id: String) {
        pages[id] = pages.getValue(id).copy(inTrash = true)
    }

    fun hideFromQueries(id: String) {
        hidden += id
    }

    override suspend fun dataSource(dataSourceId: String): DataSourceDto {
        failWith?.let { throw NotionException(it) }
        return schema
    }

    override suspend fun queryAll(dataSourceId: String, filter: JsonObject?): QueryResult {
        failWith?.let { throw NotionException(it) }
        val live = pages.values.filter { !it.inTrash && it.id !in hidden }
        val result = when {
            filter?.containsKey("timestamp") == true -> {
                val since = filter["last_edited_time"]!!.jsonObject["on_or_after"]!!.jsonPrimitive.content
                lastEditedSince = since
                val sinceInstant = java.time.OffsetDateTime.parse(since).toInstant()
                live.filter { !Instant.parse(it.lastEditedTime).isBefore(sinceInstant) }
            }
            filter?.containsKey("and") == true -> {
                val conds = filter["and"]!!.jsonArray.map { it.jsonObject["date"]!!.jsonObject }
                val from = conds[0]["on_or_after"]!!.jsonPrimitive.content.also { lastRangeFrom = it }.take(10)
                val to = conds[1]["on_or_before"]!!.jsonPrimitive.content.take(10)
                live.filter { page ->
                    val start = startOf(page) ?: return@filter false
                    start >= from && start <= to
                }
            }
            else -> live
        }
        return QueryResult(result, complete = !incomplete)
    }

    override suspend fun pageOrNull(pageId: String): PageDto? {
        failWith?.let { throw NotionException(it) }
        singleFetches += pageId
        return pages[pageId]
    }

    private fun startOf(page: PageDto): String? =
        (page.properties["Date"]?.jsonObject?.get("date") as? JsonObject)?.get("start")?.jsonPrimitive?.content?.take(10)
}
