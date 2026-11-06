package com.ysdoing.notioncal.core.notion

import com.ysdoing.notioncal.core.notion.http.RetryInterceptor
import com.ysdoing.notioncal.core.notion.http.TokenBucket
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import mockwebserver3.MockResponse
import mockwebserver3.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import kotlin.random.Random

/** VW-01 보기 읽기 계약: 호출 경로, 보기 설정 파싱, 값을 받는 쿼리의 필터·정렬·행 수. */
class ViewReaderTest {
    private val server = MockWebServer()
    private val time = FakeTime()
    private lateinit var reader: ViewReader

    @Before
    fun setUp() {
        server.start()
        val api = createNotionApi(
            baseUrl = server.url("/"),
            tokenProvider = { "test-token-not-real" },
            retry = RetryInterceptor(sleeper = time, random = Random(0)),
            bucket = TokenBucket(clock = time, sleeper = time),
            socketTag = null,
        )
        reader = NotionClient(api, NotionJson)
    }

    @After
    fun tearDown() = server.close()

    private fun ok(body: String) = MockResponse.Builder().code(200).body(body).build()

    private val viewJson = """
        {"object":"view","id":"v1","name":"할 일","type":"table",
         "filter":{"property":"p1","checkbox":{"equals":false}},
         "sorts":[{"property":"Name","direction":"ascending"}],
         "quick_filters":{"p2":{"select":{"is_not_empty":true}}},
         "configuration":{"type":"table","properties":[
           {"property_id":"title","property_name":"Name","visible":true,"width":200},
           {"property_id":"p2","property_name":"Ctgr","visible":false}]}}
    """.trimIndent()

    @Test
    fun listsEveryPageOfViewIdsNotOnlyTheFirst() = runTest {
        // CN-03: 보기가 100개를 넘어 목록이 나뉘어 와도 끝까지 받는다(예전에는 첫 쪽만 읽었다).
        server.enqueue(ok("""{"object":"list","results":[{"object":"view","id":"v1"},{"object":"view","id":"v2"}],"has_more":true,"next_cursor":"c2"}"""))
        server.enqueue(ok("""{"object":"list","results":[{"object":"view","id":"v3"}],"has_more":false}"""))
        assertEquals(listOf("v1", "v2", "v3"), reader.viewIds("ds1"))
        assertNull(server.takeRequest().url.queryParameter("start_cursor"))
        assertEquals("c2", server.takeRequest().url.queryParameter("start_cursor"))
    }

    // --- Phase 11a 보기 설정 원본과 '더 있음'

    @Test
    fun keepsTheWholeConfigurationObjectNotOnlyTheProperties() = runTest {
        // 보드의 group_by 같은 유형별 키를 앱이 읽도록 configuration 원본을 보존한다(알려진 키만 쓰고 나머지는 그대로 둔다).
        server.enqueue(
            ok(
                """{"object":"view","id":"v1","name":"보드","type":"board","configuration":{"type":"board",
                    "group_by":{"property_id":"st","type":"status","sort":{"type":"manual"}},
                    "properties":[{"property_id":"title","property_name":"Name","visible":true},{"property_id":"st","property_name":"Status","visible":false}]}}""",
            ),
        )
        val config = reader.view("v1").configuration!!
        assertEquals(listOf("Name" to true, "Status" to false), config.properties.map { it.propertyName to it.visible })
        assertEquals("st", config.raw["group_by"]!!.jsonObject["property_id"]!!.jsonPrimitive.content)
    }

    @Test
    fun aConfigurationThatIsNotAnObjectIsANotionError() = runTest {
        server.enqueue(ok("""{"object":"view","id":"v1","name":"x","type":"table","configuration":[1,2]}"""))
        val error = runCatching { reader.view("v1") }.exceptionOrNull()
        assertTrue("NotionException이어야 한다: $error", error is NotionException) // 호출부(보기 등록 목록)는 NotionException만 잡는다
    }

    @Test
    fun rowsReportWhetherNotionHasMoreThanTheCap() = runTest {
        server.enqueue(ok("""{"object":"list","results":[{"object":"page","id":"a","last_edited_time":"2026-11-01T00:00:00.000Z"}],"has_more":true,"next_cursor":"c2"}"""))
        server.enqueue(ok("""{"object":"list","results":[{"object":"page","id":"a","last_edited_time":"2026-11-01T00:00:00.000Z"}],"has_more":false}"""))
        val view = com.ysdoing.notioncal.core.notion.dto.ViewDto(id = "v1")
        assertEquals(listOf(1, true), reader.viewRows("ds1", view).let { listOf(it.pages.size, it.hasMore) })
        assertEquals(false, reader.viewRows("ds1", view).hasMore)
    }

    // --- Phase 10-7 보기 쿼리(`POST /v1/views/{id}/queries`): 보기가 보여 주는 페이지 id를 순서대로. 값은 주지 않는다.

    private fun queryPage(id: String, vararg pages: String, more: Boolean = false, cursor: String? = null, status: String? = null) =
        ok(
            """{"object":"view_query","id":"$id","results":[${pages.joinToString(",") { """{"object":"page","id":"$it"}""" }}],"has_more":$more,""" +
                (cursor?.let { """"next_cursor":"$it",""" } ?: "") + (status?.let { """"request_status":{"type":"$it"},""" } ?: "") + """"type":"view_query"}""",
        )

    @Test
    fun readsViewQueryIdsInViewOrderAcrossPages() = runTest {
        server.enqueue(queryPage("q1", "a", "b", more = true, cursor = "c2"))
        server.enqueue(queryPage("q1", "c"))
        val result = reader.viewQueryIds("v1", limit = 200)
        assertEquals(listOf("a", "b", "c"), result.ids)
        assertEquals(true, result.complete)
        val first = server.takeRequest()
        assertEquals("POST", first.method)
        assertEquals("/v1/views/v1/queries", first.url.encodedPath)
        assertEquals(100, first.body!!.utf8().let { kotlinx.serialization.json.Json.parseToJsonElement(it).jsonObject["page_size"]!!.jsonPrimitive.content.toInt() })
        val second = server.takeRequest()
        assertEquals("/v1/views/v1/queries/q1", second.url.encodedPath)
        assertEquals("c2", second.url.queryParameter("start_cursor"))
    }

    @Test
    fun theLimitSizesTheFirstRequestAndStopsPaging() = runTest {
        server.enqueue(queryPage("q1", "a", "b", more = true, cursor = "c2"))
        val result = reader.viewQueryIds("v1", limit = 2) // 위젯은 앞 50행만 받는다: 그만큼만 비교한다
        assertEquals(listOf("a", "b"), result.ids)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun anExpiredQueryIsCreatedAgainOnce() = runTest {
        // 쿼리 결과는 15분 뒤 만료된다: 쪽을 넘기다 404면 처음부터 한 번 다시 만든다.
        server.enqueue(queryPage("q1", "a", more = true, cursor = "c2"))
        server.enqueue(MockResponse.Builder().code(404).body("""{"object":"error","status":404,"code":"object_not_found"}""").build())
        server.enqueue(queryPage("q2", "a", "b"))
        val result = reader.viewQueryIds("v1", limit = 200)
        assertEquals(listOf("a", "b"), result.ids)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun aViewThatCannotBeQueriedFailsAfterOneRetry() = runTest {
        repeat(2) { server.enqueue(MockResponse.Builder().code(404).body("""{"object":"error","status":404,"code":"object_not_found"}""").build()) }
        val error = runCatching { reader.viewQueryIds("v1") }.exceptionOrNull() as NotionException
        assertEquals(NotionError.NotShared, error.error)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun anIncompleteStatusIsReported() = runTest {
        // 쿼리당 1만 건을 넘으면 노션이 request_status: incomplete로 알린다.
        server.enqueue(queryPage("q1", "a", status = "incomplete"))
        assertEquals(false, reader.viewQueryIds("v1").complete)
    }

    @Test
    fun listsViewIdsForADataSource() = runTest {
        server.enqueue(ok("""{"object":"list","results":[{"object":"view","id":"v1"},{"object":"view","id":"v2"}],"has_more":false}"""))
        assertEquals(listOf("v1", "v2"), reader.viewIds("ds1"))
        val url = server.takeRequest().url
        assertEquals("/v1/views", url.encodedPath)
        assertEquals("ds1", url.queryParameter("data_source_id"))
    }

    @Test
    fun parsesViewSettings() = runTest {
        server.enqueue(ok(viewJson))
        val view = reader.view("v1")
        assertEquals("/v1/views/v1", server.takeRequest().url.encodedPath)
        assertEquals("table", view.type)
        assertEquals(listOf("Name" to true, "Ctgr" to false), view.configuration!!.properties.map { it.propertyName to it.visible })
    }

    @Test
    fun rowsQueryCarriesViewFilterQuickFilterAndSorts() = runTest {
        server.enqueue(ok(viewJson))
        server.enqueue(ok("""{"object":"list","results":[],"has_more":true,"next_cursor":"c"}"""))
        val view = reader.view("v1")
        server.takeRequest()
        reader.rows("ds1", view)
        val request = server.takeRequest()
        assertEquals("/v1/data_sources/ds1/query", request.url.encodedPath)
        val body = kotlinx.serialization.json.Json.parseToJsonElement(request.body!!.utf8()).jsonObject
        assertEquals(2, body["filter"]!!.jsonObject["and"]!!.jsonArray.size)
        assertEquals("Name", body["sorts"]!!.jsonArray[0].jsonObject["property"]!!.jsonPrimitive.content)
        assertEquals(VIEW_ROW_LIMIT, body["page_size"]!!.jsonPrimitive.content.toInt())
        assertNull(body["start_cursor"])
    }

    @Test
    fun viewFilterIsNullWithoutAnyFilter() {
        assertNull(viewFilter(com.ysdoing.notioncal.core.notion.dto.ViewDto(id = "x", quickFilters = kotlinx.serialization.json.JsonObject(emptyMap()))))
    }
}
